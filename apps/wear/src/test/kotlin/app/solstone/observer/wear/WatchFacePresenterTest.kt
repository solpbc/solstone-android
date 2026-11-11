// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import app.solstone.core.watch.courier.*
import kotlin.test.*

class WatchFacePresenterTest {
    private val now = 1_800_000_000_000L
    private fun facts(
        reduction: WatchSessionReduction = WatchSessionReduction(WatchSessionState.ENDED),
        engaged: Boolean = false,
        savedCount: Int? = 0,
        notificationsOff: Boolean = true,
        notificationsAsked: Boolean = false,
        peerContactAt: Long? = now - 30_000L,
        peerVersion: Int? = WatchCourierProtocol.PROTOCOL_VERSION,
        elapsedMs: Long? = null,
    ) = WatchFaceFacts(reduction, engaged, elapsedMs, savedCount, false, notificationsOff, notificationsAsked,
        locationOff = false, locationAsked = false, peerVersion = peerVersion, peerContactAt = peerContactAt, nowMs = now)
    private fun present(f: WatchFaceFacts) = WatchFacePresenter.present(f) { "clock" }

    @Test fun aFreshInstallShowsNoAttentionBeforeTheOwnerHasBeenAskedAnything() {
        val m = present(facts(peerContactAt = null, peerVersion = null))
        assertNull(m.pill)
        assertEquals(WatchControlStyle.START, m.controlStyle)
        assertTrue(m.notes.none { it.contains("notifications") })
    }

    @Test fun declinedNotificationsAreSaidBelowTheFoldNotOnTheFirstScreen() {
        val m = present(facts(notificationsAsked = true))
        assertNull(m.pill)
        assertTrue(m.notes.size == 2)
    }

    @Test fun audioTroubleAndRealFailuresRaiseThePill() {
        assertNotNull(present(facts(WatchSessionReduction(WatchSessionState.ATTENTION), engaged = true, elapsedMs = 90_000L)).pill)
        val failed = present(facts(WatchSessionReduction(WatchSessionState.ENDED, WatchSessionEndReason.SENSOR_FAILURE)))
        assertNotNull(failed.pill)
        assertEquals(WatchControlStyle.DETAILS, failed.controlStyle)
        assertNotNull(failed.nextStep)
        assertNull(present(facts(WatchSessionReduction(WatchSessionState.ENDED, WatchSessionEndReason.OWNER_STOP))).pill)
    }

    @Test fun aRunningSessionLeadsWithItsElapsedTimeAndOffersStop() {
        val m = present(facts(WatchSessionReduction(WatchSessionState.ACTIVE), engaged = true, elapsedMs = 125_000L))
        assertEquals(WatchControlStyle.STOP, m.controlStyle)
        assertEquals("2m", m.header)
        assertNotNull(m.headerSub)
        assertNull(m.pill)
    }

    @Test fun aRecentReplyIsNotAnAlarmAndALongSilenceWithSavedAudioNamesTheNextStep() {
        assertNull(present(facts(savedCount = 3, peerContactAt = now - 10 * 60_000L)).nextStep)
        assertNotNull(present(facts(savedCount = 3, peerContactAt = now - 45 * 60_000L)).nextStep)
        assertNull(present(facts(savedCount = 0, peerContactAt = now - 45 * 60_000L)).nextStep)
        assertNotNull(present(facts(peerContactAt = null)).nextStep, "a phone never heard from names how to reach it")
    }

    @Test fun anOutOfDatePhoneAsksForItsUpdateOnTheFirstScreen() {
        assertNotNull(present(facts(peerVersion = WatchCourierProtocol.PROTOCOL_VERSION - 1)).pill)
    }

    @Test fun theRelayNoticeIsAlwaysTheLastLine() {
        listOf(facts(), facts(notificationsAsked = true), facts(peerContactAt = null)).forEach {
            assertSame(WatchCourierStrings.RELAY_NOTICE_SHORT, present(it).notes.last())
        }
    }

    @Test fun elapsedFollowsTheAppleWatchForm() {
        assertEquals("59s", WatchFacePresenter.elapsedDisplay(59))
        assertEquals("1h 5m", WatchFacePresenter.elapsedDisplay(3_900))
        assertEquals("1 hour 5 minutes", WatchFacePresenter.elapsedSpoken(3_900))
    }
}
