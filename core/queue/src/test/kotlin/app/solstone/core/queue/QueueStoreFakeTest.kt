// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.queue

import app.solstone.core.model.QueueState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueueStoreFakeTest {
    @Test
    fun applyEvictionsDeletesOnlyReturnedSegmentIds() {
        val store = FakeQueueStore(
            states = mutableMapOf(
                "uploaded" to QueueState.UPLOADED,
                "failed" to QueueState.FAILED,
                "sealed" to QueueState.SEALED,
            ),
            files = mutableListOf(
                FakeFileRow("uploaded", "A"),
                FakeFileRow("failed", "A"),
                FakeFileRow("sealed", "A"),
            ),
        )

        val result = store.applyEvictions(
            EvictionResult(
                evictions = listOf(
                    Eviction("uploaded", QueueState.UPLOADED, byteSize = 10),
                    Eviction("failed", QueueState.FAILED, byteSize = 10),
                ),
                events = listOf(
                    EvictionEvent("eviction", 1, "uploaded", "previousState=UPLOADED,byteSize=10"),
                    EvictionEvent("eviction", 1, "failed", "previousState=FAILED,byteSize=10"),
                ),
            ),
        )

        assertEquals(listOf("uploaded", "failed"), result.evictedSegmentIds)
        assertEquals(2, result.deletedFileRows)
        assertEquals(2, result.eventsInserted)
        assertEquals(QueueState.EVICTED, store.states.getValue("uploaded"))
        assertEquals(QueueState.EVICTED, store.states.getValue("failed"))
        assertEquals(QueueState.SEALED, store.states.getValue("sealed"))
        assertEquals(listOf(FakeFileRow("sealed", "A")), store.files)
    }

    @Test
    fun deleteSourceLeavesOtherSourceRowsAndSegment() {
        val store = FakeQueueStore(
            states = mutableMapOf("segment" to QueueState.SEALED),
            files = mutableListOf(
                FakeFileRow("segment", "A"),
                FakeFileRow("segment", "B"),
            ),
        )

        val result = store.deleteSource("A")

        assertEquals(SourceDeleteResult("A", deletedFileRows = 1), result)
        assertEquals(QueueState.SEALED, store.states.getValue("segment"))
        assertEquals(listOf(FakeFileRow("segment", "B")), store.files)
    }

    @Test
    fun advanceUsesTransitionAndRejectsIllegalEvents() {
        val store = FakeQueueStore(
            states = mutableMapOf("segment" to QueueState.SEALED),
            files = mutableListOf(),
        )

        assertEquals(QueueState.UPLOADING, store.advance("segment", QueueEvent.START_UPLOAD))
        assertFailsWith<IllegalStateException> {
            store.advance("segment", QueueEvent.SEAL)
        }
    }
}

private data class FakeFileRow(val segmentId: String, val sourceId: String)

private class FakeQueueStore(
    val states: MutableMap<String, QueueState>,
    val files: MutableList<FakeFileRow>,
) : QueueStore {
    private val events = mutableListOf<EvictionEvent>()

    override fun advance(segmentId: String, event: QueueEvent): QueueState {
        val next = transition(states.getValue(segmentId), event)
        states[segmentId] = next
        return next
    }

    override fun applyEvictions(result: EvictionResult): EvictionApplyResult {
        val ids = result.evictions.map { it.segmentId }
        ids.forEach { advance(it, QueueEvent.EVICT) }
        val before = files.size
        files.removeAll { it.segmentId in ids }
        events += result.events
        return EvictionApplyResult(
            evictedSegmentIds = ids,
            deletedFileRows = before - files.size,
            eventsInserted = result.events.size,
        )
    }

    override fun deleteSource(sourceId: String): SourceDeleteResult {
        val before = files.size
        files.removeAll { it.sourceId == sourceId }
        return SourceDeleteResult(sourceId, before - files.size)
    }
}
