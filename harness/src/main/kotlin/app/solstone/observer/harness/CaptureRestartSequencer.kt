// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

fun interface ServiceDestroyWaitSeam {
    fun awaitDestroy(timeoutMs: Long): Boolean
}

class CaptureRestartSequencer(
    private val stopPipeline: () -> Unit,
    private val stopForeground: () -> Unit,
    private val startServiceAndPipeline: () -> Unit,
    private val destroySeam: ServiceDestroyWaitSeam,
    private val isDesiredOn: () -> Boolean,
    private val isVisibleOwnerPresent: () -> Boolean,
    private val timeoutMs: Long = 5_000L,
) {
    private val lock = Any()
    private var generation = 0L

    fun onOwnerStop() {
        synchronized(lock) { generation++ }
    }

    fun requestRestart(): Boolean {
        val gen = synchronized(lock) {
            if (!isDesiredOn() || !isVisibleOwnerPresent()) return false
            val requested = ++generation
            stopPipeline()
            stopForeground()
            requested
        }

        val destroyed = destroySeam.awaitDestroy(timeoutMs)
        if (!destroyed) {
            return false
        }

        return synchronized(lock) {
            if (generation != gen || !isDesiredOn() || !isVisibleOwnerPresent()) return false
            // An owner stop either cancels this start or runs after it and stops the
            // resulting pipeline. It cannot return while this callback can still start it.
            startServiceAndPipeline()
            true
        }
    }
}
