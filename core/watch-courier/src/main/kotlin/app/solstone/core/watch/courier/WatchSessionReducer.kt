// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

enum class WatchSessionState {
    STARTING,
    ACTIVE,
    INTERRUPTED,
    ATTENTION,
    ENDED,
}

enum class WatchSessionEndReason {
    OWNER_STOP,
    SENSOR_FAILURE,
    PERMISSION_FAILURE,
    STORAGE_FULL,
    PROCESS_LOSS,
}

data class WatchSessionFacts(
    val sessionStarted: Boolean,
    val bytesAdvanced: Boolean,
    val clientSilenced: Boolean,
    val silenceDurationMs: Long = 0L,
    val sensorFailure: Boolean = false,
    val permissionFailure: Boolean = false,
    val ownerStopped: Boolean = false,
    val storageFull: Boolean = false,
    val processLossDetected: Boolean = false,
    val freeSpaceBytes: Long = Long.MAX_VALUE,
)

data class WatchSessionReduction(
    val state: WatchSessionState,
    val endReason: WatchSessionEndReason? = null,
    val stopAvailable: Boolean = true,
)

object WatchSessionReducer {
    private const val ATTENTION_THRESHOLD_MS = 60_000L
    private const val SILENCE_THRESHOLD_MS = 10_000L

    fun reduce(
        snapshot: WatchRecorderSnapshot,
        nowEpochMs: Long = System.currentTimeMillis(),
        freeSpaceBytes: Long = Long.MAX_VALUE,
    ): WatchSessionReduction {
        val terminalReason = snapshot.terminalReason
        val hasAcceptedAudio = snapshot.startSucceeded && snapshot.lastInputTimestampEpochMs != null
        val silenceDuration = if (hasAcceptedAudio) {
            (nowEpochMs - snapshot.lastInputTimestampEpochMs!!).coerceAtLeast(0L)
        } else {
            0L
        }
        val isSilenced = snapshot.clientSilenced || (hasAcceptedAudio && silenceDuration >= SILENCE_THRESHOLD_MS)

        val facts = WatchSessionFacts(
            sessionStarted = snapshot.startSucceeded && terminalReason == null,
            bytesAdvanced = hasAcceptedAudio,
            clientSilenced = isSilenced,
            silenceDurationMs = silenceDuration,
            ownerStopped = terminalReason == WindowPumpStopReason.OWNER_STOP,
            sensorFailure = terminalReason == WindowPumpStopReason.SENSOR_FAILURE,
            permissionFailure = terminalReason == WindowPumpStopReason.PERMISSION_FAILURE,
            storageFull = terminalReason == WindowPumpStopReason.STORAGE_FULL,
            processLossDetected = terminalReason == WindowPumpStopReason.PROCESS_LOSS,
            freeSpaceBytes = freeSpaceBytes,
        )
        return reduce(facts)
    }

    fun reduce(facts: WatchSessionFacts): WatchSessionReduction {
        // Terminal end conditions first
        if (facts.ownerStopped) {
            return WatchSessionReduction(WatchSessionState.ENDED, WatchSessionEndReason.OWNER_STOP, stopAvailable = false)
        }
        if (facts.permissionFailure) {
            return WatchSessionReduction(WatchSessionState.ENDED, WatchSessionEndReason.PERMISSION_FAILURE, stopAvailable = false)
        }
        if (facts.sensorFailure) {
            return WatchSessionReduction(WatchSessionState.ENDED, WatchSessionEndReason.SENSOR_FAILURE, stopAvailable = false)
        }
        if (facts.storageFull || facts.freeSpaceBytes < WatchCourierProtocol.STORAGE_FLOOR_BYTES) {
            return WatchSessionReduction(WatchSessionState.ENDED, WatchSessionEndReason.STORAGE_FULL, stopAvailable = false)
        }
        if (facts.processLossDetected) {
            return WatchSessionReduction(WatchSessionState.ENDED, WatchSessionEndReason.PROCESS_LOSS, stopAvailable = false)
        }

        if (!facts.sessionStarted) {
            return WatchSessionReduction(WatchSessionState.ENDED, null, stopAvailable = false)
        }

        // Before any bytes advance, session is STARTING
        if (!facts.bytesAdvanced) {
            return WatchSessionReduction(WatchSessionState.STARTING, stopAvailable = true)
        }

        // When bytes have advanced, check for silence / non-progression
        if (facts.clientSilenced) {
            return if (facts.silenceDurationMs >= ATTENTION_THRESHOLD_MS) {
                WatchSessionReduction(WatchSessionState.ATTENTION, stopAvailable = true)
            } else {
                WatchSessionReduction(WatchSessionState.INTERRUPTED, stopAvailable = true)
            }
        }

        return WatchSessionReduction(WatchSessionState.ACTIVE, stopAvailable = true)
    }
}
