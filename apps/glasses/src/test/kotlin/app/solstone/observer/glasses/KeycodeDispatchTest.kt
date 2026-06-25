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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeycodeDispatchTest {
    @Test
    fun mapsSwipeActions() {
        assertEquals(SwipeAction.Start, swipeAction(KeyEvent.KEYCODE_VOLUME_UP, desiredOn = false))
        assertEquals(SwipeAction.AnnounceStatus, swipeAction(KeyEvent.KEYCODE_VOLUME_UP, desiredOn = true))
        assertEquals(SwipeAction.Stop, swipeAction(KeyEvent.KEYCODE_VOLUME_DOWN, desiredOn = true))
        assertEquals(SwipeAction.AnnounceStatus, swipeAction(KeyEvent.KEYCODE_VOLUME_DOWN, desiredOn = false))
    }

    @Test
    fun leavesNonVolumeKeysUnhandled() {
        assertNull(swipeAction(KeyEvent.KEYCODE_DPAD_CENTER, desiredOn = false))
        assertNull(swipeAction(KeyEvent.KEYCODE_ENTER, desiredOn = false))
        assertNull(swipeAction(KeyEvent.KEYCODE_DPAD_DOWN, desiredOn = false))
    }

    @Test
    fun dispatchesStartWithoutAttentionWhenAccepted() {
        var started = false
        var attention = false

        dispatchSwipe(
            SwipeAction.Start,
            start = {
                started = true
                true
            },
            stop = {},
            announce = {},
            attention = { attention = true },
        )

        assertTrue(started)
        assertFalse(attention)
    }

    @Test
    fun dispatchesStartWithAttentionWhenRefused() {
        var attention = false

        dispatchSwipe(
            SwipeAction.Start,
            start = { false },
            stop = {},
            announce = {},
            attention = { attention = true },
        )

        assertTrue(attention)
    }

    @Test
    fun dispatchesStop() {
        var stopped = false

        dispatchSwipe(
            SwipeAction.Stop,
            start = { true },
            stop = { stopped = true },
            announce = {},
            attention = {},
        )

        assertTrue(stopped)
    }

    @Test
    fun dispatchesAnnounceStatus() {
        var announced = false

        dispatchSwipe(
            SwipeAction.AnnounceStatus,
            start = { true },
            stop = {},
            announce = { announced = true },
            attention = {},
        )

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
