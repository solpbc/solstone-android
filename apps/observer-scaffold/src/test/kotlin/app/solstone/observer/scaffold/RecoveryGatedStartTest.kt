// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RecoveryGatedStartTest {
    /**
     * AC7 red proof: before the recovery gate, a visible start during recovery could build/start the pipeline immediately.
     */
    @Test
    fun startRequestedDuringRecoveryIsDeferredUntilRecoveryCompletes() {
        var recoveryCompleted = false
        var deferredStartPending = false
        var foregroundStarts = 0
        var foregroundStops = 0
        var builds = 0
        var starts = 0
        var stops = 0
        var active: TestPipeline? = null
        val lifecycle = IdempotentPipelineLifecycle(
            startForeground = { foregroundStarts += 1 },
            stopForeground = { foregroundStops += 1 },
            buildPipeline = { TestPipeline(++builds) },
            startPipeline = { pipeline ->
                starts += 1
                pipeline.running = true
            },
            stopPipeline = { pipeline ->
                stops += 1
                pipeline.running = false
            },
            isRunning = { it.running },
            onActiveChanged = { active = it },
            canStart = { recoveryCompleted },
            onStartDeferred = { deferredStartPending = true },
            onStartCancelled = { deferredStartPending = false },
        )

        lifecycle.start()

        assertEquals(0, foregroundStarts)
        assertEquals(0, builds)
        assertEquals(0, starts)
        assertEquals(true, deferredStartPending)
        assertNull(active)

        recoveryCompleted = true
        if (deferredStartPending) {
            deferredStartPending = false
            lifecycle.start()
        }

        val started = active
        assertEquals(1, foregroundStarts)
        assertEquals(1, builds)
        assertEquals(1, starts)
        assertEquals(1, started?.id)
        assertEquals(true, started?.running)

        lifecycle.start()

        assertSame(started, active)
        assertEquals(1, builds)
        assertEquals(1, starts)

        lifecycle.stop()

        assertEquals(1, stops)
        assertEquals(1, foregroundStops)
        assertEquals(false, started?.running)
        assertNull(active)
    }
}

private class TestPipeline(val id: Int) {
    var running: Boolean = false
}
