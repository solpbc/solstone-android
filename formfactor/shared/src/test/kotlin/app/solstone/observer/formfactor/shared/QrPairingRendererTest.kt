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

        assertEquals(
            "that pairing link isn't one the solstone app can read. show a new pairing code on " +
                "your journal and try again.",
            invalid,
        )
        assertEquals("already pairing. give it a moment.", busy)
        assertEquals(null, pairLinkDispatchText(PairLinkDispatchResult.NoLink))
        assertEquals(
            "pairing didn't go through. show a new pairing code on your journal and try again.",
            pairLinkDispatchText(
                PairLinkDispatchResult.Attempted(PairAttemptOutcome.OtherFailure("IOException", null)),
            ),
        )
    }

    /**
     * 🔴 The register, pinned as a property rather than string by string.
     *
     * ⚠ `Pairing failed` · `No network connection` · `Pairing code expired` all passed a test that
     * asserted them exactly, which is how a retired voice survives a green gate: the assertion
     * encodes the defect. Asserting the SHAPE — lowercase opening, a sentence, a next step — is
     * what makes the next fragment fail.
     */
    @Test
    fun everyPairingMessageIsOwnerVoiceWithANextStep() {
        val messages = listOf(
            pairLinkDispatchText(PairLinkDispatchResult.InvalidLink),
            pairLinkDispatchText(PairLinkDispatchResult.Busy),
            pairStatusText(PairAttemptOutcome.OtherFailure("IOException", null)),
            pairStatusText(PairAttemptOutcome.WindowClosed(410)),
            pairStatusText(PairAttemptOutcome.WindowClosed(401)),
            pairStatusText(networkFailure(ConnectivityFailure.DEVICE_OFFLINE)),
            pairStatusText(networkFailure(ConnectivityFailure.NAME_RESOLUTION)),
            pairStatusText(networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER)),
            pairStatusText(networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, PairRoute.RELAY, 443)),
            PAIR_DISPATCH_FAILED,
        ).map { requireNotNull(it) }

        messages.forEach { message ->
            assertTrue(message.first().isLowerCase(), "sentence case: $message")
            assertTrue(message.trimEnd().endsWith('.'), "a fragment, not a sentence: $message")
            // ⛔ Naming the failure and stopping is the shape this file was converged out of.
            assertTrue(
                listOf("try again", "check the address", "give it a moment").any { message.contains(it) },
                "no next step: $message",
            )
            assertFalse(message.contains("Pairing"), "retired register: $message")
            assertFalse(message.contains("sol "), "deleted product name: $message")
        }
        // ✅ Positive controls: each matcher finds what it looks for when it is present, so the
        // clean sweep above is a measurement rather than three dead assertions.
        assertFalse("Pairing failed".first().isLowerCase())
        assertFalse("No network connection".trimEnd().endsWith('.'))
        assertTrue("Pairing code expired".contains("Pairing"))
        assertFalse(
            listOf("try again", "check the address", "give it a moment")
                .any { "Pairing failed".contains(it) },
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

        assertEquals("paired", pairStatusText(success))
        assertTrue(success.isSuccessfulPair())
        assertEquals(
            "pairing didn't go through. show a new pairing code on your journal and try again.",
            pairStatusText(pairFailure),
        )
        assertFalse(pairFailure.isSuccessfulPair())
        assertEquals(
            "pairing didn't go through. show a new pairing code on your journal and try again.",
            pairStatusText(statusFailure),
        )
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
            assertFalse(pairStatusText(outcome).contains("paired"))
        }
        assertEquals("scanning", pairStatusText(PairAttemptOutcome.Retry))
        assertEquals(
            "this device isn't on a network. pairing needs to reach your journal directly, so " +
                "join the same wi-fi as your journal and try again. everything the solstone app " +
                "has taken in is on this device and syncs once you reconnect.",
            pairStatusText(networkFailure(ConnectivityFailure.DEVICE_OFFLINE)),
        )
        assertEquals(
            "couldn't find your journal at journal.example. check the address, then try again " +
                "with a new pairing code.",
            pairStatusText(networkFailure(ConnectivityFailure.NAME_RESOLUTION)),
        )
        assertEquals(
            "couldn't reach your journal at journal.example:7657. make sure it's running and on " +
                "the same wi-fi, then try again. some networks block devices from connecting " +
                "directly. you can also switch your journal to private network to pair from " +
                "anywhere.",
            pairStatusText(networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER)),
        )
        assertEquals(
            "couldn't reach your journal at journal.example:443. make sure it's running, then " +
                "try again.",
            pairStatusText(networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, PairRoute.RELAY, 443)),
        )
    }

    /**
     * ⚠ This test asserted the OPPOSITE: that 410 and every other status render different words.
     * They mean the same thing to an owner — the code is no longer good — and both need the same
     * next step, so the split told them there was a difference and declined to say what it was.
     */
    @Test
    fun everyClosedWindowStatusRendersOneMessage() {
        val expected = "the pairing window closed. show a new pairing code on your journal, then try again."
        listOf(401, 410, 499, 200).forEach { status ->
            assertEquals(expected, pairStatusText(PairAttemptOutcome.WindowClosed(status)), "status $status")
        }
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
