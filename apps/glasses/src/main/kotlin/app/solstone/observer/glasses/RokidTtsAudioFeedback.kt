// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.StatusCue

enum class TtsAttempt { SPOKEN, UNAVAILABLE }

fun interface RokidTtsSpeaker {
    fun speak(phrase: String): TtsAttempt
}

class RokidTtsAudioFeedback(
    private val speaker: RokidTtsSpeaker,
    private val fallback: AudioFeedback,
    private val phraseFor: (StatusCue) -> String = ::phraseFor,
) : AudioFeedback {
    @Volatile
    var degraded: Boolean = false
        private set

    override fun play(cue: StatusCue) {
        if (!degraded) {
            val result = runCatching { speaker.speak(phraseFor(cue)) }.getOrDefault(TtsAttempt.UNAVAILABLE)
            if (result == TtsAttempt.SPOKEN) return
            // UNAVAILABLE covers absence, bind errors, returned errors, timeouts, and disconnects.
            // The latch prevents repeated bind attempts; packaged fallback is never suppressed.
            degraded = true
        }
        fallback.play(cue)
    }
}
