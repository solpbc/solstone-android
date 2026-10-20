// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.queue

import app.solstone.core.model.QueueState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QueueStoreFakeTest {
    @Test
    fun advanceUsesTransitionAndRejectsIllegalEvents() {
        val store = FakeQueueStore(
            states = mutableMapOf(
                "segment" to QueueState.SEALED,
                "uploaded" to QueueState.UPLOADED,
                "evicted" to QueueState.EVICTED,
                "failed" to QueueState.FAILED,
            ),
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

private class FakeQueueStore(
    val states: MutableMap<String, QueueState>,
) : QueueStore {
    override fun advance(segmentId: String, event: QueueEvent): QueueState {
        val next = transition(states.getValue(segmentId), event)
        states[segmentId] = next
        return next
    }
}
