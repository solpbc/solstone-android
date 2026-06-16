// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.queue

import app.solstone.core.model.QueueState

data class QueueSegmentDescriptor(
    val id: String,
    val state: QueueState,
    val byteSize: Long,
    val sealedAtEpochMs: Long,
)

data class EvictionBudget(
    val maxBytes: Long,
    val minFreeBytes: Long? = null,
    val currentFreeBytes: Long? = null,
)

data class EvictionInput(
    val segments: List<QueueSegmentDescriptor>,
    val budget: EvictionBudget,
    val emergency: Boolean,
    val decidedAtEpochMs: Long,
)

data class Eviction(
    val segmentId: String,
    val previousState: QueueState,
    val byteSize: Long,
)

data class EvictionEvent(
    val kind: String,
    val atEpochMs: Long,
    val segmentId: String,
    val detail: String?,
)

data class EvictionResult(
    val evictions: List<Eviction>,
    val events: List<EvictionEvent>,
)

data class EvictionApplyResult(
    val evictedSegmentIds: List<String>,
    val deletedFileRows: Int,
    val eventsInserted: Int,
)

data class SourceDeleteResult(
    val sourceId: String,
    val deletedFileRows: Int,
)

interface QueueStore {
    fun advance(segmentId: String, event: QueueEvent): QueueState
    fun applyEvictions(result: EvictionResult): EvictionApplyResult
    fun deleteSource(sourceId: String): SourceDeleteResult
}

fun evictionPolicy(input: EvictionInput): EvictionResult {
    val totalBytes = input.segments.sumOf { it.byteSize }
    if (!isOverBudget(totalBytes, input.budget)) {
        return EvictionResult(emptyList(), emptyList())
    }

    val uploaded = input.segments
        .filter { it.state == QueueState.UPLOADED }
        .sortedWith(evictionCandidateComparator)
    val candidates = if (input.emergency) {
        // Policy only emits states that transition(state, EVICT) accepts; active recording/uploading segments are never evicted.
        uploaded + input.segments
            .filter { it.state == QueueState.SEALED || it.state == QueueState.FAILED }
            .sortedWith(evictionCandidateComparator)
    } else {
        uploaded
    }

    var remainingBytes = totalBytes
    var remainingFreeBytes = input.budget.currentFreeBytes
    val evictions = mutableListOf<Eviction>()
    val events = mutableListOf<EvictionEvent>()

    for (candidate in candidates) {
        if (!isOverBudget(remainingBytes, input.budget.copy(currentFreeBytes = remainingFreeBytes))) break
        val eviction = Eviction(
            segmentId = candidate.id,
            previousState = candidate.state,
            byteSize = candidate.byteSize,
        )
        evictions += eviction
        events += EvictionEvent(
            kind = "eviction",
            atEpochMs = input.decidedAtEpochMs,
            segmentId = candidate.id,
            detail = "previousState=${candidate.state.name},byteSize=${candidate.byteSize}",
        )
        remainingBytes -= candidate.byteSize
        remainingFreeBytes = remainingFreeBytes?.plus(candidate.byteSize)
    }

    return EvictionResult(evictions, events)
}

private val evictionCandidateComparator = compareBy<QueueSegmentDescriptor> { it.sealedAtEpochMs }
    .thenBy { it.id }

private fun isOverBudget(totalBytes: Long, budget: EvictionBudget): Boolean {
    val overMaxBytes = totalBytes > budget.maxBytes
    val belowMinFree = budget.minFreeBytes != null &&
        budget.currentFreeBytes != null &&
        budget.currentFreeBytes < budget.minFreeBytes
    return overMaxBytes || belowMinFree
}
