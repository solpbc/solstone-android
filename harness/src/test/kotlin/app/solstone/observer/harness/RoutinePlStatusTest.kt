// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoutinePlStatusTest {
    @Test
    fun inTheBackgroundAJournalCheckWaitsAMinuteAfterTheLastOneFinished() {
        var now = 0L
        var probes = 0
        val f = fixture(
            plStatusProbe = PlStatusProbe { probes += 1; now += 8_000; HarnessPlStatus.Reachable(200) },
            monotonicMs = { now },
        )

        assertTrue(f.controller.checkIfDue())
        now += ROUTINE_PROBE_MAX_AGE_MS - 1
        assertFalse(f.controller.checkIfDue(), "the 5 s tick does not open a session per tick")
        now += 1
        assertTrue(f.controller.checkIfDue())
        assertEquals(2, probes)
    }

    @Test
    fun inTheForegroundAJournalCheckMayStartFifteenSecondsAfterTheLastStarted() {
        var now = 0L
        var probes = 0
        val f = fixture(
            plStatusProbe = PlStatusProbe { probes += 1; now += 8_000; HarnessPlStatus.Reachable(200) },
            monotonicMs = { now },
        )
        f.controller.onForeground(true)

        f.controller.checkIfDue()
        now = FOREGROUND_CHECK_SPACING_MS - 1
        assertFalse(f.controller.checkIfDue())
        now = FOREGROUND_CHECK_SPACING_MS
        assertTrue(f.controller.checkIfDue(), "timed from the start, so a slow answer does not stretch the cycle")
        assertEquals(2, probes)
    }

    @Test
    fun aStatusReadNeverDialsOnceThePairingHasBeenChecked() {
        var now = 0L
        var probes = 0
        val f = fixture(
            plStatusProbe = PlStatusProbe { probes += 1; HarnessPlStatus.Reachable(200) },
            monotonicMs = { now },
        )

        f.controller.currentPlStatus()
        assertEquals(1, probes, "a pairing with no check yet is checked on first read")
        now += 10 * ROUTINE_PROBE_MAX_AGE_MS
        f.controller.currentPlStatus()
        assertEquals(1, probes)
    }

    @Test
    fun withNoNetworkAtAllNothingIsDialledAndTheConnectionReadsOffline() {
        var network = true
        var probes = 0
        val f = fixture(
            plStatusProbe = PlStatusProbe { probes += 1; HarnessPlStatus.Reachable(200) },
            monotonicMs = { 0L },
            hasNetwork = { network },
        )
        f.controller.checkIfDue()
        assertEquals(PresentedConnection.CONNECTED, f.controller.presentConnection(0).connection)

        network = false
        assertTrue(f.controller.checkIfDue(), "the network going is a change to show")
        assertFalse(f.controller.checkIfDue())
        assertEquals(1, probes)
        assertEquals(PresentedConnection.OFFLINE, f.controller.presentConnection(0).connection)
    }

    @Test
    fun aJournalThatAnswersUnwellIsHeardAndOneThatIsSilentIsNot() {
        var answer: HarnessPlStatus = HarnessPlStatus.Reachable(503)
        val f = fixture(plStatusProbe = PlStatusProbe { answer }, monotonicMs = { 0L })
        f.controller.probePlStatus()
        assertEquals(PresentedConnection.CONNECTED, f.controller.presentConnection(0).connection)

        answer = HarnessPlStatus.PairedButUnreachable("down")
        f.identityStore.save(pairedHome(instanceId = "home-2"))
        f.controller.probePlStatus()
        assertEquals(PresentedConnection.CONNECTING, f.controller.presentConnection(0).connection)
    }

    @Test
    fun anOwnersCheckAndAPairingChangeProbeFresh() {
        var probes = 0
        val f = fixture(
            plStatusProbe = PlStatusProbe { probes += 1; HarnessPlStatus.Reachable(200) },
            monotonicMs = { 0L },
        )
        f.controller.checkIfDue()

        f.controller.probePlStatus()
        assertEquals(2, probes, "an explicit check never reads the cache")

        f.identityStore.save(pairedHome(instanceId = "home-2"))
        assertIs<HarnessPlStatus.Reachable>(f.controller.currentPlStatus())
        assertEquals(3, probes, "a different pairing is never answered from the old one's check")
        assertEquals(PresentedConnection.CONNECTED, f.controller.presentConnection(0).connection)
    }

    // Falsified by a key on the journal alone: re-pairing the same journal after a 401 would keep
    // showing pair again from the old credential's answer.
    @Test
    fun aNewCredentialForTheSameJournalProbesFresh() {
        var probes = 0
        val f = fixture(
            plStatusProbe = PlStatusProbe { probes += 1; HarnessPlStatus.Reachable(401) },
            monotonicMs = { 0L },
        )
        f.controller.checkIfDue()

        f.identityStore.save(pairedHome().copy(clientCertFingerprint = "sha256:client-2"))
        f.controller.checkIfDue()

        assertEquals(2, probes)
    }
}
