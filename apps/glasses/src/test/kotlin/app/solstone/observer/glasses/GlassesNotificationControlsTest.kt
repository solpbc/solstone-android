// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.app.PendingIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GlassesNotificationControlsTest {
    @Test
    fun notificationCommandTokensRouteThroughRuntimeRouter() {
        assertRoute(GlassesNotificationCommand.Stop, "observeStop") { it.observeStopResult }
        assertRoute(GlassesNotificationCommand.Sync, "syncNow") { it.syncNowResult }
        assertRoute(GlassesNotificationCommand.Status, "speakStatus") { it.speakStatusResult }
    }

    @Test
    fun notificationRequestCodesAreDistinctAndDoNotAliasContentIntent() {
        val requestCodes = GlassesNotificationCommand.entries.map { it.requestCode }

        assertEquals(requestCodes.size, requestCodes.toSet().size)
        requestCodes.forEach { requestCode ->
            assertNotEquals(CONTENT_INTENT_REQUEST_CODE, requestCode)
        }
    }

    @Test
    fun pendingIntentFlagsAlwaysSetImmutableOnSupportedLevels() {
        assertTrue(glassesPendingIntentFlags(26) and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(glassesPendingIntentFlags(33) and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun speakDecisionDispatchesOnlyWhenRuntimeAvailable() {
        assertEquals(NotificationSpeakDecision.DispatchStatus, decideNotificationSpeak(runtimeAvailable = true))
        assertEquals(NotificationSpeakDecision.NoOp, decideNotificationSpeak(runtimeAvailable = false))
    }

    private fun assertRoute(
        command: GlassesNotificationCommand,
        expectedCall: String,
        expectedResult: (FakeRuntimeCommands) -> RuntimeCommandResult,
    ) {
        val fake = FakeRuntimeCommands()

        val result = routeDebugRuntimeCommand(fake, null, command.actionToken)

        assertSame(expectedResult(fake), result)
        assertEquals(expectedCall, fake.called)
    }

    private class FakeRuntimeCommands : GlassesRuntimeCommands {
        val observeStopResult = CommandBlocked(RuntimeCommandBlockReason.InvalidPairLinkOrCameraBusy)
        val syncNowResult = CommandBlocked(RuntimeCommandBlockReason.PairingProbeFailed)
        val speakStatusResult = CommandBlocked(RuntimeCommandBlockReason.MissingPermissions)
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
            return syncNowResult
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
