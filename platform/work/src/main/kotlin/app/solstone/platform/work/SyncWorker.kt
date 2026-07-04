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
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.SegmentReconciler
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
            val streamType = streamTypeFromInput(inputData.getString(SyncScheduler.STREAM_TYPE_KEY))
            val stores = syncStores(applicationContext)
            when (val credentials = recoverSyncCredentials(stores.endpointStore, stores.credentialStore, stores.identityStore)) {
                is SyncCredentials.NeedsRepair -> {
                    Log.w(TAG, "sync credentials need repair: ${credentials.reason}")
                    Result.failure()
                }
                is SyncCredentials.Ready -> {
                    if (!SyncDrainGate.tryAcquire()) {
                        Log.i(TAG, "drain already running; deferring")
                        Result.retry()
                    } else {
                        try {
                            sync(stores, credentials, streamType)
                        } finally {
                            SyncDrainGate.release()
                        }
                    }
                }
            }
        }

    private fun sync(stores: SyncStores, credentials: SyncCredentials.Ready, streamType: String): Result {
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
                is SyncTransport.Direct -> syncWithTransport(db.segmentDao(), stores, credentials, transport, streamType)
                is SyncTransport.Relay -> dialWithReactiveRefresh(
                    identity = credentials.identity,
                    transport = transport,
                    poster = poster,
                    identityStore = stores.identityStore,
                    dial = RelayDial { relayTransport ->
                        syncWithTransport(db.segmentDao(), stores, credentials, relayTransport, streamType)
                    },
                    log = { message, throwable -> Log.w(TAG, message, throwable) },
                )
            }
            return outcome.toWorkResult()
        } catch (e: RelayDialWaitingException) {
            Log.i(TAG, "home offline, waiting; will retry", e)
            return Result.retry()
        } catch (e: RelayWebSocketClosedException) {
            Log.w(TAG, "relay ws closed; retry", e)
            return Result.retry()
        } catch (e: IOException) {
            Log.w(TAG, "sync io; retry", e)
            return Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
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
        streamType: String,
    ): SyncOutcome {
        openSyncClient(transport, credentials.credential).use { client ->
            val status = try {
                client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0)).status
            } catch (e: RelayWebSocketClosedException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "status probe io; retry", e)
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
                            registerObserverHandle(
                                client = c,
                                platform = "android",
                                hostname = deviceLabel(),
                                streamType = streamType,
                                version = appVersion(),
                            )
                        },
                        persist = { handle -> stores.identityStore.save(credentials.identity.copy(observerHandle = handle)) },
                        drain = { c, handle ->
                            val spoolDir = File(applicationContext.filesDir, "spool")
                            val report = drainSegments(
                                store = RoomDrainStore(dao),
                                reconcile = SegmentReconciler(c, handle)::diff,
                                ingest = { manifest, fileBytes ->
                                    ObserverIngestClient(c) { "solstoneSync${System.nanoTime()}" }.ingest(
                                        manifest = manifest,
                                        handle = handle,
                                        fileBytes = fileBytes,
                                        host = deviceLabel(),
                                        platform = "android",
                                    )
                                },
                                readPayload = { segment, file -> readPayloadFor(spoolDir, segment, file) },
                                now = System::currentTimeMillis,
                                log = { message, throwable -> Log.w(TAG, message, throwable) },
                            )
                            val emit = emitObserverHealth(
                                client = c,
                                priorState = stores.beaconStateStore.load(),
                                persist = stores.beaconStateStore::save,
                                streamType = streamType,
                                handle = handle,
                                version = appVersion(),
                                now = System.currentTimeMillis(),
                                syncRow = dao.syncState(),
                                cleanDrain = report.cleanDrain,
                                failedThisRun = report.failedThisRun,
                                rawErrorReason = report.lastErrorReason,
                                log = { message, throwable -> Log.w(TAG, message, throwable) },
                            )
                            if (emit == BeaconEmitResult.FAILED) {
                                Log.w(TAG, "observer health beacon not delivered")
                            }
                            report.workOutcome
                        },
                        onError = { e -> Log.w(TAG, "observer registration failed: ${e.javaClass.simpleName}", e) },
                    )
                ) {
                    RegisterDrainOutcome.Retry -> SyncOutcome.RETRY
                    RegisterDrainOutcome.Halt -> SyncOutcome.FAILURE
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
