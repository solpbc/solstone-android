// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import android.media.MediaPlayer
import app.solstone.core.diagnostics.StatusCue

class RealAudioFeedback(private val context: Context) : AudioFeedback {
    private var current: MediaPlayer? = null

    @Synchronized
    override fun play(cue: StatusCue) {
        val resId = rawResFor(cue)
        current?.let { runCatching { it.reset() }; runCatching { it.release() } }
        current = MediaPlayer.create(context, resId)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }
}
