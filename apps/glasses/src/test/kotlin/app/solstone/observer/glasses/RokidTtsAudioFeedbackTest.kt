// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.StatusCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RokidTtsAudioFeedbackTest {
    @Test
    fun spokenDoesNotPlayFallbackOrDegrade() {
        val fallback = RecordingAudioFeedback()
        val speaker = ProgrammableSpeaker(TtsAttempt.SPOKEN)
        val audio = RokidTtsAudioFeedback(speaker, fallback)

        audio.play(StatusCue.OBSERVING)

        assertEquals(emptyList(), fallback.played)
        assertFalse(audio.degraded)
        assertEquals(1, speaker.calls)
    }

    @Test
    fun unavailableFallsBackWithSameCueAndDegrades() {
        val fallback = RecordingAudioFeedback()
        val speaker = ProgrammableSpeaker(TtsAttempt.UNAVAILABLE)
        val audio = RokidTtsAudioFeedback(speaker, fallback)

        audio.play(StatusCue.NOT_PAIRED)

        assertEquals(listOf(StatusCue.NOT_PAIRED), fallback.played)
        assertTrue(audio.degraded)
        assertEquals(1, speaker.calls)
    }

    @Test
    fun thrownSpeakerFailureFallsBack() {
        val fallback = RecordingAudioFeedback()
        val speaker = ProgrammableSpeaker(error = IllegalStateException("missing service"))
        val audio = RokidTtsAudioFeedback(speaker, fallback)

        audio.play(StatusCue.NEEDS_ATTENTION)

        assertEquals(listOf(StatusCue.NEEDS_ATTENTION), fallback.played)
        assertTrue(audio.degraded)
        assertEquals(1, speaker.calls)
    }

    @Test
    fun spokenThenUnavailableFallsBackAndLatches() {
        val fallback = RecordingAudioFeedback()
        val speaker = ProgrammableSpeaker(TtsAttempt.SPOKEN, TtsAttempt.UNAVAILABLE)
        val audio = RokidTtsAudioFeedback(speaker, fallback)

        audio.play(StatusCue.OBSERVING)
        audio.play(StatusCue.SYNC_FAILED)

        assertEquals(listOf(StatusCue.SYNC_FAILED), fallback.played)
        assertTrue(audio.degraded)
        assertEquals(2, speaker.calls)
    }

    @Test
    fun degradedNeverCallsSpeakerAgainAndNeverSuppressesFallback() {
        val fallback = RecordingAudioFeedback()
        val speaker = ProgrammableSpeaker(TtsAttempt.UNAVAILABLE)
        val audio = RokidTtsAudioFeedback(speaker, fallback)

        audio.play(StatusCue.OBSERVING)
        audio.play(StatusCue.OBSERVER_PAUSED)
        audio.play(StatusCue.NEEDS_ATTENTION)

        assertEquals(1, speaker.calls)
        assertEquals(
            listOf(StatusCue.OBSERVING, StatusCue.OBSERVER_PAUSED, StatusCue.NEEDS_ATTENTION),
            fallback.played,
        )
        assertTrue(audio.degraded)
    }

    private class RecordingAudioFeedback : AudioFeedback {
        val played = mutableListOf<StatusCue>()

        override fun play(cue: StatusCue) {
            played.add(cue)
        }
    }

    private class ProgrammableSpeaker(
        private vararg val attempts: TtsAttempt,
        private val error: RuntimeException? = null,
    ) : RokidTtsSpeaker {
        var calls = 0
            private set

        override fun speak(phrase: String): TtsAttempt {
            calls += 1
            error?.let { throw it }
            return attempts.getOrElse(calls - 1) { attempts.last() }
        }
    }
}
