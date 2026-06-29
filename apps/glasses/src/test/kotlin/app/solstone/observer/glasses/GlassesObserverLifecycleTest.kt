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
        var active: String? = null
        val lifecycle = IdempotentPipelineLifecycle(
            startForeground = { foregroundStarts += 1 },
            stopForeground = {},
            buildPipeline = {
                builds += 1
                "pipeline-$builds"
            },
            startPipeline = { pipelineStarts += 1 },
            stopPipeline = {},
            onActiveChanged = { active = it },
        )

        lifecycle.start()
        lifecycle.start()

        assertEquals(2, foregroundStarts)
        assertEquals(1, builds)
        assertEquals(1, pipelineStarts)
        assertEquals("pipeline-1", active)
    }
}
