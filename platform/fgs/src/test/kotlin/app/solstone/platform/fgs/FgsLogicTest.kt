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
                enterForeground = true,
                initialNeedsAttention = true,
                dispatchRehydrate = true,
                postAttentionOn102 = false,
                stopSelf = false,
            ),
            onStartCommandPlan(hasIntent = true, hasRehydrator = true, subsetEmpty = false),
        )
        assertEquals(
            ObserverStartCommandPlan(
                enterForeground = true,
                initialNeedsAttention = true,
                dispatchRehydrate = false,
                postAttentionOn102 = false,
                stopSelf = false,
            ),
            onStartCommandPlan(hasIntent = true, hasRehydrator = false, subsetEmpty = false),
        )
        assertEquals(
            ObserverStartCommandPlan(
                enterForeground = true,
                initialNeedsAttention = true,
                dispatchRehydrate = false,
                postAttentionOn102 = true,
                stopSelf = true,
            ),
            onStartCommandPlan(hasIntent = false, hasRehydrator = false, subsetEmpty = false),
        )
        assertEquals(
            ObserverStartCommandPlan(
                enterForeground = false,
                initialNeedsAttention = true,
                dispatchRehydrate = false,
                postAttentionOn102 = true,
                stopSelf = true,
            ),
            onStartCommandPlan(hasIntent = true, hasRehydrator = true, subsetEmpty = true),
        )
        assertEquals(
            ObserverStartCommandPlan(
                enterForeground = false,
                initialNeedsAttention = true,
                dispatchRehydrate = false,
                postAttentionOn102 = true,
                stopSelf = true,
            ),
            onStartCommandPlan(hasIntent = false, hasRehydrator = false, subsetEmpty = true),
        )
    }

    @Test
    fun satisfiableSubsetMatchesDeclaredAndGranted() {
        val allDeclared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION, CaptureForegroundType.CAMERA)
        assertEquals(
            allDeclared,
            satisfiableCaptureForegroundTypes(
                microphoneGranted = true,
                cameraGranted = true,
                locationGranted = true,
                declared = allDeclared,
            ),
        )
        assertEquals(
            emptySet(),
            satisfiableCaptureForegroundTypes(
                microphoneGranted = false,
                cameraGranted = false,
                locationGranted = false,
                declared = allDeclared,
            ),
        )
        assertEquals(
            setOf(CaptureForegroundType.MICROPHONE),
            satisfiableCaptureForegroundTypes(
                microphoneGranted = true,
                cameraGranted = false,
                locationGranted = false,
                declared = allDeclared,
            ),
        )
        assertEquals(
            setOf(CaptureForegroundType.LOCATION),
            satisfiableCaptureForegroundTypes(
                microphoneGranted = false,
                cameraGranted = false,
                locationGranted = true,
                declared = allDeclared,
            ),
        )
        assertEquals(
            setOf(CaptureForegroundType.CAMERA),
            satisfiableCaptureForegroundTypes(
                microphoneGranted = false,
                cameraGranted = true,
                locationGranted = false,
                declared = allDeclared,
            ),
        )
        val glassesDeclared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.CAMERA)
        assertEquals(
            setOf(CaptureForegroundType.MICROPHONE),
            satisfiableCaptureForegroundTypes(
                microphoneGranted = true,
                cameraGranted = false,
                locationGranted = true,
                declared = glassesDeclared,
            ),
        )
    }

    @Test
    fun notificationsDoNotAffectSatisfiableSubset() {
        val declared = setOf(CaptureForegroundType.MICROPHONE)
        val withNotif = satisfiableCaptureForegroundTypes(
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = false,
            declared = declared,
        )
        assertEquals(setOf(CaptureForegroundType.MICROPHONE), withNotif)
    }

    @Test
    fun captureForegroundTokensConversion() {
        assertEquals(
            setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION, CaptureForegroundType.CAMERA),
            captureForegroundTypesFromTokens(setOf("microphone", "location", "camera")),
        )
        assertEquals(
            setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.CAMERA),
            captureForegroundTypesFromTokens(setOf("microphone", "camera")),
        )
    }

    @Test
    fun typesDiagLineFormatsCorrectly() {
        val line = typesDiagLine(
            granted = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION),
            declared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.CAMERA),
            subset = setOf(CaptureForegroundType.MICROPHONE),
        )
        assertEquals("fgs phase=types granted=microphone,location declared=microphone,camera subset=microphone", line)
    }

    @Test
    fun handledStartExceptionNamesAndWidgetRefusalReasons() {
        val handled = handledForegroundStartExceptionNames()
        assertTrue("SecurityException" in handled)
        assertTrue("IllegalArgumentException" in handled)
        assertTrue("ForegroundServiceStartNotAllowedException" in handled)
        assertTrue("MissingForegroundServiceTypeException" in handled)
        assertTrue("InvalidForegroundServiceTypeException" in handled)

        assertEquals(ReasonCode.PERMISSION_REVOKED, widgetRefusalReasonForStartException("SecurityException"))
        assertEquals(ReasonCode.PERMISSION_REVOKED, widgetRefusalReasonForStartException("java.lang.SecurityException"))
        assertEquals(ReasonCode.PERMISSION_REVOKED, widgetRefusalReasonForStartException("IllegalArgumentException"))
        assertEquals(ReasonCode.PERMISSION_REVOKED, widgetRefusalReasonForStartException("MissingForegroundServiceTypeException"))
        assertEquals(ReasonCode.PERMISSION_REVOKED, widgetRefusalReasonForStartException("InvalidForegroundServiceTypeException"))
        assertEquals(ReasonCode.PERMISSION_REVOKED, widgetRefusalReasonForStartException("EmptyCaptureForegroundSubset"))

        assertEquals(ReasonCode.FOREGROUND_START_NOT_ALLOWED, widgetRefusalReasonForStartException("ForegroundServiceStartNotAllowedException"))
        assertEquals(ReasonCode.FOREGROUND_START_NOT_ALLOWED, widgetRefusalReasonForStartException("android.app.ForegroundServiceStartNotAllowedException"))
        assertEquals(ReasonCode.FOREGROUND_START_NOT_ALLOWED, widgetRefusalReasonForStartException("IllegalStateException"))
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
            locationGranted = true,
            notificationsGranted = true,
        )
}
