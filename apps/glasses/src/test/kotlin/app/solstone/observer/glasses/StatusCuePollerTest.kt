// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.CueSnapshot
import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.diagnostics.StatusCue
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusCuePollerTest {
    @Test
    fun playsTransitionCuesAfterBaseline() {
        val snapshots = ArrayDeque(
            listOf(
                snapshot(SourceState.OFF),
                snapshot(SourceState.ON),
                snapshot(SourceState.ON),
                snapshot(SourceState.OFF),
            ),
        )
        val audio = RecordingAudio()
        val poller = StatusCuePoller(
            snapshotProvider = { snapshots.removeFirst() },
            audio = audio,
        )

        poller.tick()
        assertNull(audio.last)
        assertEquals(emptyList(), audio.played)

        poller.tick()
        assertEquals(StatusCue.OBSERVING, audio.last)
        assertEquals(listOf(StatusCue.OBSERVING), audio.played)

        poller.tick()
        assertEquals(StatusCue.OBSERVING, audio.last)
        assertEquals(listOf(StatusCue.OBSERVING), audio.played)

        poller.tick()
        assertEquals(StatusCue.OBSERVER_PAUSED, audio.last)
        assertEquals(listOf(StatusCue.OBSERVING, StatusCue.OBSERVER_PAUSED), audio.played)
    }

    @Test
    fun relayPairedTransitionPlaysPairedOnce() {
        val snapshots = ArrayDeque(
            listOf(
                snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.UNPAIRED, PairingFact.UNPAIRED),
                snapshot(SourceState.ON, pairing = PairingFact.PAIRED),
                snapshot(SourceState.ON, pairing = PairingFact.PAIRED),
            ),
        )
        val audio = RecordingAudio()
        val poller = StatusCuePoller(
            snapshotProvider = { snapshots.removeFirst() },
            audio = audio,
        )

        poller.tick()
        assertNull(audio.last)
        assertEquals(emptyList(), audio.played)

        poller.tick()
        assertEquals(StatusCue.PAIRED, audio.last)
        assertEquals(listOf(StatusCue.PAIRED), audio.played)

        poller.tick()
        assertEquals(StatusCue.PAIRED, audio.last)
        assertEquals(listOf(StatusCue.PAIRED), audio.played)
    }

    private class RecordingAudio : AudioFeedback {
        var last: StatusCue? = null
        val played = mutableListOf<StatusCue>()

        override fun play(cue: StatusCue) {
            last = cue
            played.add(cue)
        }
    }

    private fun snapshot(
        state: SourceState,
        reason: ReasonCode = ReasonCode.NONE,
        pairing: PairingFact = PairingFact.PAIRED,
        lastFailureAt: Long? = null,
    ) = CueSnapshot(state = state, reason = reason, pairing = pairing, lastFailureAt = lastFailureAt)
}
