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
            awakeMs = { now },
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
            awakeMs = { now },
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
            awakeMs = { now },
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
            awakeMs = { 0L },
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
        val f = fixture(plStatusProbe = PlStatusProbe { answer }, awakeMs = { 0L })
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
            awakeMs = { 0L },
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
            awakeMs = { 0L },
        )
        f.controller.checkIfDue()

        f.identityStore.save(pairedHome().copy(clientCertFingerprint = "sha256:client-2"))
        f.controller.checkIfDue()

        assertEquals(2, probes)
    }

    /** A phone on two clocks: [sleep] moves only real time, [awake] moves both. */
    private class Phone {
        var awakeNow = 0L
        var realNow = 0L
        var answer: HarnessPlStatus = HarnessPlStatus.Reachable(200)
        var probes = 0
        var screenOn = false
        val f = fixture(
            plStatusProbe = PlStatusProbe { probes += 1; answer },
            awakeMs = { awakeNow },
            realtimeMs = { realNow },
            screenOn = { screenOn },
        )

        fun awake(ms: Long) = apply { awakeNow += ms; realNow += ms }
        fun sleep(ms: Long) = apply { realNow += ms }
        fun shown() = f.controller.presentConnection(0).connection
    }

    @Test
    fun anAnswerFromBeforeADeepSleepNoLongerReadsConnected() {
        val phone = Phone()
        phone.f.controller.checkIfDue()
        assertEquals(PresentedConnection.CONNECTED, phone.shown())

        phone.sleep(ONE_HOUR)
        assertEquals(PresentedConnection.CONNECTED, phone.shown(), "held for the half second a check gets")
        assertEquals(PresentedConnection.CONNECTING, phone.awake(500).shown())
    }

    @Test
    fun openingTheAppAfterADeepSleepChecksAtOnceAndNeverBlinks() {
        val phone = Phone()
        phone.f.controller.checkIfDue()
        assertEquals(PresentedConnection.CONNECTED, phone.shown())
        phone.awake(5_000).sleep(ONE_HOUR)

        phone.f.controller.onForeground(true)
        assertEquals(PresentedConnection.CONNECTED, phone.shown(), "held while the fresh check answers")
        assertTrue(phone.f.controller.checkIfDue(), "only 5 s awake since the last check, but an hour has passed")
        assertEquals(2, phone.probes)
        assertEquals(PresentedConnection.CONNECTED, phone.awake(600).shown())
    }

    @Test
    fun aDeepSleepAddsNoBackgroundChecks() {
        val phone = Phone()
        phone.f.controller.checkIfDue()

        // Twelve 5 s wakes, each after five minutes asleep: one minute awake in an hour.
        repeat(12) {
            phone.sleep(5 * 60_000L).awake(5_000)
            phone.f.controller.checkIfDue()
        }

        assertEquals(2, phone.probes, "one check for the minute it was awake")
    }

    @Test
    fun theScreenComingOnAfterASleepChecksOnceAndTheWidgetNeverBlinks() {
        val phone = Phone()
        phone.f.controller.checkIfDue()
        assertEquals(PresentedConnection.CONNECTED, phone.shown())
        phone.awake(5_000).sleep(ONE_HOUR)
        phone.screenOn = true

        assertTrue(phone.f.controller.checkIfDue(), "only 5 s awake since the last check, but the owner can see it")
        assertEquals(2, phone.probes)
        assertEquals(PresentedConnection.CONNECTED, phone.shown())
        assertEquals(PresentedConnection.CONNECTED, phone.awake(600).shown())

        phone.awake(5_000)
        assertFalse(phone.f.controller.checkIfDue(), "the next tick waits its minute")
        assertEquals(2, phone.probes)
    }

    @Test
    fun aScreenLeftOnAddsNoChecks() {
        val phone = Phone()
        phone.screenOn = true
        phone.f.controller.checkIfDue()

        repeat(120) {
            phone.awake(5_000)
            phone.f.controller.checkIfDue()
        }

        assertEquals(11, phone.probes, "the first check and one a minute for ten minutes")
    }

    @Test
    fun oneFailedCheckAfterAWakeReadsConnectingAndOfflineWaitsItsTurn() {
        val phone = Phone()
        phone.f.controller.checkIfDue()
        phone.awake(ROUTINE_PROBE_MAX_AGE_MS - 1).sleep(ONE_HOUR).awake(1)

        phone.answer = HarnessPlStatus.PairedButUnreachable("down")
        assertTrue(phone.f.controller.checkIfDue(), "the check the awake minute had already earned")
        assertEquals(2, phone.probes)
        assertEquals(PresentedConnection.CONNECTING, phone.shown(), "not offline from one check after an hour's sleep")

        phone.awake(WAKE_GAP_MS - 1)
        assertEquals(PresentedConnection.CONNECTING, phone.shown())
        phone.awake(1)
        assertEquals(PresentedConnection.OFFLINE, phone.shown())
    }

    @Test
    fun aJournalAnsweringAfterAWakeReadsConnectedStraightAway() {
        val phone = Phone()
        phone.f.controller.checkIfDue()
        phone.shown()
        phone.awake(ROUTINE_PROBE_MAX_AGE_MS - 1).sleep(ONE_HOUR)
        phone.shown()
        assertEquals(PresentedConnection.CONNECTING, phone.awake(500).shown())

        phone.awake(1_000)
        phone.f.controller.checkIfDue()
        assertEquals(PresentedConnection.CONNECTED, phone.shown())
    }

    private companion object {
        const val ONE_HOUR = 3_600_000L
    }
}
