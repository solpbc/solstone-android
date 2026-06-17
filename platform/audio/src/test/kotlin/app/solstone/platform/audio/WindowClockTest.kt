// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowClockTest {
    @Test
    fun clockReturnsCurrentWindowBoundary() {
        val clock = WindowClock(windowNanos = 300)

        assertEquals(0, clock.nanos())
        clock.advanceWindow()
        assertEquals(300, clock.nanos())
        clock.advanceWindow()
        assertEquals(600, clock.nanos())
    }
}
