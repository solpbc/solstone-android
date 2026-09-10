// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.diagnostics

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState

enum class StatusCue {
    OBSERVING,
    OBSERVER_PAUSED,
    NEEDS_ATTENTION,
    NOT_PAIRED,
    PAIRED,
    SYNC_FAILED,
    // Reserved Phase-1b vocabulary; cueFor/statusCueFor must never return these.
    PAIRING_STARTED,
    NETWORK_UNAVAILABLE,
    REFRESH_CODE,
    HANDSHAKE_VALID,
    PAIRING_FAILED,
    BATTERY_LOW,
}

data class CueSnapshot(
    val state: SourceState,
    val reason: ReasonCode,
    val pairing: PairingFact,
    val lastFailureAt: Long?,
)

fun cueFor(prev: CueSnapshot?, current: CueSnapshot): StatusCue? {
    if (prev == null) return null
    return when {
        prev.pairing != PairingFact.PAIRED && current.pairing == PairingFact.PAIRED -> StatusCue.PAIRED
        prev.state != SourceState.ON && current.state == SourceState.ON -> StatusCue.OBSERVING
        prev.state != SourceState.OFF && current.state == SourceState.OFF -> StatusCue.OBSERVER_PAUSED
        current.state == SourceState.NEEDS_ATTENTION &&
            (prev.state != SourceState.NEEDS_ATTENTION || prev.reason != current.reason) ->
            if (current.reason == ReasonCode.UNPAIRED || current.pairing != PairingFact.PAIRED) {
                StatusCue.NOT_PAIRED
            } else {
                StatusCue.NEEDS_ATTENTION
            }
        current.lastFailureAt != null && current.lastFailureAt != prev.lastFailureAt -> StatusCue.SYNC_FAILED
        else -> null
    }
}

fun statusCueFor(current: CueSnapshot): StatusCue =
    when (current.state) {
        SourceState.ON -> StatusCue.OBSERVING
        // ⛔ NOT grouped with the attention arm below, and that grouping was the cheap edit the
        // compiler nudges you toward: this cue is SPOKEN ALOUD on glasses, so announcing "needs
        // attention" for a source the owner has simply never set up is a fault claim with nothing
        // behind it. It is not running, which is what `OFF` already means for a cue — the spoken
        // phrase table itself is a different medium and deliberately untouched here.
        SourceState.OFF,
        SourceState.READY_TO_SET_UP,
        -> StatusCue.OBSERVER_PAUSED
        SourceState.NEEDS_ATTENTION,
        SourceState.SETTING_UP,
        SourceState.PAUSED,
        -> if (current.reason == ReasonCode.UNPAIRED || current.pairing != PairingFact.PAIRED) {
            StatusCue.NOT_PAIRED
        } else {
            StatusCue.NEEDS_ATTENTION
        }
    }
