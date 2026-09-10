// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarnessFactsDisplayTest {
    @Test
    fun everyReasonCodeHasExactOwnerVoiceDisplay() {
        val expected = mapOf(
            ReasonCode.NONE to "needs attention",
            ReasonCode.PERMISSION_REVOKED to "needs attention: permissions needed",
            ReasonCode.SERVICE_KILLED to "needs attention: intake was stopped by the system",
            ReasonCode.PERSISTENCE_FAILED to "needs attention: couldn't save journal access on this phone",
            ReasonCode.REBOOTED to "needs attention: this device restarted and intake didn't resume on its own",
            ReasonCode.UNPAIRED to "needs attention: not paired with your journal",
            ReasonCode.STORAGE_FULL to "needs attention: storage is full",
            ReasonCode.PROVIDER_SILENT to "needs attention: nothing has come in recently",
            ReasonCode.AUTH_REVOKED to "needs attention: access to your journal was revoked",
            ReasonCode.TRANSPORT_UNAVAILABLE to "needs attention: can't reach your journal",
            ReasonCode.FOREGROUND_START_NOT_ALLOWED to "needs attention: intake couldn't start from the background",
            ReasonCode.FOREGROUND_TYPE_NOT_HELD to "needs attention: this source needs intake to restart",
            // Repeating the state as a reason adds no information, so this is the bare state word.
            ReasonCode.DESIRED_OFF to "needs attention",
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
        assertEquals("off", displayFor(SourceState.OFF, ReasonCode.NONE))
    }

    @Test
    fun everyStateWordIsTheLockedLowercaseVocabulary() {
        val expected = mapOf(
            SourceState.OFF to "off",
            SourceState.SETTING_UP to "setting up",
            SourceState.ON to "on",
            SourceState.PAUSED to "paused",
            SourceState.NEEDS_ATTENTION to "needs attention",
        )

        assertEquals(SourceState.entries.toSet(), expected.keys)
        SourceState.entries.forEach { state ->
            assertEquals(expected.getValue(state), displayFor(state, ReasonCode.NONE), state.name)
        }
    }

    /**
     * The regression this table exists to prevent.
     *
     * ⛔ `sol` is a deleted product name and `observing` is a retired state noun. Both shipped here
     * for months **with a passing test asserting them as owner voice** — the gate defended the
     * defect. Pinning the absence, per reason, is what makes a future local string fail loudly
     * rather than pass quietly.
     */
    @Test
    fun noReasonUsesADeletedProductNameOrARetiredStateNoun() {
        ReasonCode.entries.forEach { reason ->
            val diagnosis = reasonDiagnosis(reason) ?: return@forEach
            assertFalse(diagnosis.contains("observing"), "$reason: $diagnosis")
            assertFalse(
                Regex("\\bsol\\b").containsMatchIn(diagnosis),
                "$reason: $diagnosis",
            )
        }
        // Positive control: the matcher finds what it is looking for when it is present, so a
        // clean sweep above is a measurement rather than a dead assertion.
        assertTrue(Regex("\\bsol\\b").containsMatchIn("open sol to resume observing"))
        assertTrue("open sol to resume observing".contains("observing"))
    }

    @Test
    fun onlyDesiredOffAndNoneOmitADiagnosis() {
        ReasonCode.entries.forEach { reason ->
            when (reason) {
                ReasonCode.DESIRED_OFF, ReasonCode.NONE -> assertNull(reasonDiagnosis(reason), reason.name)
                else -> assertTrue(reasonDiagnosis(reason)?.isNotBlank() == true, reason.name)
            }
        }
    }
}
