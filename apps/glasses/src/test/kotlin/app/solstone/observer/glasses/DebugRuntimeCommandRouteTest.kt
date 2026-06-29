// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class DebugRuntimeCommandRouteTest {
    @Test
    fun statusRoutesToSpeakStatus() {
        val fake = FakeRuntimeCommands()

        val result = routeDebugRuntimeCommand(fake, null, "status")

        assertSame(fake.speakStatusResult, result)
        assertEquals("speakStatus", fake.called)
    }

    @Test
    fun speakStatusAliasRoutesToSpeakStatus() {
        val fake = FakeRuntimeCommands()

        val result = routeDebugRuntimeCommand(fake, null, "speak_status")

        assertSame(fake.speakStatusResult, result)
        assertEquals("speakStatus", fake.called)
    }

    @Test
    fun speakNeedsAttentionRoutesToSpeakNeedsAttention() {
        val fake = FakeRuntimeCommands()

        val result = routeDebugRuntimeCommand(fake, null, "speak_needs_attention")

        assertSame(fake.speakNeedsAttentionResult, result)
        assertEquals("speakNeedsAttention", fake.called)
    }

    @Test
    fun observeAndSyncActionsRouteToMatchingCommands() {
        assertRoute("observe_start", "observeStart") { it.observeStartResult }
        assertRoute("observe_stop", "observeStop") { it.observeStopResult }
        assertRoute("sync_now", "syncNow") { it.syncNowResult }
    }

    @Test
    fun pairLinkTakesPrecedenceOverAction() {
        val fake = FakeRuntimeCommands()

        val result = routeDebugRuntimeCommand(fake, "some-link", "observe_start")

        assertSame(fake.pairLinkResult, result)
        assertEquals("pairLink", fake.called)
        assertEquals("some-link", fake.pairRaw)
    }

    @Test
    fun unknownOrBlankInputReturnsNull() {
        val fake = FakeRuntimeCommands()

        assertNull(routeDebugRuntimeCommand(fake, null, "bogus"))
        assertNull(routeDebugRuntimeCommand(fake, null, null))
        assertNull(routeDebugRuntimeCommand(fake, "  ", null))
        assertEquals(null, fake.called)
    }

    private fun assertRoute(
        action: String,
        expectedCall: String,
        expectedResult: (FakeRuntimeCommands) -> RuntimeCommandResult,
    ) {
        val fake = FakeRuntimeCommands()

        val result = routeDebugRuntimeCommand(fake, null, action)

        assertSame(expectedResult(fake), result)
        assertEquals(expectedCall, fake.called)
    }

    private class FakeRuntimeCommands : GlassesRuntimeCommands {
        val observeStartResult = CommandBlocked(RuntimeCommandBlockReason.MissingPermissions)
        val observeStopResult = CommandBlocked(RuntimeCommandBlockReason.InvalidPairLinkOrCameraBusy)
        val syncNowResult = CommandBlocked(RuntimeCommandBlockReason.PairingProbeFailed)
        val pairLinkResult = CommandBlocked(RuntimeCommandBlockReason.RuntimeUnavailable)
        val speakStatusResult = CommandNeedsAttention(SourceState.NEEDS_ATTENTION, ReasonCode.UNPAIRED)
        val speakNeedsAttentionResult = CommandNeedsAttention(SourceState.PAUSED, ReasonCode.SERVICE_KILLED)
        var called: String? = null
        var pairRaw: String? = null

        override fun observeStart(): RuntimeCommandResult {
            called = "observeStart"
            return observeStartResult
        }

        override fun observeStop(): RuntimeCommandResult {
            called = "observeStop"
            return observeStopResult
        }

        override fun syncNow(): RuntimeCommandResult {
            called = "syncNow"
            return syncNowResult
        }

        override fun pairLink(raw: String): RuntimeCommandResult {
            called = "pairLink"
            pairRaw = raw
            return pairLinkResult
        }

        override fun speakStatus(): RuntimeCommandResult {
            called = "speakStatus"
            return speakStatusResult
        }

        override fun speakNeedsAttention(): RuntimeCommandResult {
            called = "speakNeedsAttention"
            return speakNeedsAttentionResult
        }
    }
}
