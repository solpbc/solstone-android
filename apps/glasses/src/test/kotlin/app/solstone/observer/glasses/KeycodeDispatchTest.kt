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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeycodeDispatchTest {
    @Test
    fun mapsSwipeActions() {
        assertEquals(SwipeAction.EnsureObserving, swipeAction(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(SwipeAction.Stop, swipeAction(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test
    fun leavesNonVolumeKeysUnhandled() {
        assertNull(swipeAction(KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(swipeAction(KeyEvent.KEYCODE_ENTER))
        assertNull(swipeAction(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun dispatchesEnsureObservingAndAnnounces() {
        var ensured = false
        var announced = false

        dispatchSwipe(
            SwipeAction.EnsureObserving,
            ensureObserving = { ensured = true },
            stop = {},
            announce = { announced = true },
        )

        assertTrue(ensured)
        assertTrue(announced)
    }

    @Test
    fun dispatchesStopAndAnnounces() {
        var stopped = false
        var announced = false

        dispatchSwipe(
            SwipeAction.Stop,
            ensureObserving = {},
            stop = { stopped = true },
            announce = { announced = true },
        )

        assertTrue(stopped)
        assertTrue(announced)
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
