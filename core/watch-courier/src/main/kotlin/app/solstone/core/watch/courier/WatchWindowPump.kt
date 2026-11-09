// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import java.time.Instant
import java.time.ZoneId

data class TimestampedAudioFrame(
    val timestampEpochMs: Long,
    val data: ByteArray,
    val durationMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TimestampedAudioFrame) return false
        return timestampEpochMs == other.timestampEpochMs &&
            durationMs == other.durationMs &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = timestampEpochMs.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

data class WindowGapNotice(
    val kind: String,
    val atEpochMs: Long,
    val detail: String?,
)

data class CompletedWatchWindow(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val frames: List<TimestampedAudioFrame>,
    val gaps: List<WindowGapNotice>,
    val zoneId: String = ZoneId.systemDefault().id,
    val utcOffsetSeconds: Int = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(startEpochMs)).totalSeconds,
)

enum class WindowPumpStopReason {
    OWNER_STOP,
    SENSOR_FAILURE,
    PERMISSION_FAILURE,
    STORAGE_FULL,
    PROCESS_LOSS,
}

class WatchWindowPump(
    private val windowMs: Long = WatchCourierProtocol.WINDOW_MS,
    private val onWindowComplete: (CompletedWatchWindow) -> Unit,
) {
    var receiving: Boolean = false
        private set

    private var currentWindowStartEpochMs: Long? = null
    private var currentZoneId: ZoneId? = null
    private val currentFrames = mutableListOf<TimestampedAudioFrame>()
    private val currentGaps = mutableListOf<WindowGapNotice>()
    private var lastFrameEndEpochMs: Long? = null

    @Synchronized
    fun start(nowEpochMs: Long = 0L) {
        receiving = true
        currentWindowStartEpochMs = null
        currentZoneId = null
        currentFrames.clear()
        currentGaps.clear()
        lastFrameEndEpochMs = null
    }

    @Synchronized
    fun opensNewWindow(frameStartEpochMs: Long): Boolean {
        if (!receiving) return false
        val winStart = currentWindowStartEpochMs ?: return false
        return frameStartEpochMs >= winStart + windowMs
    }

    @Synchronized
    fun feed(frame: TimestampedAudioFrame): Boolean {
        if (!receiving) return false

        val frameStart = frame.timestampEpochMs
        if (currentWindowStartEpochMs == null) {
            currentWindowStartEpochMs = frameStart
            currentZoneId = ZoneId.systemDefault()
        }
        val winStart = currentWindowStartEpochMs!!

        // Boundary check: does this frame start at or beyond the current windowMs window?
        if (frameStart >= winStart + windowMs) {
            rotate(frameStart)
        }

        currentFrames.add(frame)
        lastFrameEndEpochMs = frameStart + frame.durationMs
        return true
    }

    @Synchronized
    fun recordGap(kind: String, atEpochMs: Long, detail: String? = null) {
        if (!receiving) return
        currentGaps.add(WindowGapNotice(kind, atEpochMs, detail))
    }

    @Synchronized
    fun stop(reason: WindowPumpStopReason) {
        if (!receiving) return
        receiving = false
        val completed = makeCompletedWindow()
        if (completed != null) {
            onWindowComplete(completed)
        }
    }

    private fun rotate(nextFrameEpochMs: Long) {
        val completed = makeCompletedWindow()
        currentWindowStartEpochMs = nextFrameEpochMs
        currentZoneId = ZoneId.systemDefault()
        if (completed != null) {
            onWindowComplete(completed)
        }
    }

    private fun makeCompletedWindow(): CompletedWatchWindow? {
        val start = currentWindowStartEpochMs ?: return null
        if (currentFrames.isEmpty() && currentGaps.isEmpty()) {
            return null
        }
        val end = lastFrameEndEpochMs ?: (start + windowMs)
        val zone = currentZoneId ?: ZoneId.systemDefault()
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(start)).totalSeconds
        val completed = CompletedWatchWindow(
            startEpochMs = start,
            endEpochMs = end,
            frames = currentFrames.toList(),
            gaps = currentGaps.toList(),
            zoneId = zone.id,
            utcOffsetSeconds = offset,
        )
        currentFrames.clear()
        currentGaps.clear()
        lastFrameEndEpochMs = null
        currentWindowStartEpochMs = null
        currentZoneId = null
        return completed
    }
}
