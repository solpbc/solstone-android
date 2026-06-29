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
    fun permissionStatusRequiresStartPermissions() {
        assertTrue(granted().allRequiredGranted)
        assertFalse(granted().copy(microphoneGranted = false).allRequiredGranted)
        assertFalse(granted().copy(cameraGranted = false).allRequiredGranted)
        assertFalse(granted().copy(fineLocationGranted = false, coarseLocationGranted = false).allRequiredGranted)
        assertFalse(granted().copy(notificationsGranted = false).allRequiredGranted)
        assertTrue(granted().copy(backgroundLocationGranted = false).allRequiredGranted)
    }

    @Test
    fun permissionStatusCanMakeLocationOptional() {
        assertTrue(
            granted()
                .copy(fineLocationGranted = false, coarseLocationGranted = false, requireLocation = false)
                .allRequiredGranted,
        )
        assertFalse(
            granted()
                .copy(fineLocationGranted = false, coarseLocationGranted = false, requireLocation = true)
                .allRequiredGranted,
        )
    }

    @Test
    fun nullRehydratorIsNoOp() {
        ObserverForegroundService.dispatchRehydrate(null)
    }

    @Test
    fun nonNullRehydratorInvokedOnce() {
        var calls = 0

        ObserverForegroundService.dispatchRehydrate(
            ObserverForegroundService.ObserverServiceRehydrator { calls += 1 },
        )

        assertTrue(calls == 1)
    }

    @Test
    fun bootActionDoesNotStartForegroundServiceOrCapture() {
        val action = observerBootAction()

        assertTrue(action.postNotification)
        assertFalse(action.startForegroundService)
        assertFalse(action.startCapture)
    }

    private fun granted(): PermissionStatus =
        PermissionStatus(
            microphoneGranted = true,
            cameraGranted = true,
            fineLocationGranted = true,
            coarseLocationGranted = false,
            backgroundLocationGranted = true,
            notificationsGranted = true,
        )
}
