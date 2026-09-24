// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.queue

import app.solstone.core.model.QueueState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueueStoreFakeTest {
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
            states = mutableMapOf(
                "segment" to QueueState.SEALED,
                "uploaded" to QueueState.UPLOADED,
                "evicted" to QueueState.EVICTED,
                "failed" to QueueState.FAILED,
            ),
            files = mutableListOf(),
        )

        assertEquals(QueueState.UPLOADING, store.advance("segment", QueueEvent.START_UPLOAD))
        assertEquals(QueueState.EVICTED, store.advance("uploaded", QueueEvent.FINISH))
        assertEquals(QueueState.EVICTED, store.advance("evicted", QueueEvent.FINISH))
        assertFailsWith<IllegalStateException> {
            store.advance("segment", QueueEvent.SEAL)
        }
        assertFailsWith<IllegalStateException> {
            store.advance("failed", QueueEvent.FINISH)
        }
    }
}

private data class FakeFileRow(val segmentId: String, val sourceId: String)

private class FakeQueueStore(
    val states: MutableMap<String, QueueState>,
    val files: MutableList<FakeFileRow>,
) : QueueStore {
    override fun advance(segmentId: String, event: QueueEvent): QueueState {
        val next = transition(states.getValue(segmentId), event)
        states[segmentId] = next
        return next
    }

    override fun deleteSource(sourceId: String): SourceDeleteResult {
        val before = files.size
        files.removeAll { it.sourceId == sourceId }
        return SourceDeleteResult(sourceId, before - files.size)
    }
}
