// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.view.KeyEvent
import app.solstone.core.diagnostics.CueSnapshot
import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.diagnostics.StatusCue
import app.solstone.core.diagnostics.statusCueFor
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeycodeDispatchTest {
    @Test
    fun acceptsTempleButtonCandidateKeys() {
        assertTrue(isTempleButtonKey(KeyEvent.KEYCODE_STEM_PRIMARY))
        assertTrue(isTempleButtonKey(KeyEvent.KEYCODE_HEADSETHOOK))
        assertTrue(isTempleButtonKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(isTempleButtonKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(isTempleButtonKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(isTempleButtonKey(KeyEvent.KEYCODE_BUTTON_1))
        assertFalse(isTempleButtonKey(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun mapsButtonStatusCues() {
        assertEquals(StatusCue.OBSERVING, statusCueFor(snapshot(SourceState.ON)))
        assertEquals(StatusCue.OBSERVER_PAUSED, statusCueFor(snapshot(SourceState.OFF)))
        assertEquals(
            StatusCue.NOT_PAIRED,
            statusCueFor(snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.UNPAIRED, PairingFact.UNPAIRED)),
        )
        assertEquals(
            StatusCue.NEEDS_ATTENTION,
            statusCueFor(snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.PROVIDER_SILENT)),
        )
    }

    private fun snapshot(
        state: SourceState,
        reason: ReasonCode = ReasonCode.NONE,
        pairing: PairingFact = PairingFact.PAIRED,
        lastFailureAt: Long? = null,
    ) = CueSnapshot(state = state, reason = reason, pairing = pairing, lastFailureAt = lastFailureAt)
}
