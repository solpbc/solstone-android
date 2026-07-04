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
        assertEquals(QueueState.EVICTED, transition(QueueState.SEALED, QueueEvent.EVICT))
        assertEquals(QueueState.EVICTED, transition(QueueState.UPLOADED, QueueEvent.EVICT))
        assertEquals(QueueState.EVICTED, transition(QueueState.FAILED, QueueEvent.EVICT))
        assertTrue(canTransition(QueueState.SEALED, QueueEvent.EVICT))
        assertFalse(canTransition(QueueState.UPLOADED, QueueEvent.START_UPLOAD))
        assertFalse(canTransition(QueueState.RECORDING, QueueEvent.EVICT))
        assertFalse(canTransition(QueueState.RECORDING, QueueEvent.MARK_UPLOADED))
        assertFailsWith<IllegalStateException> {
            transition(QueueState.RECORDING, QueueEvent.MARK_UPLOADED)
        }
        assertFailsWith<IllegalStateException> {
            transition(QueueState.UPLOADED, QueueEvent.START_UPLOAD)
        }
        assertFailsWith<IllegalStateException> {
            transition(QueueState.RECORDING, QueueEvent.EVICT)
        }
    }
}
