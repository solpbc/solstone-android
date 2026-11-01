// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

/** Each portion belongs to one uninterrupted unlocked interval. */
open class ScreenCaptureGate(
    private val eligible: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var current: Permit? = null

    open fun start() {}
    open fun stop() { pause() }

    @Synchronized
    fun pause() {
        current?.close(now())
        current = null
    }

    @Synchronized
    fun isAllowed(): Boolean {
        val allowed = eligible()
        if (!allowed) pause()
        return allowed
    }

    @Synchronized
    fun openPortion(): Permit? {
        if (!isAllowed()) return null
        return Permit().also { current = it }
    }

    inner class Permit internal constructor() {
        @Volatile var cutoffEpochMs: Long = Long.MAX_VALUE
            private set

        internal fun close(at: Long) { cutoffEpochMs = minOf(cutoffEpochMs, at) }

        fun allowsFrame(): Boolean = synchronized(this@ScreenCaptureGate) {
            isAllowed() && current === this && cutoffEpochMs == Long.MAX_VALUE
        }
    }
}
