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
}
