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

fun phraseFor(cue: StatusCue): String =
    when (cue) {
        StatusCue.OBSERVING -> "Observing"
        StatusCue.OBSERVER_PAUSED -> "Observing paused"
        StatusCue.NEEDS_ATTENTION -> "Needs attention"
        StatusCue.NOT_PAIRED -> "Not paired"
        StatusCue.PAIRED -> "Paired"
        StatusCue.SYNC_FAILED -> "Sync failed"
        StatusCue.PAIRING_READY -> "Pairing ready"
        StatusCue.HANDSHAKE_VALID -> "Handshake valid"
        StatusCue.PAIRING_FAILED -> "Pairing failed"
        StatusCue.BATTERY_LOW -> "Battery low"
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

enum class SwipeAction { EnsureObserving, Stop }

// Swipe forward = VOLUME_UP (24), swipe back = VOLUME_DOWN (25), decided on-device from the KeyEvent log.
// Forward ensures observing, back stops observing, and either gesture speaks live status.
fun swipeAction(keyCode: Int): SwipeAction? =
    when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> SwipeAction.EnsureObserving
        KeyEvent.KEYCODE_VOLUME_DOWN -> SwipeAction.Stop
        else -> null
    }

fun dispatchSwipe(
    action: SwipeAction,
    ensureObserving: () -> Unit,
    stop: () -> Unit,
    announce: () -> Unit,
) {
    when (action) {
        SwipeAction.EnsureObserving -> ensureObserving()
        SwipeAction.Stop -> stop()
    }
    announce()
}

class StatusCuePoller(
    private val snapshotProvider: () -> CueSnapshot,
    private val audio: AudioFeedback,
) {
    private var previous: CueSnapshot? = null

    fun tick() {
        val current = snapshotProvider()
        val cue = cueFor(previous, current)
        previous = current
        if (cue != null) audio.play(cue)
    }
}
