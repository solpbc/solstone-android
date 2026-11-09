// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import app.solstone.core.watch.courier.*

internal object WatchSessionCopy {
    fun label(state: WatchSessionReduction): String = when (state.state) {
        WatchSessionState.STARTING -> "starting"
        WatchSessionState.ACTIVE -> "taking it in"
        WatchSessionState.INTERRUPTED -> "audio interrupted"
        WatchSessionState.ATTENTION -> "audio needs attention"
        WatchSessionState.ENDED -> when (state.endReason) {
            WatchSessionEndReason.OWNER_STOP -> "you stopped the session"
            WatchSessionEndReason.PERMISSION_FAILURE -> "microphone permission needed"
            WatchSessionEndReason.STORAGE_FULL -> "watch storage is full"
            WatchSessionEndReason.SENSOR_FAILURE -> "microphone stopped"
            WatchSessionEndReason.PROCESS_LOSS -> "the session ended when the app closed or the watch restarted"
            null -> "ready on your watch"
        }
    }
}
