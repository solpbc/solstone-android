// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import java.util.concurrent.atomic.AtomicLong

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
    private val generation = AtomicLong(0L)

    fun onOwnerStop() {
        generation.incrementAndGet()
    }

    fun requestRestart(): Boolean {
        if (!isDesiredOn() || !isVisibleOwnerPresent()) {
            return false
        }
        val gen = generation.incrementAndGet()
        stopPipeline()
        stopForeground()

        val destroyed = destroySeam.awaitDestroy(timeoutMs)
        if (!destroyed) {
            return false
        }

        if (generation.get() != gen || !isDesiredOn()) {
            return false
        }

        startServiceAndPipeline()
        return true
    }
}
