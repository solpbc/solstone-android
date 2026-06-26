// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.solstone.core.model.BundleFile
import app.solstone.core.model.QueueState
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.observer.SegmentReconciler
import app.solstone.core.queue.QueueEvent
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.pl.transport.conscrypt.openAuthenticatedClient
import app.solstone.platform.pl.transport.conscrypt.openRelaySyncClient
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val stores = syncStores(applicationContext)
            when (val credentials = recoverSyncCredentials(stores.endpointStore, stores.credentialStore, stores.identityStore)) {
                is SyncCredentials.NeedsRepair -> Result.failure()
                is SyncCredentials.Ready -> drain(credentials)
            }
        }

    private fun drain(credentials: SyncCredentials.Ready): Result {
        val db = openSolstonePersistenceDatabase(applicationContext)
        try {
            openSyncClient(credentials).use { client ->
                val status = try {
                    client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0)).status
                } catch (_: IOException) {
                    return Result.retry()
                }
                return when (decideReachability(paired = true, reachable = status == 200)) {
                    ReachabilityVerdict.SKIP -> Result.failure()
                    ReachabilityVerdict.RESCHEDULE -> Result.retry()
                    ReachabilityVerdict.DRAIN -> drainSegments(db.segmentDao(), SegmentReconciler(client, credentials.handle), ObserverIngestClient(client) {
                        "solstoneSync${System.nanoTime()}"
                    }, credentials.handle)
                }
            }
        } catch (_: IOException) {
            return Result.retry()
        } catch (_: Exception) {
            return Result.failure()
        } finally {
            db.close()
        }
    }

    private fun openSyncClient(credentials: SyncCredentials.Ready) =
        when (val transport = credentials.transport) {
            is SyncTransport.Direct -> openAuthenticatedClient(transport.endpoint, credentials.credential)
            is SyncTransport.Relay -> openRelaySyncClient(
                transport.relayOrigin,
                transport.instanceId,
                transport.deviceToken,
                credentials.credential,
            )
        }

    private fun drainSegments(
        dao: SegmentDao,
        reconciler: SegmentReconciler,
        ingestClient: ObserverIngestClient,
        handle: String,
    ): Result {
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
                                    host = listOf(Build.MANUFACTURER, Build.MODEL)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" ")
                                        .ifBlank { "android" },
                                    platform = "android",
                                ),
                            )
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
            halted -> Result.failure()
            shouldRetry -> Result.retry()
            else -> Result.success()
        }
    }

    private fun readPayloadFor(spoolDir: File, segment: SegmentRow, file: BundleFile): ByteArray {
        val segmentDir = File(File(File(spoolDir, segment.day), segment.stream), segment.segment)
        val payload = File(segmentDir, file.name)
        require(payload.canonicalFile.parentFile == segmentDir.canonicalFile) {
            "payload name must not contain path separators: ${file.name}"
        }
        return payload.readBytes()
    }
}
