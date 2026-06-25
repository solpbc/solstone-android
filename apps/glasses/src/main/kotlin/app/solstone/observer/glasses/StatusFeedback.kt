// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.view.KeyEvent
import app.solstone.core.diagnostics.CueSnapshot
import app.solstone.core.diagnostics.StatusCue
import app.solstone.core.diagnostics.cueFor
import app.solstone.observer.harness.HarnessController

fun rawResFor(cue: StatusCue): Int =
    when (cue) {
        StatusCue.OBSERVING -> R.raw.fb_observing
        StatusCue.OBSERVER_PAUSED -> R.raw.fb_observer_paused
        StatusCue.NEEDS_ATTENTION -> R.raw.fb_needs_attention
        StatusCue.NOT_PAIRED -> R.raw.fb_not_paired
        StatusCue.PAIRED -> R.raw.fb_paired
        StatusCue.SYNC_FAILED -> R.raw.fb_sync_failed
        // Exhaustive references keep UnusedResources lint green for reserved Phase-1b clips.
        StatusCue.PAIRING_READY -> R.raw.fb_pairing_ready
        StatusCue.HANDSHAKE_VALID -> R.raw.fb_handshake_valid
        StatusCue.PAIRING_FAILED -> R.raw.fb_pairing_failed
        StatusCue.BATTERY_LOW -> R.raw.fb_battery_low
    }

fun cueSnapshot(controller: HarnessController): CueSnapshot {
    val diagnostics = controller.diagnostics()
    return CueSnapshot(
        state = diagnostics.state,
        reason = diagnostics.reason,
        pairing = controller.pairingFact(),
        lastFailureAt = controller.syncState().lastFailureAt,
    )
}

fun isTempleButtonKey(keyCode: Int): Boolean =
    // Candidate set is finalized on-device from the KeyEvent log.
    keyCode == KeyEvent.KEYCODE_STEM_PRIMARY ||
        keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        keyCode == KeyEvent.KEYCODE_BUTTON_1

class StatusCuePoller(
    private val snapshotProvider: () -> CueSnapshot,
    private val audio: AudioFeedback,
    private val rawResFor: (StatusCue) -> Int = ::rawResFor,
) {
    private var previous: CueSnapshot? = null

    fun tick() {
        val current = snapshotProvider()
        val cue = cueFor(previous, current)
        previous = current
        if (cue != null) audio.play(rawResFor(cue))
    }
}
