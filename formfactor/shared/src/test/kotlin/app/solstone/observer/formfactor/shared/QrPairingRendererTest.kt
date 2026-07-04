// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.PairAttemptOutcome
import app.solstone.observer.harness.PairConnectionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrPairingRendererTest {
    /**
     * AC4 red proof: the old QR path rendered "Paired" optimistically after any non-throwing scan callback.
     */
    @Test
    fun linkedOnlyRendersPairedWhenPairAndStatusAreBothSuccessful() {
        val success = PairAttemptOutcome.Linked(result(pairStatus = 200, statusStatus = 204))
        val pairFailure = PairAttemptOutcome.Linked(result(pairStatus = 503, statusStatus = 204))
        val statusFailure = PairAttemptOutcome.Linked(result(pairStatus = 200, statusStatus = 503))

        assertEquals("Paired", pairStatusText(success))
        assertTrue(success.isSuccessfulPair())
        assertEquals("Pairing failed", pairStatusText(pairFailure))
        assertFalse(pairFailure.isSuccessfulPair())
        assertEquals("Pairing failed", pairStatusText(statusFailure))
        assertFalse(statusFailure.isSuccessfulPair())
    }

    /**
     * AC4 red proof: classified failure outcomes must render honest non-"Paired" states.
     */
    @Test
    fun nonLinkedOutcomesNeverRenderPaired() {
        val outcomes = listOf(
            PairAttemptOutcome.Retry,
            PairAttemptOutcome.NetworkUnavailable,
            PairAttemptOutcome.WindowClosed(401),
            PairAttemptOutcome.OtherFailure("IOException", null),
        )

        outcomes.forEach { outcome ->
            assertFalse(outcome.isSuccessfulPair())
            assertFalse(pairStatusText(outcome).contains("Paired"))
        }
        assertEquals("Scanning", pairStatusText(PairAttemptOutcome.Retry))
        assertEquals("Network unavailable", pairStatusText(PairAttemptOutcome.NetworkUnavailable))
        assertEquals("Pairing code expired", pairStatusText(PairAttemptOutcome.WindowClosed(401)))
        assertEquals("Pairing failed", pairStatusText(PairAttemptOutcome.OtherFailure("IOException", null)))
    }

    private fun result(pairStatus: Int, statusStatus: Int): HarnessPairProbeResult =
        HarnessPairProbeResult(
            handshakePinned = pairStatus in 200..299 && statusStatus in 200..299,
            pairStatus = pairStatus,
            statusStatus = statusStatus,
            statusBody = "",
            homeLabel = "home",
            endpointHost = "10.0.0.2",
            endpointPort = 7657,
            connectionMode = PairConnectionMode.PAIRING,
        )
}
