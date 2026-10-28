// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import app.solstone.platform.audio.AudioContinuousSourceEngine

object ScreenConstants {
    const val SOURCE_ID = "screen"
    const val PAYLOAD_NAME = "screen.mp4"
    const val MEDIA_TYPE = "video/mp4"
    const val WINDOW_MS = AudioContinuousSourceEngine.WINDOW_MS
    // Codec bitrate is a hint. Bound each durable portion even when an encoder exceeds it.
    const val MAX_RECORD_BYTES = 32L * 1024L * 1024L
    const val MAX_MP4_BYTES = 33L * 1024L * 1024L
    // One frame a second, like every other solstone screen source, at their bitrate.
    const val BIT_RATE = 1_000_000
    const val FRAME_RATE = 1
    const val MAX_RECORD_SAMPLES = FRAME_RATE * 300 + 2
    const val I_FRAME_INTERVAL_SECONDS = 2
    const val MIME_TYPE = "video/avc"
    const val MAX_LONG_EDGE = 1280

    fun calculateResolution(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val longEdge = maxOf(width, height)
        val (w, h) = if (longEdge > MAX_LONG_EDGE) {
            val scale = MAX_LONG_EDGE.toDouble() / longEdge
            Math.round(width * scale).toInt() to Math.round(height * scale).toInt()
        } else {
            width to height
        }
        val evenW = maxOf(2, (w / 2) * 2)
        val evenH = maxOf(2, (h / 2) * 2)
        return evenW to evenH
    }
}
