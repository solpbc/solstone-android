// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FgsLogicTest {
    @Test
    fun heartbeatRequiresExistingRecentBeat() {
        assertFalse(
            HeartbeatMonitor.isFresh(
                nowNanos = 10,
                lastBeatNanos = null,
                lastStartRequestedNanos = null,
                staleAfterNanos = 5,
                startGraceNanos = 3,
            ),
        )
        assertTrue(
            HeartbeatMonitor.isFresh(
                nowNanos = 10,
                lastBeatNanos = 5,
                lastStartRequestedNanos = null,
                staleAfterNanos = 5,
                startGraceNanos = 3,
            ),
        )
        assertFalse(
            HeartbeatMonitor.isFresh(
                nowNanos = 11,
                lastBeatNanos = 5,
                lastStartRequestedNanos = null,
                staleAfterNanos = 5,
                startGraceNanos = 3,
            ),
        )
    }

    @Test
    fun startRequestOptimisticallyRefreshesHeartbeat() {
        ObserverForegroundService.markStartRequested(nowNanos = 10)

        assertTrue(ObserverForegroundService.isHeartbeatFresh(nowNanos = 12, staleAfterNanos = 15, startGraceNanos = 5))
        assertFalse(ObserverForegroundService.isHeartbeatFresh(nowNanos = 16, staleAfterNanos = 15, startGraceNanos = 5))
        assertTrue(
            HeartbeatMonitor.isFresh(
                nowNanos = 30,
                lastBeatNanos = 20,
                lastStartRequestedNanos = 10,
                staleAfterNanos = 15,
                startGraceNanos = 5,
            ),
        )
    }

    @Test
    fun startCommandPlanCoversFreshStartAndStickyRestart() {
        assertEquals(
            ObserverStartCommandPlan(
                initialNeedsAttention = true,
                dispatchRehydrate = true,
                postAttentionOn102 = false,
                stopSelf = false,
            ),
            onStartCommandPlan(hasIntent = true, hasRehydrator = true),
        )
        assertEquals(
            ObserverStartCommandPlan(
                initialNeedsAttention = true,
                dispatchRehydrate = false,
                postAttentionOn102 = false,
                stopSelf = false,
            ),
            onStartCommandPlan(hasIntent = true, hasRehydrator = false),
        )
        assertEquals(
            ObserverStartCommandPlan(
                initialNeedsAttention = true,
                dispatchRehydrate = false,
                postAttentionOn102 = true,
                stopSelf = true,
            ),
            onStartCommandPlan(hasIntent = false, hasRehydrator = false),
        )
    }

    @Test
    fun notificationAttentionFollowsState() {
        assertFalse(needsAttentionForState(SourceState.ON))
        assertTrue(needsAttentionForState(SourceState.OFF))
        assertTrue(needsAttentionForState(SourceState.NEEDS_ATTENTION))
    }

    @Test
    fun startFailureDiagLineEmbedsExceptionClass() {
        assertTrue(startFailureDiagLine("SecurityException").contains("SecurityException"))
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
    fun lifecycleDiagHookReceivesRawLine() {
        val lines = mutableListOf<String>()
        ObserverForegroundService.lifecycleDiag = { lines += it }
        try {
            ObserverForegroundService.dispatchLifecycle("fgs phase=start startId=7 flags=0")
        } finally {
            ObserverForegroundService.lifecycleDiag = null
        }

        assertTrue(lines == listOf("fgs phase=start startId=7 flags=0"))
    }

    @Test
    fun nullNotificationDecorationIsNoOp() {
        ObserverNotification.dispatchDecoration(null)
    }

    @Test
    fun notificationDecorationInvokedOnce() {
        var calls = 0

        ObserverNotification.dispatchDecoration { calls += 1 }

        assertTrue(calls == 1)
    }

    @Test
    fun bootActionDoesNotStartForegroundServiceOrCapture() {
        val action = observerBootAction(persistedDesiredOn = true)

        assertTrue(action.postNotification)
        assertFalse(action.startForegroundService)
        assertFalse(action.startCapture)
    }

    @Test
    fun bootActionIsGatedByPersistedDesiredOn() {
        assertFalse(observerBootAction(persistedDesiredOn = false).postNotification)
        assertTrue(observerBootAction(persistedDesiredOn = true).postNotification)
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
