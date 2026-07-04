// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        lifecycle.start()

        assertEquals(0, foregroundStarts)
        assertEquals(0, builds)
        assertEquals(0, starts)
        assertEquals(true, deferredStartPending)
        assertNull(active)

        recoveryCompleted = true
        lifecycle.replayDeferredStartIfPending()

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

    @Test
    fun stopCancelsPendingDeferredStart() {
        var recoveryCompleted = false
        var deferredStarts = 0
        var deferredCancels = 0
        var builds = 0
        val lifecycle = IdempotentPipelineLifecycle(
            startForeground = {},
            stopForeground = {},
            buildPipeline = {
                builds += 1
                TestPipeline(builds)
            },
            startPipeline = { it.running = true },
            stopPipeline = { it.running = false },
            isRunning = { it.running },
            onActiveChanged = {},
            canStart = { recoveryCompleted },
            onStartDeferred = { deferredStarts += 1 },
            onStartCancelled = { deferredCancels += 1 },
        )

        lifecycle.start()
        lifecycle.start()
        lifecycle.stop()
        recoveryCompleted = true
        lifecycle.replayDeferredStartIfPending()

        assertEquals(1, deferredStarts)
        assertEquals(1, deferredCancels)
        assertEquals(0, builds)
    }

    @Test
    fun concurrentStartBuildsAndStartsOnePipeline() {
        var builds = 0
        var starts = 0
        var running = false
        val buildEntered = CountDownLatch(1)
        val releaseBuild = CountDownLatch(1)
        val lifecycle = IdempotentPipelineLifecycle(
            startForeground = {},
            stopForeground = {},
            buildPipeline = {
                builds += 1
                buildEntered.countDown()
                assertTrue(releaseBuild.await(1, TimeUnit.SECONDS))
                "pipeline-$builds"
            },
            startPipeline = {
                running = true
                starts += 1
            },
            stopPipeline = { running = false },
            isRunning = { running },
            onActiveChanged = {},
        )

        val first = thread(start = true) { lifecycle.start() }
        assertTrue(buildEntered.await(1, TimeUnit.SECONDS))
        val second = thread(start = true) { lifecycle.start() }
        releaseBuild.countDown()
        first.join(1_000)
        second.join(1_000)

        assertEquals(1, builds)
        assertEquals(1, starts)
    }
}

private class TestPipeline(val id: Int) {
    var running: Boolean = false
}
