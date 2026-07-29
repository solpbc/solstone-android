// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class G3ProgressOrderTest {
    @Test
    fun productionObservationsAdvanceOnlyInContractOrder() {
        val order = G3ProgressOrder(expectedBodyBytes = 100)

        assertEquals(1, order.advance(G3ProgressState.PARTIAL_RESPONSE_CONSUMED, 20).sequence)
        assertEquals(2, order.advance(G3ProgressState.INTERRUPTED_REQUEST_FAILED, 20).sequence)
        assertEquals(3, order.advance(G3ProgressState.DEGRADED_STATUS_RECORDED, 20).sequence)
        assertEquals(4, order.advance(G3ProgressState.NETWORK_RESTORE_OBSERVED, 20).sequence)
        assertTrue(order.isComplete())
    }

    @Test
    fun deviceWideConnectivityRemainingValidatedDoesNotGateProgress() {
        val deviceWideConnectivityValidatedThroughout = true
        val order = G3ProgressOrder(expectedBodyBytes = 100)

        G3ProgressState.entries.forEach { state ->
            assertTrue(deviceWideConnectivityValidatedThroughout)
            order.advance(state, 20)
        }
        assertTrue(order.isComplete())
    }

    @Test
    fun repeatedSkippedAndNonPartialProgressFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            G3ProgressOrder(100).advance(G3ProgressState.INTERRUPTED_REQUEST_FAILED, 20)
        }
        val repeated = G3ProgressOrder(100)
        repeated.advance(G3ProgressState.PARTIAL_RESPONSE_CONSUMED, 20)
        assertFailsWith<IllegalArgumentException> {
            repeated.advance(G3ProgressState.PARTIAL_RESPONSE_CONSUMED, 20)
        }
        assertFailsWith<IllegalArgumentException> {
            G3ProgressOrder(100).advance(G3ProgressState.PARTIAL_RESPONSE_CONSUMED, 100)
        }
    }
}
