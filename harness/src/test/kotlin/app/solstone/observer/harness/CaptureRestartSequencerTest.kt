// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureRestartSequencerTest {

    @Test
    fun stopPipelineInvokedBeforeStart() {
        val events = mutableListOf<String>()
        var desiredOn = true
        val sequencer = CaptureRestartSequencer(
            stopPipeline = { events += "stopPipeline" },
            stopForeground = { events += "stopForeground" },
            startServiceAndPipeline = { events += "startServiceAndPipeline" },
            destroySeam = { timeoutMs ->
                events += "awaitDestroy($timeoutMs)"
                true
            },
            isDesiredOn = { desiredOn },
            isVisibleOwnerPresent = { true },
        )

        val started = sequencer.requestRestart()

        assertTrue(started)
        assertEquals(
            listOf("stopPipeline", "stopForeground", "awaitDestroy(5000)", "startServiceAndPipeline"),
            events,
        )
    }

    @Test
    fun startNotCalledIfOwnerStopDuringWait() {
        val events = mutableListOf<String>()
        var desiredOn = true
        lateinit var sequencer: CaptureRestartSequencer
        sequencer = CaptureRestartSequencer(
            stopPipeline = { events += "stopPipeline" },
            stopForeground = { events += "stopForeground" },
            startServiceAndPipeline = { events += "startServiceAndPipeline" },
            destroySeam = {
                events += "awaitDestroy"
                desiredOn = false
                sequencer.onOwnerStop()
                true
            },
            isDesiredOn = { desiredOn },
            isVisibleOwnerPresent = { true },
        )

        val started = sequencer.requestRestart()

        assertFalse(started)
        assertEquals(listOf("stopPipeline", "stopForeground", "awaitDestroy"), events)
    }

    @Test
    fun startNotCalledWhileDestroyNotSignaledOrTimeout() {
        val events = mutableListOf<String>()
        val sequencer = CaptureRestartSequencer(
            stopPipeline = { events += "stopPipeline" },
            stopForeground = { events += "stopForeground" },
            startServiceAndPipeline = { events += "startServiceAndPipeline" },
            destroySeam = { false },
            isDesiredOn = { true },
            isVisibleOwnerPresent = { true },
        )

        val started = sequencer.requestRestart()

        assertFalse(started)
        assertEquals(listOf("stopPipeline", "stopForeground"), events)
    }

    @Test
    fun requestRestartReturnsFalseWhenNotDesiredOnOrNoVisibleOwner() {
        var desiredOn = false
        var visibleOwner = true
        val sequencer = CaptureRestartSequencer(
            stopPipeline = {},
            stopForeground = {},
            startServiceAndPipeline = {},
            destroySeam = { true },
            isDesiredOn = { desiredOn },
            isVisibleOwnerPresent = { visibleOwner },
        )

        assertFalse(sequencer.requestRestart())

        desiredOn = true
        visibleOwner = false
        assertFalse(sequencer.requestRestart())
    }
    @Test
    fun losesVisibleAuthorityWhileWaitingForServiceDestruction() {
        var visible = true
        var starts = 0
        val sequencer = CaptureRestartSequencer(
            stopPipeline = {},
            stopForeground = {},
            startServiceAndPipeline = { starts++ },
            destroySeam = { visible = false; true },
            isDesiredOn = { true },
            isVisibleOwnerPresent = { visible },
        )
        assertFalse(sequencer.requestRestart())
        assertEquals(0, starts)
    }

    @Test
    fun ownerStopCannotFinishBeforeAnAlreadyCommittedRestartStart() {
        val enteredStart = java.util.concurrent.CountDownLatch(1)
        val releaseStart = java.util.concurrent.CountDownLatch(1)
        val attemptedStop = java.util.concurrent.CountDownLatch(1)
        val finishedStop = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.atomic.AtomicBoolean(false)
        val sequencer = CaptureRestartSequencer(
            stopPipeline = { running.set(false) },
            stopForeground = {},
            startServiceAndPipeline = {
                enteredStart.countDown()
                check(releaseStart.await(5, java.util.concurrent.TimeUnit.SECONDS))
                running.set(true)
            },
            destroySeam = { true },
            isDesiredOn = { true },
            isVisibleOwnerPresent = { true },
        )
        val restart = Thread { sequencer.requestRestart() }
        val stop = Thread {
            attemptedStop.countDown()
            sequencer.onOwnerStop()
            running.set(false)
            finishedStop.countDown()
        }
        restart.start()
        try {
            assertTrue(enteredStart.await(5, java.util.concurrent.TimeUnit.SECONDS))
            stop.start()
            assertTrue(attemptedStop.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val stopOvertookStart = finishedStop.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            releaseStart.countDown()
            restart.join(5_000)
            stop.join(5_000)
            assertFalse(stopOvertookStart, "owner stop returned while restart could still start intake")
            assertFalse(running.get())
        } finally {
            releaseStart.countDown()
            restart.join(5_000)
            if (stop.state != Thread.State.NEW) stop.join(5_000)
        }
    }

}
