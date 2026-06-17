// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FgsLogicTest {
    @Test
    fun heartbeatRequiresExistingRecentBeat() {
        assertFalse(HeartbeatMonitor.isFresh(nowNanos = 10, lastBeatNanos = null, staleAfterNanos = 5))
        assertTrue(HeartbeatMonitor.isFresh(nowNanos = 10, lastBeatNanos = 5, staleAfterNanos = 5))
        assertFalse(HeartbeatMonitor.isFresh(nowNanos = 11, lastBeatNanos = 5, staleAfterNanos = 5))
    }

    @Test
    fun permissionStatusRequiresMicrophoneAndNotifications() {
        assertTrue(PermissionStatus(microphoneGranted = true, notificationsGranted = true).allRequiredGranted)
        assertFalse(PermissionStatus(microphoneGranted = false, notificationsGranted = true).allRequiredGranted)
        assertFalse(PermissionStatus(microphoneGranted = true, notificationsGranted = false).allRequiredGranted)
    }
}
