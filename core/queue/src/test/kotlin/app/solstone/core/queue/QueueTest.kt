// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.queue

import app.solstone.core.model.QueueState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QueueTest {
    @Test
    fun classifyMapsRetryDecisions() {
        assertEquals(RetryDecision.STOP_AUTH, classify(401, ioError = false))
        assertEquals(RetryDecision.STOP_AUTH, classify(403, ioError = false))
        assertEquals(RetryDecision.RETRY, classify(500, ioError = false))
        assertEquals(RetryDecision.RETRY, classify(503, ioError = false))
        assertEquals(RetryDecision.RETRY, classify(408, ioError = false))
        assertEquals(RetryDecision.RETRY, classify(425, ioError = false))
        assertEquals(RetryDecision.RETRY, classify(429, ioError = false))
        assertEquals(RetryDecision.RETRY, classify(null, ioError = true))
        assertEquals(RetryDecision.HARD_FAIL, classify(404, ioError = false))
        assertEquals(RetryDecision.HARD_FAIL, classify(409, ioError = false))
        assertEquals(RetryDecision.HARD_FAIL, classify(400, ioError = false))
        assertEquals(RetryDecision.HARD_FAIL, classify(422, ioError = false))
    }

    @Test
    fun transitionAllowsOnlyLegalEdges() {
        assertEquals(QueueState.SEALED, transition(QueueState.RECORDING, QueueEvent.SEAL))
        assertEquals(QueueState.UPLOADING, transition(QueueState.SEALED, QueueEvent.START_UPLOAD))
        assertEquals(QueueState.UPLOADED, transition(QueueState.UPLOADING, QueueEvent.MARK_UPLOADED))
        assertEquals(QueueState.FAILED, transition(QueueState.UPLOADING, QueueEvent.MARK_FAILED))
        assertEquals(QueueState.UPLOADING, transition(QueueState.FAILED, QueueEvent.RETRY))
        assertEquals(QueueState.EVICTED, transition(QueueState.UPLOADED, QueueEvent.FINISH))
        assertEquals(QueueState.EVICTED, transition(QueueState.EVICTED, QueueEvent.FINISH))
        assertTrue(canTransition(QueueState.UPLOADED, QueueEvent.FINISH))
        assertTrue(canTransition(QueueState.EVICTED, QueueEvent.FINISH))
        assertFalse(canTransition(QueueState.SEALED, QueueEvent.FINISH))
        assertFalse(canTransition(QueueState.FAILED, QueueEvent.FINISH))
        assertFalse(canTransition(QueueState.UPLOADING, QueueEvent.FINISH))
        assertFalse(canTransition(QueueState.RECORDING, QueueEvent.FINISH))
        assertFalse(canTransition(QueueState.UPLOADED, QueueEvent.START_UPLOAD))
        assertFalse(canTransition(QueueState.RECORDING, QueueEvent.MARK_UPLOADED))
        assertFailsWith<IllegalStateException> {
            transition(QueueState.RECORDING, QueueEvent.MARK_UPLOADED)
        }
        assertFailsWith<IllegalStateException> {
            transition(QueueState.UPLOADED, QueueEvent.START_UPLOAD)
        }
        assertFailsWith<IllegalStateException> {
            transition(QueueState.SEALED, QueueEvent.FINISH)
        }
        assertFailsWith<IllegalStateException> {
            transition(QueueState.FAILED, QueueEvent.FINISH)
        }
        assertFailsWith<IllegalStateException> {
            transition(QueueState.UPLOADING, QueueEvent.FINISH)
        }
    }

    @Test
    fun importRetryBackoffMsUsesShortLadderAndLeavesSegmentBackoffIntact() {
        assertEquals(15_000L, importRetryBackoffMs(0))
        assertEquals(15_000L, importRetryBackoffMs(1))
        assertEquals(30_000L, importRetryBackoffMs(2))
        assertEquals(60_000L, importRetryBackoffMs(3))
        assertEquals(120_000L, importRetryBackoffMs(4))
        assertEquals(240_000L, importRetryBackoffMs(5))
        assertEquals(480_000L, importRetryBackoffMs(6))
        assertEquals(960_000L, importRetryBackoffMs(7))
        assertEquals(1_920_000L, importRetryBackoffMs(8))
        assertEquals(3_600_000L, importRetryBackoffMs(9))
        assertEquals(3_600_000L, importRetryBackoffMs(99))

        // Segment sync retains its 15m..4h schedule untouched.
        assertEquals(15 * 60_000L, retryBackoffMs(1, RetryDecision.RETRY))
        assertEquals(30 * 60_000L, retryBackoffMs(2, RetryDecision.RETRY))
        assertEquals(4 * 60 * 60_000L, retryBackoffMs(5, RetryDecision.RETRY))
    }
}
