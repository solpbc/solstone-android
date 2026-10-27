// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.reasonDiagnosis
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceStateDescriptionTest {
    private fun status(
        sourceId: String,
        state: SourceState,
        wish: SourceWish,
        reason: ReasonCode = ReasonCode.NONE,
        wishExpressed: Boolean = true,
    ) = SourceStatus(sourceId, wish, state, reason, wishExpressed)

    @Test
    fun needsAttentionWithKnownReasonAppendsDiagnosis() {
        val paired = true
        val status = status("audio", SourceState.NEEDS_ATTENTION, SourceWish.On, ReasonCode.AUDIO_START_FAILED)
        val subLine = sourceSubLine(status, paired)
        assertNotNull(subLine)
        assertTrue(subLine.isNotEmpty())
        val result = sourceStateDescription(status, paired)
        assertEquals(sourceStateCopy(status.state) + ". " + subLine, result)
        assertTrue(result.contains(reasonDiagnosis(ReasonCode.AUDIO_START_FAILED)!!))
    }

    @Test
    fun needsAttentionWithoutDiagnosisAppendsHonestUnknownFallback() {
        val paired = true
        val status = status("audio", SourceState.NEEDS_ATTENTION, SourceWish.On, ReasonCode.NONE)
        val subLine = sourceSubLine(status, paired)
        assertNotNull(subLine)
        assertTrue(subLine.isNotEmpty())
        assertEquals(sourceStateCopy(status.state) + ". " + subLine, sourceStateDescription(status, paired))
    }

    @Test
    fun microphoneSilencedAppendsSilencedDiagnosis() {
        val paired = true
        val status = status("audio", SourceState.PAUSED, SourceWish.On, ReasonCode.MICROPHONE_SILENCED)
        val subLine = sourceSubLine(status, paired)
        assertNotNull(subLine)
        assertTrue(subLine.isNotEmpty())
        assertEquals(sourceStateCopy(status.state) + ". " + subLine, sourceStateDescription(status, paired))
    }

    @Test
    fun cameraOnReturnsStateWordAlone() {
        val paired = true
        val status = status("camera", SourceState.ON, SourceWish.On, ReasonCode.NONE)
        val subLine = sourceSubLine(status, paired)
        assertNotNull(subLine)
        assertEquals(sourceStateCopy(status.state), sourceStateDescription(status, paired))
    }

    @Test
    fun ownerPauseReturnsStateWordAlone() {
        val paired = true
        val status = status("audio", SourceState.PAUSED, SourceWish.On, ReasonCode.NONE)
        val subLine = sourceSubLine(status, paired)
        assertNotNull(subLine)
        assertEquals(sourceStateCopy(status.state), sourceStateDescription(status, paired))
    }

    @Test
    fun offPairedAndUnpairedReturnsStateWordAlone() {
        val status = status("audio", SourceState.OFF, SourceWish.Off, ReasonCode.NONE)
        for (paired in listOf(true, false)) {
            val subLine = sourceSubLine(status, paired)
            assertNotNull(subLine)
            assertEquals(sourceStateCopy(status.state), sourceStateDescription(status, paired))
        }
    }

    @Test
    fun settingUpReturnsStateWordAlone() {
        val paired = true
        val status = status("audio", SourceState.SETTING_UP, SourceWish.On, ReasonCode.NONE)
        val subLine = sourceSubLine(status, paired)
        assertNotNull(subLine)
        assertEquals(sourceStateCopy(status.state), sourceStateDescription(status, paired))
    }

    @Test
    fun readyToSetUpReturnsStateWordAlone() {
        val paired = true
        val status = status(
            sourceId = "audio",
            state = SourceState.READY_TO_SET_UP,
            wish = SourceWish.Off,
            reason = ReasonCode.NONE,
            wishExpressed = false,
        )
        assertNull(sourceSubLine(status, paired))
        assertEquals(sourceStateCopy(status.state), sourceStateDescription(status, paired))
    }
}
