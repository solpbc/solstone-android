// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import kotlin.test.Test
import kotlin.test.assertEquals

class GlassesObserverLifecycleTest {
    @Test
    fun startTwiceBuildsOnePipeline() {
        var foregroundStarts = 0
        var builds = 0
        var pipelineStarts = 0
        var running = false
        var active: String? = null
        val lifecycle = IdempotentPipelineLifecycle(
            startForeground = { foregroundStarts += 1 },
            stopForeground = {},
            buildPipeline = {
                builds += 1
                "pipeline-$builds"
            },
            startPipeline = {
                running = true
                pipelineStarts += 1
            },
            stopPipeline = {},
            isRunning = { running },
            onActiveChanged = { active = it },
        )

        lifecycle.start()
        lifecycle.start()

        assertEquals(2, foregroundStarts)
        assertEquals(1, builds)
        assertEquals(1, pipelineStarts)
        assertEquals("pipeline-1", active)
    }

    @Test
    fun pipelineDiedRebuildsOnNextStart() {
        var builds = 0
        var pipelineStarts = 0
        var pipelineStops = 0
        var running = false
        val lifecycle = IdempotentPipelineLifecycle(
            startForeground = {},
            stopForeground = {},
            buildPipeline = {
                builds += 1
                "pipeline-$builds"
            },
            startPipeline = {
                running = true
                pipelineStarts += 1
            },
            stopPipeline = {
                pipelineStops += 1
                running = false
            },
            isRunning = { running },
            onActiveChanged = {},
        )

        lifecycle.start()
        running = false
        lifecycle.start()

        assertEquals(2, builds)
        assertEquals(1, pipelineStops)
        assertEquals(2, pipelineStarts)
    }
}
