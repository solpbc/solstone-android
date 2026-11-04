// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.observer.harness.PresentedConnection.CONNECTED
import app.solstone.observer.harness.PresentedConnection.CONNECTING
import app.solstone.observer.harness.PresentedConnection.OFFLINE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scripted traces on a virtual clock. Each step is one evaluation, as a status read makes one. */
class ConnectionPresenterTest {
    private val timing = ConnectionTiming()

    private class Trace(startMs: Long = 0L, foreground: Boolean = true) {
        var now = startMs
        val presenter = ConnectionPresenter(startMs)
        val shown = mutableListOf<Pair<Long, PresentedConnection>>()

        init {
            if (foreground) presenter.foreground(true, now)
        }

        fun at(ms: Long): Trace = apply { now = ms }
        fun heard() = apply { presenter.heard(now) }
        fun unheard() = apply { presenter.unheard(now) }
        fun network(present: Boolean) = apply { presenter.network(present, now) }
        fun show(pending: Int = 0): PresentedConnection =
            presenter.present(now, pending).connection.also { shown += now to it }

        /** Evaluates every [stepMs] from now through [untilMs], as status reads would. */
        fun showEvery(stepMs: Long, untilMs: Long) {
            while (now <= untilMs) {
                show()
                now += stepMs
            }
            now = untilMs
        }
    }

    @Test
    fun aHardDropShowsWithinOneEvaluation() {
        val t = Trace().heard()
        assertEquals(CONNECTED, t.show())
        t.at(2_000).network(false)
        assertEquals(OFFLINE, t.show(), "no network at all reads offline at once, lease or not")
    }

    @Test
    fun connectedIsNeverShownWithoutSomethingHeardWithinTheLease() {
        val t = Trace()
        // A check answers every 15 to 20 s for a minute, then the journal goes quiet.
        var last = 0L
        for (heardAt in listOf(0L, 18_000L, 36_000L, 55_000L)) {
            t.at(heardAt).heard()
            last = heardAt
            t.showEvery(500, heardAt + 15_000)
        }
        t.showEvery(500, last + 90_000)
        val heard = listOf(0L, 18_000L, 36_000L, 55_000L)
        t.shown.filter { it.second == CONNECTED }.forEach { (at, _) ->
            assertTrue(heard.any { at - it in 0 until timing.foregroundLeaseMs }, "connected at $at with nothing heard in the lease")
        }
        assertEquals(CONNECTING, t.shown.first { it.first == last + timing.foregroundLeaseMs }.second)
    }

    @Test
    fun aDropFromConnectedIsShownForAtLeastASecond() {
        val t = Trace().heard()
        assertEquals(CONNECTED, t.show())
        t.at(30_000)
        assertEquals(CONNECTING, t.show(), "the lease ran out")
        t.at(30_200).heard()
        assertEquals(CONNECTING, t.show(), "evidence 0.2 s after the drop waits out the dwell")
        assertEquals(CONNECTING, t.at(30_999).show())
        assertEquals(CONNECTED, t.at(31_000).show())
    }

    @Test
    fun aSingleFailedCheckInsideTheLeaseChangesNothing() {
        val t = Trace().heard()
        assertEquals(CONNECTED, t.show())
        t.at(15_000).unheard()
        assertEquals(CONNECTED, t.show())
        t.at(29_999)
        assertEquals(CONNECTED, t.show())
        assertEquals(CONNECTING, t.at(30_000).show(), "the lease, not the failed check, ends connected")
    }

    @Test
    fun aSlowAnswerInTheForegroundCadenceNeverBlinks() {
        // Checks start every 15 s (a 5 s tick can add 5 s); one answers 9 s slower than the one before.
        val t = Trace()
        var start = 0L
        var duration = 200L
        repeat(12) { i ->
            val answeredAt = start + duration
            t.showEvery(500, answeredAt - 1)
            t.at(answeredAt).heard()
            start += 20_000
            duration = if (i % 2 == 0) 9_200L else 200L
        }
        t.showEvery(500, t.now + 5_000)
        val afterFirst = t.shown.dropWhile { it.second != CONNECTED }
        assertTrue(afterFirst.isNotEmpty())
        assertTrue(afterFirst.all { it.second == CONNECTED }, "a healthy but slow cycle withdrew connected")
    }

    @Test
    fun theQueueDrainingKeepsSyncingForASecondAndAHalf() {
        val p = ConnectionPresenter(0L)
        p.heard(0L)
        assertEquals(2, p.present(0L, 2).pendingCount)
        assertEquals(1, p.present(1_000L, 1).pendingCount)
        assertEquals(1, p.present(2_000L, 0).pendingCount, "drained: syncing stays")
        assertEquals(1, p.present(3_499L, 0).pendingCount)
        assertEquals(0, p.present(3_500L, 0).pendingCount)
        assertEquals(3_500L, ConnectionPresenter(0L).run {
            heard(0L); present(0L, 1); present(2_000L, 0); nextChangeAt(2_000L)
        })
    }

    @Test
    fun offlineShowsTheRealCountAtOnce() {
        val p = ConnectionPresenter(0L)
        p.heard(0L)
        assertEquals(3, p.present(0L, 3).pendingCount)
        p.network(false, 1_000L)
        assertEquals(PresentedStatus(OFFLINE, 0), p.present(1_000L, 0))
    }

    @Test
    fun aFreshLaunchNeverShowsConnectedBeforeEvidence() {
        val t = Trace(startMs = 5_000)
        t.showEvery(250, 5_000 + 29_000)
        assertTrue(t.shown.all { it.second == CONNECTING })
        t.at(36_000).heard()
        assertEquals(CONNECTED, t.show(), "the first evidence after launch shows at once")
    }

    @Test
    fun recoveryWithNoDropShownIsImmediate() {
        val t = Trace()
        assertEquals(CONNECTING, t.at(1_000).show())
        t.at(1_100).heard()
        assertEquals(CONNECTED, t.show())
    }

    @Test
    fun offlineIsATimerAfterAFailedCheckNotAGuess() {
        val t = Trace()
        assertEquals(CONNECTING, t.at(40_000).show(), "nothing tried yet is not offline")
        t.at(41_000).unheard()
        assertEquals(OFFLINE, t.show(), "30 s without the journal, and a check failed in that time")
        t.at(42_000).heard()
        assertEquals(CONNECTED, t.show())
    }

    @Test
    fun theNetworkComingBackStartsANewConnectingEpisode() {
        val t = Trace().network(false)
        assertEquals(OFFLINE, t.show())
        t.at(60_000).unheard()
        t.at(65_000).network(true)
        assertEquals(CONNECTING, t.show())
        assertEquals(CONNECTING, t.at(94_000).show(), "no check has failed since the network came back")
        t.at(96_000).unheard()
        assertEquals(OFFLINE, t.show())
    }

    @Test
    fun comingForwardHoldsTheLastStateHalfASecondThenShowsTheTruth() {
        val t = Trace(foreground = false).heard()
        assertEquals(CONNECTED, t.show())
        t.at(45_000)
        assertEquals(CONNECTED, t.show(), "the background lease still holds")
        t.presenter.foreground(true, t.now)
        assertEquals(CONNECTED, t.show(), "held while the fresh check answers")
        assertEquals(CONNECTED, t.at(45_499).show())
        assertEquals(CONNECTING, t.at(45_500).show(), "the foreground lease ran out, and the hold is over")
        assertEquals(45_500L, ConnectionPresenter(0L).run {
            heard(0L); present(0L, 0); foreground(true, 45_000L); present(45_000L, 0); nextChangeAt(45_000L)
        })
    }

    @Test
    fun comingForwardWithAnAnswerInsideTheHoldNeverBlinks() {
        val t = Trace(foreground = false).heard()
        t.at(45_000).show()
        t.presenter.foreground(true, t.now)
        t.show()
        t.at(45_300).heard()
        assertEquals(CONNECTED, t.show())
        assertEquals(CONNECTED, t.at(46_000).show())
    }

    @Test
    fun comingForwardStartsANewEpisodeSoAnOldFailureIsNotOffline() {
        val t = Trace(foreground = false).heard()
        t.show()
        t.at(60_000).unheard()
        t.at(70_000)
        assertEquals(CONNECTED, t.show(), "one failed check inside the background lease")
        t.presenter.foreground(true, t.now)
        assertEquals(CONNECTING, t.at(70_500).show(), "a new episode, not offline from the old failure")
    }

    @Test
    fun anAnswerJustBeforeComingForwardStillCounts() {
        val t = Trace(foreground = false)
        assertEquals(CONNECTING, t.at(1_000).show())
        t.at(2_000).heard()
        t.presenter.foreground(true, t.at(2_050).now)
        assertEquals(CONNECTED, t.show(), "no read had applied the answer yet")
    }

    @Test
    fun aNewPairingForgetsWhatWasHeard() {
        val t = Trace().heard()
        assertEquals(CONNECTED, t.show())
        t.presenter.reset(t.at(1_000).now)
        assertEquals(CONNECTING, t.show())
    }

    @Test
    fun theBackgroundLeaseFollowsTheBackgroundCadence() {
        val t = Trace(foreground = false).heard()
        assertEquals(CONNECTED, t.at(timing.backgroundLeaseMs - 1).show())
        assertEquals(CONNECTING, t.at(timing.backgroundLeaseMs).show())
    }
}
