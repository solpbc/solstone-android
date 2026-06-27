// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.diagnostics

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusCueTest {
    @Test
    fun firstPollEstablishesBaselineWithoutCue() {
        assertNull(cueFor(null, snapshot(SourceState.ON)))
    }

    @Test
    fun emitsEachPhase1aCue() {
        assertEquals(
            StatusCue.PAIRED,
            cueFor(snapshot(pairing = PairingFact.UNPAIRED), snapshot(pairing = PairingFact.PAIRED)),
        )
        assertEquals(StatusCue.OBSERVING, cueFor(snapshot(SourceState.OFF), snapshot(SourceState.ON)))
        assertEquals(StatusCue.OBSERVER_PAUSED, cueFor(snapshot(SourceState.ON), snapshot(SourceState.OFF)))
        assertEquals(
            StatusCue.NEEDS_ATTENTION,
            cueFor(snapshot(SourceState.ON), snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.PROVIDER_SILENT)),
        )
        assertEquals(
            StatusCue.NOT_PAIRED,
            cueFor(
                snapshot(SourceState.ON),
                snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.UNPAIRED, PairingFact.UNPAIRED),
            ),
        )
        assertEquals(StatusCue.SYNC_FAILED, cueFor(snapshot(), snapshot(lastFailureAt = 1L)))
    }

    @Test
    fun debouncesEqualConsecutiveSnapshots() {
        val current = snapshot(SourceState.ON)
        assertEquals(StatusCue.OBSERVING, cueFor(snapshot(SourceState.OFF), current))
        assertNull(cueFor(current, current))

        val attention = snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.PROVIDER_SILENT)
        assertNull(cueFor(attention, attention))
    }

    @Test
    fun reasonChangeReannouncesWithinNeedsAttention() {
        assertEquals(
            StatusCue.NEEDS_ATTENTION,
            cueFor(
                snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.UNPAIRED),
                snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.PROVIDER_SILENT),
            ),
        )
        assertNull(
            cueFor(
                snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.PROVIDER_SILENT),
                snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.PROVIDER_SILENT),
            ),
        )
    }

    @Test
    fun pairingFlipWinsOverEnterOnInSameTick() {
        assertEquals(
            StatusCue.PAIRED,
            cueFor(
                snapshot(SourceState.OFF, pairing = PairingFact.UNPAIRED),
                snapshot(SourceState.ON, pairing = PairingFact.PAIRED),
            ),
        )
    }

    @Test
    fun observingAndPairedAreGapHonest() {
        assertNull(cueFor(snapshot(SourceState.OFF), snapshot(SourceState.SETTING_UP)))
        assertNull(cueFor(snapshot(SourceState.OFF), snapshot(SourceState.PAUSED)))
        assertNull(cueFor(snapshot(pairing = PairingFact.PAIRED), snapshot(pairing = PairingFact.PAIRED)))
    }

    @Test
    fun syncFailedFiresOncePerNewTimestamp() {
        assertEquals(StatusCue.SYNC_FAILED, cueFor(snapshot(lastFailureAt = null), snapshot(lastFailureAt = 1L)))
        assertNull(cueFor(snapshot(lastFailureAt = 1L), snapshot(lastFailureAt = 1L)))
        assertEquals(StatusCue.SYNC_FAILED, cueFor(snapshot(lastFailureAt = 1L), snapshot(lastFailureAt = 2L)))
    }

    @Test
    fun mapsCurrentStatusForButton() {
        assertEquals(StatusCue.OBSERVING, statusCueFor(snapshot(SourceState.ON)))
        assertEquals(StatusCue.OBSERVER_PAUSED, statusCueFor(snapshot(SourceState.OFF)))
        assertEquals(
            StatusCue.NOT_PAIRED,
            statusCueFor(snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.UNPAIRED, PairingFact.UNPAIRED)),
        )
        assertEquals(
            StatusCue.NEEDS_ATTENTION,
            statusCueFor(snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.PROVIDER_SILENT)),
        )
    }

    @Test
    fun pairedCurrentStatusNeverMapsToNotPaired() {
        assertEquals(
            StatusCue.NEEDS_ATTENTION,
            statusCueFor(snapshot(SourceState.NEEDS_ATTENTION, ReasonCode.PROVIDER_SILENT, PairingFact.PAIRED)),
        )
    }

    private fun snapshot(
        state: SourceState = SourceState.OFF,
        reason: ReasonCode = ReasonCode.NONE,
        pairing: PairingFact = PairingFact.PAIRED,
        lastFailureAt: Long? = null,
    ) = CueSnapshot(state = state, reason = reason, pairing = pairing, lastFailureAt = lastFailureAt)
}
