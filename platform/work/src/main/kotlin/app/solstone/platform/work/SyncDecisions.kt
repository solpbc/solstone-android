// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.queue.RetryDecision
import app.solstone.core.queue.classify
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow

fun selectDrainSegments(segments: List<SegmentRow>): List<SegmentRow> =
    segments.filter { it.state == QueueState.SEALED && it.stream == MAIN_STREAM }

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
    data class Uploaded(val serverKey: String?) : SegmentSyncResult
    data class Retry(val status: Int?) : SegmentSyncResult
    data class HardFail(val status: Int) : SegmentSyncResult
    data class AuthHalt(val status: Int) : SegmentSyncResult
}

fun resolveIngestOutcome(outcome: IngestOutcome): SegmentSyncResult =
    when (outcome) {
        is IngestOutcome.Accepted -> SegmentSyncResult.Uploaded(outcome.serverSegment)
        is IngestOutcome.Collision -> SegmentSyncResult.Uploaded(outcome.serverSegment)
        is IngestOutcome.Duplicate -> SegmentSyncResult.Uploaded(outcome.existingSegment)
        is IngestOutcome.Rejected -> outcome.status.toSegmentSyncResult()
    }

fun resolveIoError(): SegmentSyncResult = SegmentSyncResult.Retry(null)

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
    val verdictByKey = verdicts.associateBy { it.key }
    return segments.map { segment ->
        val key = SegmentKey(segment.day, segment.segment)
        if (verdictByKey[key]?.needsUpload == false) {
            DrainAction.Skip(segment.id)
        } else {
            DrainAction.Upload(segment.id)
        }
    }
}

private fun Int.toSegmentSyncResult(): SegmentSyncResult =
    when (classify(this, ioError = false)) {
        RetryDecision.STOP_AUTH -> SegmentSyncResult.AuthHalt(this)
        RetryDecision.RETRY -> SegmentSyncResult.Retry(this)
        RetryDecision.HARD_FAIL -> SegmentSyncResult.HardFail(this)
    }
