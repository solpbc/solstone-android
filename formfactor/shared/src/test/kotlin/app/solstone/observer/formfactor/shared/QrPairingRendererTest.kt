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
            pairStatusText(PairAttemptOutcome.ExistingPairingActive),
            pairStatusText(PairAttemptOutcome.ExistingPairingUnreachable),
            pairStatusText(PairAttemptOutcome.WindowClosed(410)),
            pairStatusText(PairAttemptOutcome.WindowClosed(401)),
            pairStatusText(networkFailure(ConnectivityFailure.DEVICE_OFFLINE)),
            pairStatusText(networkFailure(ConnectivityFailure.NAME_RESOLUTION)),
            pairStatusText(networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER)),
            pairStatusText(networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, host = "8.8.8.8")),
            pairStatusText(networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, PairRoute.RELAY, 443)),
            pairStatusText(PairAttemptOutcome.NotVerified("10.0.0.2", 7657)),
            PAIR_DISPATCH_FAILED,
            CAMERA_COULD_NOT_START,
            CAMERA_IN_USE_BY_THIS_APP,
            CAMERA_OFF_FOR_SCAN,
        ).map { requireNotNull(it) }

        messages.forEach { message ->
            assertTrue(message.first().isLowerCase(), "sentence case: $message")
            assertTrue(message.trimEnd().endsWith('.'), "a fragment, not a sentence: $message")
            // ⛔ Naming the failure and stopping is the shape this file was converged out of.
            assertTrue(
                listOf("try again", "check the address", "give it a moment", "turn it on")
                    .any { message.contains(it) },
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

    @Test
    fun pinMismatchNamesTheAddressThatAnswered() {
        assertEquals(
            "something answered at 10.0.0.2:7657 but couldn't prove it's your journal. " +
                "make sure you're connected to your journal's network, directly or over your vpn, " +
                "then try again.",
            pairStatusText(PairAttemptOutcome.NotVerified("10.0.0.2", 7657)),
        )
        assertEquals(
            "something answered at [fd00::1]:7657 but couldn't prove it's your journal. " +
                "make sure you're connected to your journal's network, directly or over your vpn, " +
                "then try again.",
            pairStatusText(PairAttemptOutcome.NotVerified("fd00::1", 7657)),
        )
    }

    /**
     * 🔴 What the owner used to read here was the platform's exception: a permission-check function
     * name, a process id and a uid. The scanner now has exactly one sentence for a camera that did
     * not start, one for a camera this app is itself holding, and one for a camera the owner has
     * turned off.
     */
    @Test
    fun theScannersCameraCopyIsFixedAndCarriesNoPlatformText() {
        assertEquals(
            "the camera couldn't start. try again, and if another app is using the camera, " +
                "close it first.",
            CAMERA_COULD_NOT_START,
        )
        assertEquals(
            "the solstone app is using the camera right now. try again, or turn the camera " +
                "source off to scan.",
            CAMERA_IN_USE_BY_THIS_APP,
        )
        assertEquals(
            "the camera is off for the solstone app, so it can't scan a pairing code. turn it on " +
                "in android settings, or open your journal's pairing link on this phone instead.",
            CAMERA_OFF_FOR_SCAN,
        )
        assertEquals("open android settings", OPEN_ANDROID_SETTINGS)
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
            PairAttemptOutcome.ExistingPairingActive,
            PairAttemptOutcome.ExistingPairingUnreachable,
        )

        outcomes.forEach { outcome ->
            assertFalse(outcome.isSuccessfulPair())
            assertFalse(pairStatusText(outcome).contains("paired"))
        }
        // ⛔ Never a scanner-state word: this renders on the same sink as the scanner's own
        // status line, so `scanning` told the owner the app was scanning while it paired.
        assertEquals("the app is busy. give it a moment.", pairStatusText(PairAttemptOutcome.Retry))
        assertEquals(
            // ⚠ This renders on a PAIRING failure, so the device is not paired: rejoining a
            // network cannot sync anything, and the old wording promised it would.
            "this device isn't on a network. get back on a network and try again. what the " +
                "solstone app has taken in is on this device, and goes to your journal as soon " +
                "as it can reach it.",
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

    @Test
    fun directHostAdviceOnlyAssumesLocalNetworkForLocalShapedTargets() {
        val short = "couldn't reach your journal at %s:7657. make sure it's running, then try again."
        val localAdvice = "make sure it's running and on the same wi-fi"

        listOf(
            "8.8.8.8",
            "1.1.1.1",
            "203.1.2.3",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
        ).forEach { host ->
            val rendered = pairStatusText(
                networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, host = host),
            )
            assertEquals(short.format(host), rendered, host)
            assertFalse(rendered.contains(localAdvice), host)
        }

        listOf(
            "journal.example",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.2",
            "172.16.0.1",
            "192.168.1.2",
            "224.0.0.1",
            "001.2.3.4",
        ).forEach { host ->
            val rendered = pairStatusText(
                networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, host = host),
            )
            assertTrue(rendered.contains(localAdvice), host)
        }
    }

    /**
     * The same rule, for IPv6 literals — documentation ranges (v4 TEST-NET and v6 2001:db8) are public;
     * mapped `::ffff:203.0.113.1` is public because the v4 half is; mapped RFC1918 stays local.
     */
    @Test
    fun directHostAdviceTreatsPublicIpv6LikePublicIpv4() {
        val localAdvice = "make sure it's running and on the same wi-fi"

        listOf(
            "2606:4700::1111",
            "2a00:1450:4001:80e::200e",
            "2001:4860:4860::8888",
            "2001:db8::1",
            "::ffff:203.0.113.1",
        ).forEach { host ->
            val rendered = pairStatusText(
                networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, host = host),
            )
            assertEquals(
                "couldn't reach your journal at [$host]:7657. make sure it's running, then try again.",
                rendered,
                host,
            )
            assertFalse(rendered.contains(localAdvice), host)
        }

        listOf(
            "::1",
            "::",
            "fe80::1",
            "fe80::1%wlan0",
            "fd00::1",
            "fc00::abcd",
            "ff02::1",
            "::ffff:192.168.1.10",
            "not:an:address",
        ).forEach { host ->
            val rendered = pairStatusText(
                networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, host = host),
            )
            assertTrue(rendered.contains(localAdvice), host)
        }
    }

    /**
     * Parity with solstone-swift `PairFailureReasonTests` (where `198.51.100.10` reads public).
     *
     * Sweeps all 57,088 well-formed (first, second) pairs for first in 1..223 and second in 0..255.
     * Retained divergences: `0.x` and `224+` stay outside this sweep as non-public; they cannot reach
     * this renderer because PairLink `isDirectDialCandidate` requires `a != 0 && a < 224`.
     */
    @Test
    fun exhaustiveSweepOfFirstTwoOctetsMatchesIndependentCidrMaskReference() {
        val short = "couldn't reach your journal at %s:7657. make sure it's running, then try again."
        val localAdvice = "make sure it's running and on the same wi-fi"
        var count = 0
        for (first in 1..223) {
            for (second in 0..255) {
                count++
                val host = "$first.$second.1.1"
                val rendered = pairStatusText(
                    networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, host = host),
                )
                val isPublic = isPublicByCidrMask(first, second, 1, 1)
                if (isPublic) {
                    assertEquals(short.format(host), rendered, host)
                    assertFalse(rendered.contains(localAdvice), host)
                } else {
                    assertTrue(rendered.contains(localAdvice), host)
                }
            }
        }
        assertEquals(57088, count)
    }

    @Test
    fun boundaryTableVerifiesAllFamilyEdgesNeighborsAndFormerExclusions() {
        val short = "couldn't reach your journal at %s:7657. make sure it's running, then try again."
        val localAdvice = "make sure it's running and on the same wi-fi"

        val publicHosts = listOf(
            // Parser edges
            "1.0.0.0",
            "223.255.255.255",
            // 10.0.0.0/8 neighbors
            "9.255.255.255",
            "11.0.0.0",
            // 100.64.0.0/10 neighbors
            "100.63.255.255",
            "100.128.0.0",
            // 127.0.0.0/8 neighbors
            "126.255.255.255",
            "128.0.0.0",
            // 169.254.0.0/16 neighbors
            "169.253.255.255",
            "169.255.0.0",
            // 172.16.0.0/12 neighbors
            "172.15.255.255",
            "172.32.0.0",
            // 192.168.0.0/16 neighbors
            "192.167.255.255",
            "192.169.0.0",
            // Former exclusions now public
            "192.0.0.1",
            "192.2.0.1",
            "192.88.99.1",
            "198.18.0.1",
            "8.8.8.8",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
        )

        publicHosts.forEach { host ->
            val rendered = pairStatusText(
                networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, host = host),
            )
            assertEquals(short.format(host), rendered, host)
            assertFalse(rendered.contains(localAdvice), host)
        }

        val localHosts = listOf(
            // Family edges (RFC 1918, CGNAT, loopback, link-local)
            "10.0.0.0",
            "10.255.255.255",
            "100.64.0.0",
            "100.127.255.255",
            "127.0.0.0",
            "127.255.255.255",
            "169.254.0.0",
            "169.254.255.255",
            "172.16.0.0",
            "172.31.255.255",
            "192.168.0.0",
            "192.168.255.255",
            // Retained divergences & parse reject
            "0.0.0.0",
            "224.0.0.1",
            "001.2.3.4",
        )

        localHosts.forEach { host ->
            val rendered = pairStatusText(
                networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, host = host),
            )
            assertTrue(rendered.contains(localAdvice), host)
        }
    }

    /**
     * ⛔ The port has to stay readable. A bare IPv6 literal rendered `at 2001:db8::1:7657.`, where
     * nothing separates the address from the port — so the owner cannot read either one.
     */
    @Test
    fun anIpv6LiteralIsBracketedSoItsPortIsReadable() {
        listOf(
            "2606:4700::1111" to "[2606:4700::1111]:7657",
            "2001:db8::1" to "[2001:db8::1]:7657",
            "192.168.1.2" to "192.168.1.2:7657",
            "journal.example" to "journal.example:7657",
        ).forEach { (host, expected) ->
            val rendered = pairStatusText(
                networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER, host = host),
            )
            assertTrue(rendered.contains("at $expected."), "$host -> $rendered")
        }
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

    @Test
    fun retryIsOnlyOfferedWhereItCanPlausiblyWork() {
        assertFalse(PairLinkDispatchResult.NoLink.isRetryableSameLink())
        assertFalse(PairLinkDispatchResult.InvalidLink.isRetryableSameLink())
        assertTrue(PairLinkDispatchResult.Busy.isRetryableSameLink())
        assertFalse(
            PairLinkDispatchResult.Attempted(
                PairAttemptOutcome.Linked(result(pairStatus = 503, statusStatus = 204)),
            ).isRetryableSameLink(),
        )
        assertFalse(
            PairLinkDispatchResult.Attempted(PairAttemptOutcome.ExistingPairingActive).isRetryableSameLink(),
        )
        assertTrue(
            PairLinkDispatchResult.Attempted(PairAttemptOutcome.ExistingPairingUnreachable).isRetryableSameLink(),
        )
        assertTrue(
            PairLinkDispatchResult.Attempted(PairAttemptOutcome.Retry).isRetryableSameLink(),
        )
        assertTrue(
            PairLinkDispatchResult.Attempted(
                networkFailure(ConnectivityFailure.HOST_DID_NOT_ANSWER),
            ).isRetryableSameLink(),
        )
        assertFalse(
            PairLinkDispatchResult.Attempted(PairAttemptOutcome.WindowClosed(410)).isRetryableSameLink(),
        )
        assertFalse(
            PairLinkDispatchResult.Attempted(
                PairAttemptOutcome.OtherFailure("IOException", null),
            ).isRetryableSameLink(),
        )
    }

    private fun networkFailure(
        failure: ConnectivityFailure,
        route: PairRoute = PairRoute.DIRECT,
        port: Int = 7657,
        host: String = "journal.example",
    ): PairAttemptOutcome = PairAttemptOutcome.NetworkUnavailable(failure, host, port, route)

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

    private data class Cidr(val prefix: Long, val mask: Long)

    private fun cidr(a: Int, b: Int, c: Int, d: Int, prefixLen: Int): Cidr {
        val ip = ((a.toLong() and 0xFF) shl 24) or
            ((b.toLong() and 0xFF) shl 16) or
            ((c.toLong() and 0xFF) shl 8) or
            (d.toLong() and 0xFF)
        val mask = if (prefixLen == 0) 0L else (-1L shl (32 - prefixLen)) and 0xFFFFFFFFL
        return Cidr(prefix = ip and mask, mask = mask)
    }

    private val nonPublicFamilies = listOf(
        cidr(10, 0, 0, 0, 8),
        cidr(172, 16, 0, 0, 12),
        cidr(192, 168, 0, 0, 16),
        cidr(100, 64, 0, 0, 10),
        cidr(127, 0, 0, 0, 8),
        cidr(169, 254, 0, 0, 16),
    )

    private fun isPublicByCidrMask(first: Int, second: Int, third: Int, fourth: Int): Boolean {
        val ip = ((first.toLong() and 0xFF) shl 24) or
            ((second.toLong() and 0xFF) shl 16) or
            ((third.toLong() and 0xFF) shl 8) or
            (fourth.toLong() and 0xFF)
        return nonPublicFamilies.none { (ip and it.mask) == it.prefix }
    }
}
