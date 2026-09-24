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
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.JournalCachePathRefusal
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import java.io.FileNotFoundException
import java.io.IOException

const val DRAIN_SEGMENT_CAP = 50

data class DrainReport(
    val workOutcome: SyncOutcome,
    val cleanDrain: Boolean,
    val failedThisRun: Boolean,
    val lastErrorReason: String?,
)

fun advanceLastSuccess(prior: Long?, cleanDrain: Boolean, now: Long): Long? =
    if (cleanDrain) now else prior

interface DrainStore {
    fun syncState(): SyncStateRow?
    fun segmentsForDrain(): List<SegmentRow>
    fun filesBySegmentId(id: String): List<SegmentFileRow>
    fun advanceState(id: String, event: QueueEvent): QueueState
    fun recordAttempt(id: String, attempts: Int, at: Long): Int
    fun recordUploaded(id: String): Int
    fun recordFailure(id: String, code: Int?, error: String?): Int
    fun pendingCount(stream: String): Int
    fun upsertSyncState(row: SyncStateRow)
}

class RoomDrainStore(private val dao: SegmentDao) : DrainStore {
    override fun syncState(): SyncStateRow? = dao.syncState()
    override fun segmentsForDrain(): List<SegmentRow> = dao.segmentsForDrain(MAIN_STREAM)
    override fun filesBySegmentId(id: String): List<SegmentFileRow> = dao.filesBySegmentId(id)
    override fun advanceState(id: String, event: QueueEvent): QueueState = dao.advanceState(id, event)
    override fun recordAttempt(id: String, attempts: Int, at: Long): Int = dao.recordAttempt(id, attempts, at)
    override fun recordUploaded(id: String): Int = dao.recordUploaded(id)
    override fun recordFailure(id: String, code: Int?, error: String?): Int = dao.recordFailure(id, code, error)
    override fun pendingCount(stream: String): Int = dao.pendingCount(stream)
    override fun upsertSyncState(row: SyncStateRow) = dao.upsertSyncState(row)
}

fun drainSegments(
    store: DrainStore,
    reconcile: (List<BundleManifest>, String) -> List<ReconcileVerdict>,
    ingest: (BundleManifest, (BundleFile) -> ByteArray) -> List<IngestOutcome>,
    readPayload: (SegmentRow, BundleFile) -> ByteArray,
    now: () -> Long,
    log: (String, Throwable?) -> Unit,
    finisher: ConfirmedCopyFinisher,
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

        for (segment in daySegments) {
            val manifest = manifests.getValue(segment)
            if (!claimForUpload(store, segment, log)) {
                failedThisRun = true
                continue
            }
            store.recordAttempt(segment.id, segment.attemptCount + 1, now())

            when (actions.getValue(segment.id)) {
                is DrainAction.Skip -> {
                    confirmThenRemove(store, finisher, segment, log)?.let { reason ->
                        failedThisRun = true
                        lastFailureAt = now()
                        lastErrorReason = reason
                    }
                }
                is DrainAction.Upload -> {
                    val result = try {
                        resolveIngestOutcomes(
                            manifest,
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
                        is SegmentSyncResult.Uploaded,
                        is SegmentSyncResult.JournalRemoved -> {
                            confirmThenRemove(store, finisher, segment, log)?.let { reason ->
                                failedThisRun = true
                                lastFailureAt = now()
                                lastErrorReason = reason
                            }
                        }
                        is SegmentSyncResult.Retry -> {
                            store.advanceState(segment.id, QueueEvent.MARK_FAILED)
                            store.recordFailure(segment.id, result.status, result.error)
                            failedThisRun = true
                            lastFailureAt = now()
                            lastErrorReason = result.error + (result.status?.let { " ($it)" } ?: "")
                        }
                        is SegmentSyncResult.HardFail -> {
                            store.advanceState(segment.id, QueueEvent.MARK_FAILED)
                            store.recordFailure(segment.id, result.status, result.error)
                            failedThisRun = true
                            lastFailureAt = now()
                            lastErrorReason = "${result.error} (${result.status})"
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

/**
 * Called once the journal is known to hold [segment]. Returns null when the segment is done, or
 * the failure reason when its local directory cannot be proven to be its own copy: the segment is
 * then FAILED so the normal backoff paces the next attempt instead of every drain re-sending it.
 * A directory that is already gone leaves nothing to prove, so the segment finishes.
 */
private fun confirmThenRemove(
    store: DrainStore,
    finisher: ConfirmedCopyFinisher,
    segment: SegmentRow,
    log: (String, Throwable?) -> Unit,
): String? {
    val refusal = finisher.confirmationRefusal(segment)
    if (refusal != null && refusal != JournalCachePathRefusal.MISSING_DIRECTORY) {
        val reason = "local copy unproven: ${refusal.name.lowercase()}"
        log("confirmed copy refused ${segment.id} $refusal", null)
        store.advanceState(segment.id, QueueEvent.MARK_FAILED)
        store.recordFailure(segment.id, null, reason)
        return reason
    }
    store.advanceState(segment.id, QueueEvent.MARK_UPLOADED)
    store.recordUploaded(segment.id)
    finisher.finishUploaded(segment.id)
    return null
}
