// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ReconcileAuthException
import app.solstone.core.observer.ReconcileUnavailableException
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.queue.QueueEvent
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import java.io.FileNotFoundException
import java.io.IOException

const val DRAIN_SEGMENT_CAP = 50

interface DrainStore {
    fun syncState(): SyncStateRow?
    fun segmentsForDrain(): List<SegmentRow>
    fun filesBySegmentId(id: String): List<SegmentFileRow>
    fun recordDedupeChecked(id: String, at: Long): Int
    fun advanceState(id: String, event: QueueEvent): QueueState
    fun recordAttempt(id: String, attempts: Int, at: Long): Int
    fun recordUploaded(id: String, serverKey: String?): Int
    fun recordFailure(id: String, code: Int?, error: String?): Int
    fun pendingCount(stream: String): Int
    fun upsertSyncState(row: SyncStateRow)
}

class RoomDrainStore(private val dao: SegmentDao) : DrainStore {
    override fun syncState(): SyncStateRow? = dao.syncState()
    override fun segmentsForDrain(): List<SegmentRow> = dao.segmentsForDrain(MAIN_STREAM)
    override fun filesBySegmentId(id: String): List<SegmentFileRow> = dao.filesBySegmentId(id)
    override fun recordDedupeChecked(id: String, at: Long): Int = dao.recordDedupeChecked(id, at)
    override fun advanceState(id: String, event: QueueEvent): QueueState = dao.advanceState(id, event)
    override fun recordAttempt(id: String, attempts: Int, at: Long): Int = dao.recordAttempt(id, attempts, at)
    override fun recordUploaded(id: String, serverKey: String?): Int = dao.recordUploaded(id, serverKey)
    override fun recordFailure(id: String, code: Int?, error: String?): Int = dao.recordFailure(id, code, error)
    override fun pendingCount(stream: String): Int = dao.pendingCount(stream)
    override fun upsertSyncState(row: SyncStateRow) = dao.upsertSyncState(row)
}

fun drainSegments(
    store: DrainStore,
    reconcile: (List<BundleManifest>, String) -> List<ReconcileVerdict>,
    ingest: (BundleManifest, (BundleFile) -> ByteArray) -> IngestOutcome,
    readPayload: (SegmentRow, BundleFile) -> ByteArray,
    now: () -> Long,
    log: (String, Throwable?) -> Unit,
): DrainReport {
    val syncState = store.syncState()
    val priorLastSuccessAt = syncState?.lastSuccessAt
    var lastFailureAt = syncState?.lastFailureAt
    var halted = false
    var failedThisRun = false
    var lastErrorReason: String? = null
    val due = selectDrainSegments(store.segmentsForDrain(), now())
    val selected = due.take(DRAIN_SEGMENT_CAP)
    val dueRemaining = due.size > selected.size

    for ((day, daySegments) in selected.groupBy { it.day }) {
        val manifests = daySegments.associateWith { segment ->
            reconstructManifest(segment, store.filesBySegmentId(segment.id))
        }
        val verdicts = try {
            reconcile(manifests.values.toList(), day)
        } catch (e: RelayWebSocketClosedException) {
            throw e
        } catch (e: ReconcileAuthException) {
            log("reconcile auth halt day=$day", e)
            failedThisRun = true
            halted = true
            break
        } catch (e: ReconcileUnavailableException) {
            log("reconcile unavailable day=$day", e)
            failedThisRun = true
            continue
        } catch (e: IOException) {
            log("reconcile io day=$day", e)
            failedThisRun = true
            continue
        }
        val actions = planDayDrain(verdicts, daySegments).associateBy(::drainActionId)
        daySegments.forEach { store.recordDedupeChecked(it.id, now()) }

        for (segment in daySegments) {
            val manifest = manifests.getValue(segment)
            if (!claimForUpload(store, segment, log)) {
                failedThisRun = true
                continue
            }
            store.recordAttempt(segment.id, segment.attemptCount + 1, now())

            when (actions.getValue(segment.id)) {
                is DrainAction.Skip -> {
                    store.advanceState(segment.id, QueueEvent.MARK_UPLOADED)
                }
                is DrainAction.Upload -> {
                    val result = try {
                        resolveIngestOutcome(
                            ingest(manifest) { file -> readPayload(segment, file) },
                        )
                    } catch (e: RelayWebSocketClosedException) {
                        throw e
                    } catch (e: FileNotFoundException) {
                        val reason = "payload missing"
                        log("payload missing ${segment.id}", e)
                        markPayloadFailed(store, segment, reason)
                        failedThisRun = true
                        lastFailureAt = now()
                        lastErrorReason = reason
                        continue
                    } catch (e: IllegalArgumentException) {
                        val reason = "payload unreadable"
                        log("payload unreadable ${segment.id}", e)
                        markPayloadFailed(store, segment, reason)
                        failedThisRun = true
                        lastFailureAt = now()
                        lastErrorReason = reason
                        continue
                    } catch (e: IOException) {
                        log("ingest io ${segment.id}", e)
                        resolveIoError()
                    }

                    when (result) {
                        is SegmentSyncResult.Uploaded -> {
                            store.advanceState(segment.id, QueueEvent.MARK_UPLOADED)
                            store.recordUploaded(segment.id, result.serverKey)
                        }
                        is SegmentSyncResult.Retry -> {
                            store.advanceState(segment.id, QueueEvent.MARK_FAILED)
                            store.recordFailure(segment.id, result.status, "retry")
                            failedThisRun = true
                            lastFailureAt = now()
                            lastErrorReason = "retry" + (result.status?.let { " ($it)" } ?: "")
                        }
                        is SegmentSyncResult.HardFail -> {
                            store.advanceState(segment.id, QueueEvent.MARK_FAILED)
                            store.recordFailure(segment.id, result.status, "hard failure")
                            failedThisRun = true
                            lastFailureAt = now()
                            lastErrorReason = "hard failure (${result.status})"
                        }
                        is SegmentSyncResult.AuthHalt -> {
                            store.advanceState(segment.id, QueueEvent.MARK_FAILED)
                            store.recordFailure(segment.id, result.status, "auth halted")
                            failedThisRun = true
                            halted = haltsDrain(result)
                            lastFailureAt = now()
                            lastErrorReason = "auth halted (${result.status})"
                        }
                    }
                }
            }

            if (halted) break
        }

        if (halted) break
    }

    val pendingAfter = store.pendingCount(MAIN_STREAM)
    val cleanDrain = !halted && !failedThisRun && pendingAfter == 0
    val workOutcome = when {
        halted -> SyncOutcome.FAILURE
        failedThisRun -> SyncOutcome.RETRY
        dueRemaining -> SyncOutcome.RETRY
        else -> SyncOutcome.SUCCESS
    }
    val lastSuccessAt = advanceLastSuccess(priorLastSuccessAt, cleanDrain, now())
    store.upsertSyncState(nextSyncState(pendingAfter, lastSuccessAt, lastFailureAt))
    return DrainReport(workOutcome, cleanDrain, failedThisRun, lastErrorReason)
}

private fun drainActionId(action: DrainAction): String =
    when (action) {
        is DrainAction.Skip -> action.id
        is DrainAction.Upload -> action.id
    }

private fun claimForUpload(
    store: DrainStore,
    segment: SegmentRow,
    log: (String, Throwable?) -> Unit,
): Boolean =
    try {
        when (segment.state) {
            QueueState.SEALED -> store.advanceState(segment.id, QueueEvent.START_UPLOAD)
            QueueState.FAILED -> store.advanceState(segment.id, QueueEvent.RETRY)
            QueueState.UPLOADING -> {
                store.advanceState(segment.id, QueueEvent.MARK_FAILED)
                store.advanceState(segment.id, QueueEvent.RETRY)
            }
            else -> error("segment is not drainable: ${segment.id} ${segment.state}")
        }
        true
    } catch (e: Exception) {
        log("claim failed ${segment.id}", e)
        false
    }

private fun markPayloadFailed(store: DrainStore, segment: SegmentRow, reason: String) {
    store.advanceState(segment.id, QueueEvent.MARK_FAILED)
    store.recordFailure(segment.id, null, reason)
}
