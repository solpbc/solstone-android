// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import app.solstone.core.watch.courier.*

internal object WatchSessionCopy {
    fun label(state: WatchSessionReduction, copy: WatchCopy): String = when (state.state) {
        WatchSessionState.STARTING -> copy.text(WatchMessage.STARTING)
        WatchSessionState.ACTIVE -> copy.text(WatchMessage.TAKING_IT_IN)
        WatchSessionState.INTERRUPTED -> copy.text(WatchMessage.AUDIO_INTERRUPTED)
        WatchSessionState.ATTENTION -> copy.text(WatchMessage.AUDIO_NEEDS_ATTENTION)
        WatchSessionState.ENDED -> when (state.endReason) {
            WatchSessionEndReason.OWNER_STOP -> copy.text(WatchMessage.STOPPED)
            WatchSessionEndReason.PERMISSION_FAILURE -> copy.text(WatchMessage.MICROPHONE_ACCESS_NEEDED)
            WatchSessionEndReason.STORAGE_FULL -> copy.text(WatchMessage.WATCH_STORAGE_IS_FULL)
            WatchSessionEndReason.SENSOR_FAILURE -> copy.text(WatchMessage.MICROPHONE_STOPPED)
            WatchSessionEndReason.PROCESS_LOSS -> copy.text(WatchMessage.SESSION_ENDED_UNEXPECTEDLY)
            null -> copy.text(WatchMessage.READY)
        }
    }
}
