// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchWindowPumpTest {

    @Test
    fun acceptsAbuttingFramesAndRotatesAtWindowBoundary() {
        val completedWindows = mutableListOf<CompletedWatchWindow>()
        val pump = WatchWindowPump(windowMs = 300_000L) { window ->
            completedWindows.add(window)
        }

        val start = 1_000_000L
        pump.start(start)
        assertTrue(pump.receiving)

        // Window 1 starts at first frame: 1_000_000L, boundary is at 1_300_000L
        // Frame 1: [1_000_000, 1_050_000)
        val f1 = TimestampedAudioFrame(1_000_000L, byteArrayOf(1, 2, 3), 50_000L)
        // Frame 2: [1_050_000, 1_100_000) (abutting f1)
        val f2 = TimestampedAudioFrame(1_050_000L, byteArrayOf(4, 5, 6), 50_000L)
        // Frame 3: [1_100_000, 1_150_000) (abutting f2)
        val f3 = TimestampedAudioFrame(1_100_000L, byteArrayOf(7, 8, 9), 50_000L)

        pump.feed(f1)
        pump.feed(f2)
        pump.feed(f3)

        assertEquals(0, completedWindows.size)
        assertTrue(pump.receiving)

        // Opens new window query: false before window boundary, true at or after boundary
        assertFalse(pump.opensNewWindow(1_299_999L))
        assertTrue(pump.opensNewWindow(1_300_000L))

        // Frame 4 starts at 1_300_000L (crosses boundary into next window [1_300_000, 1_600_000))
        val f4 = TimestampedAudioFrame(1_300_000L, byteArrayOf(10, 11), 50_000L)
        pump.feed(f4)

        // Rotating closes previous window while f4 is accepted and receiving stays true
        assertEquals(1, completedWindows.size)
        assertTrue(pump.receiving)

        val w1 = completedWindows.first()
        assertEquals(3, w1.frames.size)
        assertEquals(1_000_000L, w1.startEpochMs)
        assertEquals(1_150_000L, w1.endEpochMs)
        // Verify abutting frame timestamps
        assertEquals(f1.timestampEpochMs + f1.durationMs, f2.timestampEpochMs)
        assertEquals(f2.timestampEpochMs + f2.durationMs, f3.timestampEpochMs)

        // Stop pump
        pump.stop(WindowPumpStopReason.OWNER_STOP)
        assertFalse(pump.receiving)
        assertEquals(2, completedWindows.size)
        val w2 = completedWindows[1]
        assertEquals(1_300_000L, w2.startEpochMs)
        assertEquals(1_350_000L, w2.endEpochMs)
        assertEquals(listOf(f4), w2.frames)
    }

    @Test
    fun framesOfferedDuringRemuxAreKeptAndReceivingStaysTrue() {
        var remuxExecuting = false
        var framesAcceptedDuringRemux = 0

        val completedWindows = mutableListOf<CompletedWatchWindow>()
        lateinit var pump: WatchWindowPump
        pump = WatchWindowPump(windowMs = 300_000L) { window ->
            remuxExecuting = true
            // Simulate parallel frame offering while previous window is completing
            val parallelFrame = TimestampedAudioFrame(window.startEpochMs + 300_000L + 10L, byteArrayOf(99), 10L)
            if (pump.feed(parallelFrame)) {
                framesAcceptedDuringRemux++
            }
            completedWindows.add(window)
            remuxExecuting = false
        }

        pump.start(0L)
        pump.feed(TimestampedAudioFrame(0L, byteArrayOf(1), 100_000L))

        // Trigger rotation by feeding frame past 300_000 ms
        pump.feed(TimestampedAudioFrame(300_000L, byteArrayOf(2), 50_000L))

        assertEquals(1, completedWindows.size)
        assertEquals(1, framesAcceptedDuringRemux)
        assertTrue(pump.receiving)

        pump.stop(WindowPumpStopReason.OWNER_STOP)
        assertEquals(2, completedWindows.size)
        // Note: the trigger frame (at 300_000L) + the parallel frame (at 300_010L) were both accepted in the second window
        assertEquals(2, completedWindows[1].frames.size)
    }
}
