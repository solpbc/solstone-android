// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import app.solstone.core.model.SourceState
import app.solstone.core.model.ReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun startActionOfferedOnlyWhenCaptureNotRunning() {
        assertFalse(shouldOfferStartAction(isRunning = true))
        assertTrue(shouldOfferStartAction(isRunning = false))
    }

    @Test
    fun stoppedNotificationRequiresObservedOnToOff() {
        assertTrue(shouldNotifyCaptureStopped(SourceState.ON, SourceState.OFF))
        assertFalse(shouldNotifyCaptureStopped(null, SourceState.OFF))
        assertFalse(shouldNotifyCaptureStopped(SourceState.OFF, SourceState.OFF))
        assertFalse(shouldNotifyCaptureStopped(SourceState.ON, SourceState.SETTING_UP))
        assertFalse(shouldNotifyCaptureStopped(SourceState.ON, SourceState.PAUSED))
        assertFalse(shouldNotifyCaptureStopped(SourceState.ON, SourceState.NEEDS_ATTENTION))
    }

    @Test
    fun stoppedNotificationTextIsDistinctAndSelected() {
        assertNotEquals(ObserverNotification.TEXT_ON, ObserverNotification.TEXT_OFF)
        assertNotEquals(ObserverNotification.TEXT_NEEDS_ATTENTION, ObserverNotification.TEXT_OFF)
        assertEquals(
            ObserverNotification.TEXT_OFF,
            ObserverNotification.ongoingContentText(needsAttention = false, stopped = true),
        )
        assertEquals(
            ObserverNotification.TEXT_ON,
            ObserverNotification.ongoingContentText(needsAttention = false, stopped = false),
        )
        assertEquals(
            ObserverNotification.TEXT_NEEDS_ATTENTION,
            ObserverNotification.ongoingContentText(needsAttention = true, stopped = false),
        )
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
    fun widgetStartCallbacksPreserveAcceptanceAndRefusalReasons() {
        val accepted = mutableListOf<String>()
        val refused = mutableListOf<Pair<String, ReasonCode>>()
        ObserverForegroundService.widgetStartHandler = object : ObserverForegroundService.ObserverWidgetStartHandler {
            override fun onForegroundServiceStarted(sourceId: String) {
                accepted += sourceId
            }

            override fun onForegroundServiceStartRefused(sourceId: String, reason: ReasonCode) {
                refused += sourceId to reason
            }
        }
        try {
            ObserverForegroundService.dispatchWidgetStartAccepted("audio")
            ObserverForegroundService.dispatchWidgetStartRefused("audio", ReasonCode.PERMISSION_REVOKED)
            ObserverForegroundService.dispatchWidgetStartRefused(
                "audio",
                ReasonCode.FOREGROUND_START_NOT_ALLOWED,
            )
        } finally {
            ObserverForegroundService.widgetStartHandler = null
        }

        assertEquals(listOf("audio"), accepted)
        assertEquals(
            listOf(
                "audio" to ReasonCode.PERMISSION_REVOKED,
                "audio" to ReasonCode.FOREGROUND_START_NOT_ALLOWED,
            ),
            refused,
        )
    }

    @Test
    fun widgetStartRefusalHasNoReportingChannelOtherThanTheInstalledHandler() {
        ObserverForegroundService.widgetStartHandler = null
        ObserverForegroundService.dispatchWidgetStartRefused("audio", ReasonCode.FOREGROUND_START_NOT_ALLOWED)

        val refused = mutableListOf<Pair<String, ReasonCode>>()
        ObserverForegroundService.widgetStartHandler = object : ObserverForegroundService.ObserverWidgetStartHandler {
            override fun onForegroundServiceStarted(sourceId: String) {}

            override fun onForegroundServiceStartRefused(sourceId: String, reason: ReasonCode) {
                refused += sourceId to reason
            }
        }
        try {
            ObserverForegroundService.dispatchWidgetStartRefused("audio", ReasonCode.FOREGROUND_START_NOT_ALLOWED)
        } finally {
            ObserverForegroundService.widgetStartHandler = null
        }

        assertEquals(listOf("audio" to ReasonCode.FOREGROUND_START_NOT_ALLOWED), refused)
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
