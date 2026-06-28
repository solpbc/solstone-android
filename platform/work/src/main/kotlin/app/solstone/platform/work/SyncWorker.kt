// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.solstone.core.identity.ClientCredential
import app.solstone.core.model.BundleFile
import app.solstone.core.model.QueueState
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.ObserverRegistration
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.observer.SegmentReconciler
import app.solstone.core.queue.QueueEvent
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.pl.transport.conscrypt.RelayDialWaitingException
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import app.solstone.platform.pl.transport.conscrypt.defaultHttpsPoster
import app.solstone.platform.pl.transport.conscrypt.openAuthenticatedClient
import app.solstone.platform.pl.transport.conscrypt.openRelaySyncClient
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SyncWorker"

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val stores = syncStores(applicationContext)
            when (val credentials = recoverSyncCredentials(stores.endpointStore, stores.credentialStore, stores.identityStore)) {
                is SyncCredentials.NeedsRepair -> Result.failure()
                is SyncCredentials.Ready -> sync(stores, credentials)
            }
        }

    private fun sync(stores: SyncStores, credentials: SyncCredentials.Ready): Result {
        val db = openSolstonePersistenceDatabase(applicationContext)
        val poster = defaultHttpsPoster()
        try {
            val transport = when (val current = credentials.transport) {
                is SyncTransport.Direct -> current
                is SyncTransport.Relay -> when (
                    val maintained = maintainRelayToken(
                        identity = credentials.identity,
                        transport = current,
                        poster = poster,
                        identityStore = stores.identityStore,
                        nowEpochMs = System.currentTimeMillis(),
                    )
                ) {
                    is RelayTokenResult.Ready -> maintained.transport
                    RelayTokenResult.ReconnectNeeded -> return Result.failure()
                }
            }
            val outcome = when (transport) {
                is SyncTransport.Direct -> syncWithTransport(db.segmentDao(), stores, credentials, transport)
                is SyncTransport.Relay -> dialWithReactiveRefresh(
                    identity = credentials.identity,
                    transport = transport,
                    poster = poster,
                    identityStore = stores.identityStore,
                    dial = RelayDial { relayTransport ->
                        syncWithTransport(db.segmentDao(), stores, credentials, relayTransport)
                    },
                )
            }
            return outcome.toWorkResult()
        } catch (_: RelayDialWaitingException) {
            Log.i(TAG, "home offline, waiting; will retry")
            return Result.retry()
        } catch (_: RelayWebSocketClosedException) {
            return Result.retry()
        } catch (_: IOException) {
            return Result.retry()
        } catch (_: Exception) {
            return Result.failure()
        } finally {
            db.close()
        }
    }

    private fun syncWithTransport(
        dao: SegmentDao,
        stores: SyncStores,
        credentials: SyncCredentials.Ready,
        transport: SyncTransport,
    ): SyncOutcome {
        openSyncClient(transport, credentials.credential).use { client ->
            val status = try {
                client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0)).status
            } catch (e: RelayWebSocketClosedException) {
                throw e
            } catch (_: IOException) {
                return SyncOutcome.RETRY
            }
            return when (decideReachability(paired = true, reachable = status == 200)) {
                ReachabilityVerdict.SKIP -> SyncOutcome.FAILURE
                ReachabilityVerdict.RESCHEDULE -> SyncOutcome.RETRY
                ReachabilityVerdict.DRAIN -> when (
                    val outcome = registerThenDrain(
                        client = client,
                        existingHandle = credentials.identity.observerHandle,
                        register = { c ->
                            ObserverRegistration(c).register(
                                platform = "android",
                                hostname = deviceLabel(),
                                streamType = MAIN_STREAM,
                                version = appVersion(),
                            ).handle
                        },
                        persist = { handle -> stores.identityStore.save(credentials.identity.copy(observerHandle = handle)) },
                        drain = { c, handle ->
                            drainSegments(
                                dao,
                                SegmentReconciler(c, handle),
                                ObserverIngestClient(c) { "solstoneSync${System.nanoTime()}" },
                                handle,
                            )
                        },
                        onError = { e -> Log.w(TAG, "observer registration failed: ${e.javaClass.simpleName}", e) },
                    )
                ) {
                    RegisterDrainOutcome.Retry -> SyncOutcome.RETRY
                    is RegisterDrainOutcome.Drained -> outcome.result
                }
            }
        }
    }

    private fun openSyncClient(transport: SyncTransport, credential: ClientCredential) =
        when (transport) {
            is SyncTransport.Direct -> openAuthenticatedClient(transport.endpoint, credential)
            is SyncTransport.Relay -> openRelaySyncClient(
                transport.relayOrigin,
                transport.instanceId,
                transport.deviceToken,
                credential,
            )
        }

    private fun drainSegments(
        dao: SegmentDao,
        reconciler: SegmentReconciler,
        ingestClient: ObserverIngestClient,
        handle: String,
    ): SyncOutcome {
        var shouldRetry = false
        var halted = false
        var lastSuccessAt = dao.syncState()?.lastSuccessAt
        var lastFailureAt = dao.syncState()?.lastFailureAt
        val spoolDir = File(applicationContext.filesDir, "spool")
        val sealed = selectDrainSegments(dao.segmentsByState(QueueState.SEALED))
        val now = System.currentTimeMillis()

        for ((day, segments) in sealed.groupBy { it.day }) {
            val manifests = segments.associateWith { segment ->
                reconstructManifest(segment, dao.filesBySegmentId(segment.id))
            }
            val verdicts = reconciler.diff(manifests.values.toList(), day)
            val actions = planDayDrain(verdicts, segments).associateBy { action ->
                when (action) {
                    is DrainAction.Skip -> action.id
                    is DrainAction.Upload -> action.id
                }
            }
            segments.forEach { dao.recordDedupeChecked(it.id, now) }

            for (segment in segments) {
                val manifest = manifests.getValue(segment)
                dao.advanceState(segment.id, QueueEvent.START_UPLOAD)
                dao.recordAttempt(segment.id, segment.attemptCount + 1, System.currentTimeMillis())

                when (actions.getValue(segment.id)) {
                    is DrainAction.Skip -> {
                        dao.advanceState(segment.id, QueueEvent.MARK_UPLOADED)
                        lastSuccessAt = System.currentTimeMillis()
                    }
                    is DrainAction.Upload -> {
                        val result = try {
                            resolveIngestOutcome(
                                ingestClient.ingest(
                                    manifest = manifest,
                                    handle = handle,
                                    fileBytes = { file -> readPayloadFor(spoolDir, segment, file) },
                                    host = deviceLabel(),
                                    platform = "android",
                                ),
                            )
                        } catch (e: RelayWebSocketClosedException) {
                            throw e
                        } catch (_: IOException) {
                            resolveIoError()
                        }

                        when (result) {
                            is SegmentSyncResult.Uploaded -> {
                                dao.advanceState(segment.id, QueueEvent.MARK_UPLOADED)
                                dao.recordUploaded(segment.id, result.serverKey)
                                lastSuccessAt = System.currentTimeMillis()
                            }
                            is SegmentSyncResult.Retry -> {
                                dao.advanceState(segment.id, QueueEvent.MARK_FAILED)
                                dao.recordFailure(segment.id, result.status, "retry")
                                lastFailureAt = System.currentTimeMillis()
                                shouldRetry = true
                            }
                            is SegmentSyncResult.HardFail -> {
                                dao.advanceState(segment.id, QueueEvent.MARK_FAILED)
                                dao.recordFailure(segment.id, result.status, "hard failure")
                                lastFailureAt = System.currentTimeMillis()
                            }
                            is SegmentSyncResult.AuthHalt -> {
                                dao.advanceState(segment.id, QueueEvent.MARK_FAILED)
                                dao.recordFailure(segment.id, result.status, "auth halted")
                                lastFailureAt = System.currentTimeMillis()
                                halted = haltsDrain(result)
                            }
                        }
                    }
                }

                if (halted) break
            }

            if (halted) break
        }

        dao.upsertSyncState(
            nextSyncState(
                pendingCount = dao.pendingCount(MAIN_STREAM),
                lastSuccessAt = lastSuccessAt,
                lastFailureAt = lastFailureAt,
            ),
        )

        return when {
            halted -> SyncOutcome.FAILURE
            shouldRetry -> SyncOutcome.RETRY
            else -> SyncOutcome.SUCCESS
        }
    }

    private fun SyncOutcome.toWorkResult(): Result =
        when (this) {
            SyncOutcome.SUCCESS -> Result.success()
            SyncOutcome.RETRY -> Result.retry()
            SyncOutcome.FAILURE -> Result.failure()
        }

    private fun readPayloadFor(spoolDir: File, segment: SegmentRow, file: BundleFile): ByteArray {
        val segmentDir = File(File(File(spoolDir, segment.day), segment.stream), segment.segment)
        val payload = File(segmentDir, file.name)
        require(payload.canonicalFile.parentFile == segmentDir.canonicalFile) {
            "payload name must not contain path separators: ${file.name}"
        }
        return payload.readBytes()
    }

    private fun deviceLabel(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "android" }

    private fun appVersion(): String =
        runCatching {
            applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.1"
}
