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
            rawResFor = { cue -> cue.ordinal },
        )

        poller.tick()
        assertNull(audio.last)
        assertEquals(emptyList(), audio.played)

        poller.tick()
        assertEquals(StatusCue.OBSERVING.ordinal, audio.last)
        assertEquals(listOf(StatusCue.OBSERVING.ordinal), audio.played)

        poller.tick()
        assertEquals(StatusCue.OBSERVING.ordinal, audio.last)
        assertEquals(listOf(StatusCue.OBSERVING.ordinal), audio.played)

        poller.tick()
        assertEquals(StatusCue.OBSERVER_PAUSED.ordinal, audio.last)
        assertEquals(listOf(StatusCue.OBSERVING.ordinal, StatusCue.OBSERVER_PAUSED.ordinal), audio.played)
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
            rawResFor = { cue -> cue.ordinal },
        )

        poller.tick()
        assertNull(audio.last)
        assertEquals(emptyList(), audio.played)

        poller.tick()
        assertEquals(StatusCue.PAIRED.ordinal, audio.last)
        assertEquals(listOf(StatusCue.PAIRED.ordinal), audio.played)

        poller.tick()
        assertEquals(StatusCue.PAIRED.ordinal, audio.last)
        assertEquals(listOf(StatusCue.PAIRED.ordinal), audio.played)
    }

    private class RecordingAudio : AudioFeedback {
        var last: Int? = null
        val played = mutableListOf<Int>()

        override fun play(resId: Int) {
            last = resId
            played.add(resId)
        }
    }

    private fun snapshot(
        state: SourceState,
        reason: ReasonCode = ReasonCode.NONE,
        pairing: PairingFact = PairingFact.PAIRED,
        lastFailureAt: Long? = null,
    ) = CueSnapshot(state = state, reason = reason, pairing = pairing, lastFailureAt = lastFailureAt)
}
