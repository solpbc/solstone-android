// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.StatusCue
import java.util.concurrent.CopyOnWriteArrayList

class FakeAudioFeedback : AudioFeedback {
    @Volatile
    var lastPlayedCue: StatusCue? = null
        private set
    val played = CopyOnWriteArrayList<StatusCue>()

    override fun play(cue: StatusCue) {
        lastPlayedCue = cue
        played.add(cue)
    }
}
