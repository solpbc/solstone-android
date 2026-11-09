// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchSessionReducerTest {

    @Test
    fun unstartedSessionIsEnded() {
        val facts = WatchSessionFacts(
            sessionStarted = false,
            bytesAdvanced = false,
            clientSilenced = false,
        )
        val result = WatchSessionReducer.reduce(facts)
        assertEquals(WatchSessionState.ENDED, result.state)
        assertFalse(result.stopAvailable)
    }

    @Test
    fun startingSessionBeforeBytesAdvance() {
        val facts = WatchSessionFacts(
            sessionStarted = true,
            bytesAdvanced = false,
            clientSilenced = false,
        )
        val result = WatchSessionReducer.reduce(facts)
        assertEquals(WatchSessionState.STARTING, result.state)
        assertTrue(result.stopAvailable)
    }

    @Test
    fun activeSessionWhenBytesAdvance() {
        val facts = WatchSessionFacts(
            sessionStarted = true,
            bytesAdvanced = true,
            clientSilenced = false,
        )
        val result = WatchSessionReducer.reduce(facts)
        assertEquals(WatchSessionState.ACTIVE, result.state)
        assertTrue(result.stopAvailable)
    }

    @Test
    fun interruptedSessionBelowSixtySeconds() {
        val facts = WatchSessionFacts(
            sessionStarted = true,
            bytesAdvanced = true,
            clientSilenced = true,
            silenceDurationMs = 59_999L,
        )
        val result = WatchSessionReducer.reduce(facts)
        assertEquals(WatchSessionState.INTERRUPTED, result.state)
        assertTrue(result.stopAvailable)
    }

    @Test
    fun attentionSessionAtSixtySecondsBoundary() {
        val facts = WatchSessionFacts(
            sessionStarted = true,
            bytesAdvanced = true,
            clientSilenced = true,
            silenceDurationMs = 60_000L,
        )
        val result = WatchSessionReducer.reduce(facts)
        assertEquals(WatchSessionState.ATTENTION, result.state)
        assertTrue(result.stopAvailable)
    }

    @Test
    fun terminalFailuresTransitionToEnded() {
        val base = WatchSessionFacts(
            sessionStarted = true,
            bytesAdvanced = true,
            clientSilenced = false,
        )

        assertEquals(
            WatchSessionEndReason.OWNER_STOP,
            WatchSessionReducer.reduce(base.copy(ownerStopped = true)).endReason,
        )
        assertEquals(
            WatchSessionEndReason.SENSOR_FAILURE,
            WatchSessionReducer.reduce(base.copy(sensorFailure = true)).endReason,
        )
        assertEquals(
            WatchSessionEndReason.PERMISSION_FAILURE,
            WatchSessionReducer.reduce(base.copy(permissionFailure = true)).endReason,
        )
        assertEquals(
            WatchSessionEndReason.STORAGE_FULL,
            WatchSessionReducer.reduce(base.copy(storageFull = true)).endReason,
        )
        assertEquals(
            WatchSessionEndReason.STORAGE_FULL,
            WatchSessionReducer.reduce(base.copy(freeSpaceBytes = 49L * 1024L * 1024L)).endReason,
        )
        assertEquals(
            WatchSessionEndReason.PROCESS_LOSS,
            WatchSessionReducer.reduce(base.copy(processLossDetected = true)).endReason,
        )
    }

    @Test
    fun snapshotStartWithoutAcceptedAudioIsNotActive() {
        val snapshot = WatchRecorderSnapshot(
            startSucceeded = true,
            lastInputTimestampEpochMs = null,
            clientSilenced = false,
            terminalReason = null,
        )
        val result = WatchSessionReducer.reduce(snapshot, nowEpochMs = 1000L)
        assertEquals(WatchSessionState.STARTING, result.state)
        assertTrue(result.stopAvailable)
    }

    @Test
    fun snapshotActiveAndSilenceProgression() {
        val snapshot = WatchRecorderSnapshot(
            startSucceeded = true,
            lastInputTimestampEpochMs = 100_000L,
            clientSilenced = false,
            terminalReason = null,
        )

        // Fresh audio within 10s is ACTIVE
        val activeResult = WatchSessionReducer.reduce(snapshot, nowEpochMs = 105_000L)
        assertEquals(WatchSessionState.ACTIVE, activeResult.state)

        // Silence after 10s is INTERRUPTED
        val interruptedResult = WatchSessionReducer.reduce(snapshot, nowEpochMs = 115_000L)
        assertEquals(WatchSessionState.INTERRUPTED, interruptedResult.state)

        // Silence at 60s is ATTENTION
        val attentionResult = WatchSessionReducer.reduce(snapshot, nowEpochMs = 160_000L)
        assertEquals(WatchSessionState.ATTENTION, attentionResult.state)
    }

    @Test
    fun snapshotTerminalReasonsTransitionToEnded() {
        val base = WatchRecorderSnapshot(startSucceeded = true, lastInputTimestampEpochMs = 1000L)

        assertEquals(
            WatchSessionEndReason.OWNER_STOP,
            WatchSessionReducer.reduce(base.copy(terminalReason = WindowPumpStopReason.OWNER_STOP)).endReason,
        )
        assertEquals(
            WatchSessionEndReason.SENSOR_FAILURE,
            WatchSessionReducer.reduce(base.copy(terminalReason = WindowPumpStopReason.SENSOR_FAILURE)).endReason,
        )
        assertEquals(
            WatchSessionEndReason.PERMISSION_FAILURE,
            WatchSessionReducer.reduce(base.copy(terminalReason = WindowPumpStopReason.PERMISSION_FAILURE)).endReason,
        )
        assertEquals(
            WatchSessionEndReason.STORAGE_FULL,
            WatchSessionReducer.reduce(base.copy(terminalReason = WindowPumpStopReason.STORAGE_FULL)).endReason,
        )
        assertEquals(
            WatchSessionEndReason.PROCESS_LOSS,
            WatchSessionReducer.reduce(base.copy(terminalReason = WindowPumpStopReason.PROCESS_LOSS)).endReason,
        )
    }
}
