// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RokidButtonBroadcastsTest {
    @Test
    fun rokidButtonActionsMapConservatively() {
        assertEquals(GlassesNotificationCommand.Status.actionToken, rokidButtonActionToken(RokidButtonActions.CLICK))
        assertEquals("observe_start", rokidButtonActionToken(RokidButtonActions.DOUBLE_CLICK))
        assertNull(rokidButtonActionToken(RokidButtonActions.DOWN))
        assertNull(rokidButtonActionToken(RokidButtonActions.UP))
        assertEquals(GlassesNotificationCommand.Stop.actionToken, rokidButtonActionToken(RokidButtonActions.LONG_PRESS))
        assertNull(rokidButtonActionToken("bogus"))
        assertNull(rokidButtonActionToken(null))
    }

    @Test
    fun rokidHandlingOnlyEnablesForRokidGuidance() {
        assertTrue(rokidButtonHandlingEnabled("rokid"))
        assertFalse(rokidButtonHandlingEnabled("generic"))
        assertFalse(rokidButtonHandlingEnabled("rogbid"))
        assertFalse(rokidButtonHandlingEnabled("samsung"))
        assertFalse(rokidButtonHandlingEnabled(""))
    }

    @Test
    fun mappedRokidTokensRouteThroughRuntimeRouter() {
        assertRoute(RokidButtonActions.CLICK, "speakStatus") { it.speakStatusResult }
        assertRoute(RokidButtonActions.DOUBLE_CLICK, "observeStart") { it.observeStartResult }
        assertRoute(RokidButtonActions.LONG_PRESS, "observeStop") { it.observeStopResult }
    }

    private fun assertRoute(
        rokidAction: String,
        expectedCall: String,
        expectedResult: (FakeRuntimeCommands) -> RuntimeCommandResult,
    ) {
        val fake = FakeRuntimeCommands()
        val token = rokidButtonActionToken(rokidAction)

        val result = routeDebugRuntimeCommand(fake, null, token)

        assertSame(expectedResult(fake), result)
        assertEquals(expectedCall, fake.called)
    }

    private class FakeRuntimeCommands : GlassesRuntimeCommands {
        val observeStartResult = CommandBlocked(RuntimeCommandBlockReason.MissingPermissions)
        val observeStopResult = CommandBlocked(RuntimeCommandBlockReason.InvalidPairLinkOrCameraBusy)
        val speakStatusResult = CommandBlocked(RuntimeCommandBlockReason.PairingProbeFailed)
        var called: String? = null

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
            return CommandSucceeded
        }

        override fun pairLink(raw: String): RuntimeCommandResult {
            called = "pairLink"
            return CommandSucceeded
        }

        override fun speakStatus(): RuntimeCommandResult {
            called = "speakStatus"
            return speakStatusResult
        }

        override fun speakNeedsAttention(): RuntimeCommandResult {
            called = "speakNeedsAttention"
            return CommandSucceeded
        }
    }
}
