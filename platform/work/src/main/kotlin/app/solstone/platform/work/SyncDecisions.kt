// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.observer.IngestDescriptors
import app.solstone.core.observer.IngestFileDescriptor
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.pl.parseJson
import app.solstone.core.queue.RetryDecision
import app.solstone.core.queue.classify
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60L * MINUTE_MS
private const val RETRY_BACKOFF_BASE_MS = 15L * MINUTE_MS
private const val RETRY_BACKOFF_CAP_MS = 4L * HOUR_MS
private const val HARD_FAIL_BACKOFF_BASE_MS = 2L * HOUR_MS
private const val HARD_FAIL_BACKOFF_CAP_MS = 6L * HOUR_MS

fun isRetryDue(
    state: QueueState,
    attemptCount: Int,
    lastAttemptAt: Long?,
    lastStatusCode: Int?,
    lastError: String? = null,
    now: Long,
): Boolean {
    if (lastError == "removed_in_journal") return false
    return when (state) {
        QueueState.SEALED,
        QueueState.UPLOADING -> true
        QueueState.FAILED -> {
            val decision = classify(lastStatusCode, ioError = lastStatusCode == null)
            now - (lastAttemptAt ?: 0L) >= retryBackoffMs(attemptCount, decision)
        }
        else -> false
    }
}

fun retryBackoffMs(attemptCount: Int, decision: RetryDecision): Long {
    val exponent = (attemptCount - 1).coerceAtLeast(0)
    return when (decision) {
        RetryDecision.RETRY -> (RETRY_BACKOFF_BASE_MS shl exponent.coerceAtMost(4)).coerceAtMost(RETRY_BACKOFF_CAP_MS)
        RetryDecision.HARD_FAIL -> (HARD_FAIL_BACKOFF_BASE_MS shl exponent.coerceAtMost(2)).coerceAtMost(HARD_FAIL_BACKOFF_CAP_MS)
        RetryDecision.STOP_AUTH -> Long.MAX_VALUE
    }
}

fun selectDrainSegments(segments: List<SegmentRow>, now: Long): List<SegmentRow> =
    segments.filter {
        it.stream == MAIN_STREAM &&
            isRetryDue(it.state, it.attemptCount, it.lastAttemptAt, it.lastStatusCode, it.lastError, now)
    }

fun reconstructManifest(
    segment: SegmentRow,
    files: List<SegmentFileRow>,
): BundleManifest =
    BundleManifest(
        key = SegmentKey(day = segment.day, segment = segment.segment),
        files = files.map { file ->
            BundleFile(
                sourceId = file.sourceId,
                name = file.name,
                sha256 = file.sha256,
                byteSize = file.byteSize,
                mediaType = file.mediaType,
                captureStartEpochMs = file.captureStartEpochMs,
                captureEndEpochMs = file.captureEndEpochMs,
            )
        },
        gaps = emptyList(),
    )

enum class ReachabilityVerdict { DRAIN, RESCHEDULE, SKIP }

fun decideReachability(
    paired: Boolean,
    reachable: Boolean,
): ReachabilityVerdict =
    when {
        !paired -> ReachabilityVerdict.SKIP
        !reachable -> ReachabilityVerdict.RESCHEDULE
        else -> ReachabilityVerdict.DRAIN
    }

sealed interface SegmentSyncResult {
    data object Uploaded : SegmentSyncResult
    data class Retry(val status: Int?, val error: String = "retry") : SegmentSyncResult
    data class HardFail(val status: Int, val error: String = "hard failure") : SegmentSyncResult
    data class AuthHalt(val status: Int) : SegmentSyncResult
    data class JournalRemoved(val status: Int) : SegmentSyncResult
}

fun receiptError(files: List<BundleFile>, descriptors: IngestDescriptors): String? =
    when (descriptors) {
        is IngestDescriptors.Absent -> "custody_missing"
        is IngestDescriptors.NotAList -> "custody_mismatch"
        is IngestDescriptors.Listed -> {
            if (descriptors.items.any { it.disposition == "received_not_written" }) {
                "custody_not_written"
            } else if (
                descriptors.items.isEmpty() ||
                descriptors.items.any { it.submitted == null } ||
                descriptors.items.map { it.submitted }.distinct().size != descriptors.items.size ||
                files.map { it.name }.distinct().size != files.size ||
                descriptors.items.map { it.submitted }.toSet() != files.map { it.name }.toSet()
            ) {
                "custody_mismatch"
            } else {
                val fileByName = files.associateBy { it.name }
                val hasMismatch = descriptors.items.any { descriptor ->
                    val file = fileByName.getValue(descriptor.submitted!!)
                    descriptor.size != file.byteSize ||
                        descriptor.sha256 != file.sha256 ||
                        (descriptor.disposition != "written" && descriptor.disposition != "already_held")
                }
                if (hasMismatch) "custody_mismatch" else null
            }
        }
    }

fun resolveIngestOutcomes(manifest: BundleManifest, outcomes: List<IngestOutcome>): SegmentSyncResult {
    val sourceGroups = manifest.files.groupBy(BundleFile::sourceId).values.toList()
    val results = mutableListOf<SegmentSyncResult>()
    for (i in sourceGroups.indices) {
        if (i < outcomes.size) {
            val single = resolveSingleOutcome(sourceGroups[i], outcomes[i])
            results += single
            if (single is SegmentSyncResult.AuthHalt) {
                break
            }
        } else {
            results += SegmentSyncResult.Retry(null, "retry")
        }
    }
    if (results.all { it is SegmentSyncResult.Uploaded || it is SegmentSyncResult.JournalRemoved }) {
        return SegmentSyncResult.Uploaded
    }
    val failures = results.filter { it !is SegmentSyncResult.Uploaded }
    return failures.minBy { it.severityRank() }
}

private fun resolveSingleOutcome(files: List<BundleFile>, outcome: IngestOutcome): SegmentSyncResult =
    when (outcome) {
        is IngestOutcome.Accepted -> receiptError(files, outcome.descriptors)?.toCustodyResult() ?: SegmentSyncResult.Uploaded
        is IngestOutcome.Collision -> receiptError(files, outcome.descriptors)?.toCustodyResult() ?: SegmentSyncResult.Uploaded
        is IngestOutcome.Duplicate -> receiptError(files, outcome.descriptors)?.toCustodyResult() ?: SegmentSyncResult.Uploaded
        is IngestOutcome.Failed -> SegmentSyncResult.HardFail(200, "hard failure")
        is IngestOutcome.UnknownStatus,
        is IngestOutcome.MalformedResponse -> SegmentSyncResult.Retry(null, "retry")
        is IngestOutcome.Rejected -> resolveRejected(outcome.status, outcome.body)
    }

private fun String.toCustodyResult(): SegmentSyncResult =
    when (this) {
        "custody_mismatch" -> SegmentSyncResult.Retry(429, "custody_mismatch")
        "custody_missing" -> SegmentSyncResult.Retry(408, "custody_missing")
        "custody_not_written" -> SegmentSyncResult.HardFail(422, "custody_not_written")
        else -> SegmentSyncResult.Retry(null, this)
    }

private fun resolveRejected(status: Int, body: String): SegmentSyncResult {
    if (status == 500) {
        try {
            val root = parseJson(body) as? Map<*, *>
            if (root?.get("reason_code") == "segment_removed") {
                return SegmentSyncResult.JournalRemoved(500)
            }
        } catch (_: Exception) {
            // non-JSON
        }
    }
    return when (classify(status, ioError = false)) {
        RetryDecision.STOP_AUTH -> SegmentSyncResult.AuthHalt(status)
        RetryDecision.RETRY -> SegmentSyncResult.Retry(status, "retry")
        RetryDecision.HARD_FAIL -> SegmentSyncResult.HardFail(status, "hard failure")
    }
}

private fun SegmentSyncResult.severityRank(): Int =
    when (this) {
        is SegmentSyncResult.AuthHalt -> 0
        is SegmentSyncResult.HardFail -> 1
        is SegmentSyncResult.Retry -> 2
        is SegmentSyncResult.JournalRemoved -> 3
        SegmentSyncResult.Uploaded -> 4
    }

fun resolveIoError(): SegmentSyncResult = SegmentSyncResult.Retry(null, "retry")

fun haltsDrain(result: SegmentSyncResult): Boolean = result is SegmentSyncResult.AuthHalt

fun nextSyncState(
    pendingCount: Int,
    lastSuccessAt: Long?,
    lastFailureAt: Long?,
): SyncStateRow =
    SyncStateRow(id = 0, pendingCount = pendingCount, lastSuccessAt = lastSuccessAt, lastFailureAt = lastFailureAt)

sealed interface DrainAction {
    data class Skip(val id: String) : DrainAction
    data class Upload(val id: String) : DrainAction
}

fun planDayDrain(
    verdicts: List<ReconcileVerdict>,
    segments: List<SegmentRow>,
): List<DrainAction> {
    require(verdicts.size == segments.size) { "reconcile verdict count must match segment count" }
    val segmentKeys = segments.map { SegmentKey(day = it.day, segment = it.segment) }
    if (segmentKeys.distinct().size == segmentKeys.size) {
        val verdictByKey = verdicts.associateBy { it.key }
        return segments.map { segment ->
            val verdict = verdictByKey.getValue(SegmentKey(day = segment.day, segment = segment.segment))
            segment.drainAction(verdict)
        }
    }
    return segments.zip(verdicts).map { (segment, verdict) ->
        segment.drainAction(verdict)
    }
}

private fun SegmentRow.drainAction(verdict: ReconcileVerdict): DrainAction =
    if (verdict.needsUpload == false) {
        DrainAction.Skip(id)
    } else {
        DrainAction.Upload(id)
    }
