// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import app.solstone.core.segment.MonotonicClock

class WindowClock(private val windowNanos: Long) : MonotonicClock {
    private var windowIndex: Long = 0L

    init {
        require(windowNanos > 0L) { "windowNanos must be positive" }
    }

    override fun nanos(): Long = windowIndex * windowNanos

    fun advanceWindow() {
        windowIndex += 1L
    }
}
