// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HarnessFactsDisplayTest {
    @Test
    fun everyReasonCodeHasExactOwnerVoiceDisplay() {
        val expected = mapOf(
            ReasonCode.NONE to "Needs attention",
            ReasonCode.PERMISSION_REVOKED to "Needs attention: permissions needed",
            ReasonCode.SERVICE_KILLED to "Needs attention: observing was stopped by the system",
            ReasonCode.REBOOTED to "Needs attention: restart observing after reboot",
            ReasonCode.UNPAIRED to "Needs attention: not paired with your journal",
            ReasonCode.STORAGE_FULL to "Needs attention: phone storage is full",
            ReasonCode.PROVIDER_SILENT to "Needs attention: nothing observed recently",
            ReasonCode.AUTH_REVOKED to "Needs attention: access was revoked - pair again",
            ReasonCode.EXEMPTION_UNVERIFIED to "Needs attention: battery settings may stop sol in the background",
            ReasonCode.TRANSPORT_UNAVAILABLE to "Needs attention: can't reach your journal",
            ReasonCode.FOREGROUND_START_NOT_ALLOWED to "Needs attention: open sol to resume observing",
            ReasonCode.DESIRED_OFF to "Needs attention: observing is turned off",
        )

        assertEquals(ReasonCode.entries.toSet(), expected.keys)
        ReasonCode.entries.forEach { reason ->
            val display = displayFor(SourceState.NEEDS_ATTENTION, reason)
            assertEquals(expected.getValue(reason), display, reason.name)
            assertFalse(display.contains('_'), reason.name)
            assertFalse(display.contains(reason.name.lowercase(Locale.ROOT)), reason.name)
        }
    }

    @Test
    fun noneKeepsBareStateLabelWithoutSuffix() {
        assertEquals("Off", displayFor(SourceState.OFF, ReasonCode.NONE))
    }
}
