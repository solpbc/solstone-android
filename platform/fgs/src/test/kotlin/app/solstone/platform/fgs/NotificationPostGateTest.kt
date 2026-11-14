// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationPostGateTest {
    private fun refresh(gate: NotificationPostGate, content: String): Boolean {
        if (!gate.shouldPost(ObserverNotification.SERVICE_NOTIFICATION_ID, content)) return false
        gate.posted(ObserverNotification.SERVICE_NOTIFICATION_ID, content)
        return true
    }

    @Test
    fun unchangedRefreshesPostNothingSoADismissedNotificationStaysDismissed() {
        val gate = NotificationPostGate()
        assertTrue(refresh(gate, "intake\u0000on"))
        // A status refresh every 5 s for a day, with nothing changed.
        assertEquals(0, (1..17_280).count { refresh(gate, "intake\u0000on") })

        assertTrue(refresh(gate, "intake\u0000needs attention"))
        assertEquals(0, (1..10).count { refresh(gate, "intake\u0000needs attention") })

        // A new foreground entry posts its own notification, so the next refresh posts again.
        gate.forget(ObserverNotification.SERVICE_NOTIFICATION_ID)
        assertTrue(refresh(gate, "intake\u0000needs attention"))
        // Each notification id keeps its own record.
        assertTrue(gate.shouldPost(ObserverNotification.BOOT_NOTIFICATION_ID, "intake\u0000needs attention"))
    }
}
