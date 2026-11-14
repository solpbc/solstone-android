// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationPostGateTest {
    private val id = ObserverNotification.SERVICE_NOTIFICATION_ID

    private fun refresh(gate: NotificationPostGate, content: String, nowMs: Long): Boolean {
        if (!gate.shouldPost(id, content, nowMs)) return false
        gate.posted(id, content, nowMs)
        return true
    }

    /** Refreshes every 5 s from [startMs] for a day; how many of them posted. */
    private fun dayOfRefreshes(gate: NotificationPostGate, content: String, startMs: Long): Int =
        (1..17_280).count { refresh(gate, content, startMs + it * 5_000L) }

    @Test
    fun unchangedRefreshesPostNothingSoADismissedNotificationStaysDismissed() {
        val gate = NotificationPostGate()
        assertTrue(refresh(gate, "intake\u0000on", 0L))
        // One settling post a few seconds later, in case the system dropped the first, then nothing.
        assertEquals(1, dayOfRefreshes(gate, "intake\u0000on", 0L))

        val later = 100_000_000L
        assertTrue(refresh(gate, "intake\u0000needs attention", later))
        assertEquals(1, dayOfRefreshes(gate, "intake\u0000needs attention", later))
    }

    @Test
    fun aBurstOfPostsIsSettledOnceAQuietRefreshComes() {
        val gate = NotificationPostGate()
        assertTrue(refresh(gate, "setting up", 0L))
        assertTrue(refresh(gate, "on", 100L))
        // Inside the burst the same content is not posted again.
        assertEquals(false, refresh(gate, "on", 200L))
        // The first refresh after the burst posts it once more, in case the system dropped it.
        assertTrue(refresh(gate, "on", 5_000L))
        assertEquals(false, refresh(gate, "on", 10_000L))
    }

    @Test
    fun aNewForegroundEntryPostsAgainAndEachIdKeepsItsOwnRecord() {
        val gate = NotificationPostGate()
        assertTrue(refresh(gate, "on", 0L))
        assertTrue(refresh(gate, "on", 5_000L))
        gate.forget(id)
        assertTrue(refresh(gate, "on", 10_000L))
        assertTrue(gate.shouldPost(ObserverNotification.BOOT_NOTIFICATION_ID, "on", 10_000L))
    }
}
