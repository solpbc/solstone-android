// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.StatusCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame

class GlassesCommandRoutingTest {
    @Test
    fun commandReceiverDoesNotOwnPrivateExecutor() {
        assertTrue(
            GlassesCommandReceiver::class.java.declaredFields.none {
                it.name.contains("executor", ignoreCase = true)
            },
        )
    }

    @Test
    fun broadcastStopRoutesAndEmitsPausedCueAndDiag() {
        val runtime = RecordingRuntimeCommands()
        val cues = mutableListOf<StatusCue>()
        val diag = mutableListOf<String>()

        val result = routeCommandSurfaceCommand(
            runtime = runtime,
            rawAction = RokidButtonActions.LONG_PRESS,
            token = GlassesNotificationCommand.Stop.actionToken,
            appendDiag = { diag += it },
            playCue = { cues += it },
        )

        assertSame(runtime.observeStopResult, result)
        assertEquals("observeStop", runtime.called)
        assertEquals(listOf(StatusCue.OBSERVER_PAUSED), cues)
        val line = diag.single()
        assertTrue(line.contains("command-accepted"))
        assertTrue(line.contains("source=broadcast"))
        assertTrue(line.contains("action=${RokidButtonActions.LONG_PRESS}"))
        assertTrue(line.contains("token=${GlassesNotificationCommand.Stop.actionToken}"))
    }

    @Test
    fun notificationStopGetsSamePausedCueAndDiagWithNotificationSource() {
        val runtime = RecordingRuntimeCommands()
        val cues = mutableListOf<StatusCue>()
        val diag = mutableListOf<String>()

        routeCommandSurfaceCommand(
            runtime = runtime,
            rawAction = null,
            token = GlassesNotificationCommand.Stop.actionToken,
            appendDiag = { diag += it },
            playCue = { cues += it },
        )

        assertEquals(listOf(StatusCue.OBSERVER_PAUSED), cues)
        assertTrue(diag.single().contains("source=notification"))
    }

    private class RecordingRuntimeCommands : GlassesRuntimeCommands {
        val observeStopResult = CommandSucceeded
        var called: String? = null

        override fun observeStart(): RuntimeCommandResult {
            called = "observeStart"
            return CommandSucceeded
        }

        override fun observeStop(): RuntimeCommandResult {
            called = "observeStop"
            return observeStopResult
        }

        override fun syncNow(): RuntimeCommandResult {
            called = "syncNow"
            return CommandSucceeded
        }

        override fun pairLink(raw: String): RuntimeCommandResult {
            called = "pairLink"
            return CommandSucceeded
        }

        override fun speakStatus(): RuntimeCommandResult {
            called = "speakStatus"
            return CommandSucceeded
        }

        override fun speakNeedsAttention(): RuntimeCommandResult {
            called = "speakNeedsAttention"
            return CommandSucceeded
        }
    }
}
