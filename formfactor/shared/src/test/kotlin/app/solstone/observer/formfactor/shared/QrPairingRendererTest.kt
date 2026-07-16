// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.PairAttemptOutcome
import app.solstone.observer.harness.PairConnectionMode
import app.solstone.observer.harness.ConnectivityFailure
import app.solstone.observer.harness.PairRoute
import app.solstone.observer.harness.PairLinkDispatchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrPairingRendererTest {
    @Test
    fun pairLinkDispatchCopyIsFixed() {
        // These result types carry no pair-link content, so their renderer cannot expose it.
        val invalid = pairLinkDispatchText(PairLinkDispatchResult.InvalidLink)
        val busy = pairLinkDispatchText(PairLinkDispatchResult.Busy)

        assertEquals("Invalid pair link", invalid)
        assertEquals("Pairing already in progress. Try again.", busy)
        assertEquals(null, pairLinkDispatchText(PairLinkDispatchResult.NoLink))
        assertEquals(
            "Pairing failed",
            pairLinkDispatchText(
                PairLinkDispatchResult.Attempted(PairAttemptOutcome.OtherFailure("IOException", null)),
            ),
        )
    }

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
            networkFailure(ConnectivityFailure.DEVICE_OFFLINE),
            PairAttemptOutcome.WindowClosed(401),
            PairAttemptOutcome.OtherFailure("IOException", null),
        )

        outcomes.forEach { outcome ->
            assertFalse(outcome.isSuccessfulPair())
            assertFalse(pairStatusText(outcome).contains("Paired"))
        }
        assertEquals("Scanning", pairStatusText(PairAttemptOutcome.Retry))
        assertEquals("No network connection", pairStatusText(networkFailure(ConnectivityFailure.DEVICE_OFFLINE)))
        assertEquals(
            "Couldn't find journal.example. Check the address.",
            pairStatusText(networkFailure(ConnectivityFailure.NAME_RESOLUTION)),
        )
        assertEquals(
            "Your journal at journal.example:7657 didn't answer. " +
                "Check it's running and that port 7657 isn't blocked by a firewall.",
            pairStatusText(networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER)),
        )
        assertEquals(
            "Your journal at journal.example:443 didn't answer.",
            pairStatusText(networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, PairRoute.RELAY, 443)),
        )
        assertEquals("Pairing code expired", pairStatusText(PairAttemptOutcome.WindowClosed(401)))
        assertEquals("Pairing failed", pairStatusText(PairAttemptOutcome.OtherFailure("IOException", null)))
    }

    private fun networkFailure(
        failure: ConnectivityFailure,
        route: PairRoute = PairRoute.DIRECT,
        port: Int = 7657,
    ): PairAttemptOutcome = PairAttemptOutcome.NetworkUnavailable(failure, "journal.example", port, route)

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
