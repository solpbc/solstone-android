// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ScreenCaptureGateTest {
    @Test fun lockedAndDisplayOffCannotOpenAPortion() {
        var interactive = false
        var keyguard = true
        val gate = ScreenCaptureGate({ interactive && !keyguard })
        assertNull(gate.openPortion())
        interactive = true
        assertNull(gate.openPortion())
        keyguard = false
        assertTrue(gate.openPortion()!!.allowsFrame())
    }

    @Test fun aQueuedFrameNeverRegainsAdmissionAfterUnlock() {
        var allowed = true
        var now = 100L
        val gate = ScreenCaptureGate({ allowed }, { now })
        val before = gate.openPortion()!!
        assertTrue(before.allowsFrame())
        now = 200L
        allowed = false
        assertFalse(before.allowsFrame())
        now = 900L
        allowed = true
        assertFalse(before.allowsFrame())
        assertEquals(200L, before.cutoffEpochMs)
        assertTrue(gate.openPortion()!!.allowsFrame())
        assertFalse(before.allowsFrame())
    }

    @Test fun delayedScreenOffInvalidatesEvenAfterAQuickUnlock() {
        val gate = ScreenCaptureGate()
        val before = gate.openPortion()!!
        gate.pause()
        assertFalse(before.allowsFrame())
        val after = gate.openPortion()!!
        assertTrue(after.allowsFrame())
        gate.stop()
        assertFalse(after.allowsFrame())
    }
}
