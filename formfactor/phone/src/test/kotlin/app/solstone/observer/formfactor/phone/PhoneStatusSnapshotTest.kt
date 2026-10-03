// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneStatusSnapshotTest {
    @Test
    fun mapsEveryPlStatus() {
        assertEquals(false to false, flagsFor(HarnessPlStatus.NotPaired))
        assertEquals(true to false, flagsFor(HarnessPlStatus.PairedButUnreachable("offline")))
        assertEquals(true to true, flagsFor(HarnessPlStatus.Reachable(200)))
    }

    @Test
    fun usesRegisteredOrderThenSortedOrphans() {
        val audio = source("audio", SourceWish.On, SourceState.ON)
        val location = source("location", SourceWish.On, SourceState.ON)
        val snapshot = phoneStatusSnapshotOf(
            backlog = HarnessBacklogStatus(
                plStatus = HarnessPlStatus.Reachable(200),
                pendingCount = 3,
                pendingSourceIds = listOf("z-orphan", "location", "a-orphan", "audio", "audio"),
            ),
            registered = listOf(audio, location),
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )

        assertEquals(listOf("audio", "location", "a-orphan", "z-orphan"), snapshot.waiting.map { it.sourceId })
        assertTrue(snapshot.waiting[0] === audio)
        assertTrue(snapshot.waiting[1] === location)
        assertEquals(SourceWish.Off, snapshot.waiting[2].wish)
        assertEquals(SourceState.OFF, snapshot.waiting[2].state)
        assertEquals(ReasonCode.NONE, snapshot.waiting[2].reason)
        assertTrue(snapshot.status.hasContentPending)
        assertEquals(WristShare.Unknown, snapshot.status.wrist)
    }

    @Test
    fun preservesZeroFilePendingFact() {
        val snapshot = phoneStatusSnapshotOf(
            backlog = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), pendingCount = 1, pendingSourceIds = emptyList()),
            registered = emptyList(),
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )

        assertEquals(1, snapshot.status.pendingCount)
        assertFalse(snapshot.status.hasContentPending)
        assertTrue(snapshot.waiting.isEmpty())
    }

    @Test
    fun passesThroughJournalVersion() {
        val reading = app.solstone.core.pl.JournalVersionReading("1.0.0", app.solstone.core.pl.JournalVersionFreshness.CURRENT)
        val snapshot = phoneStatusSnapshotOf(
            backlog = HarnessBacklogStatus(
                plStatus = HarnessPlStatus.Reachable(200),
                pendingCount = 0,
                pendingSourceIds = emptyList(),
                journalVersion = reading,
            ),
            registered = emptyList(),
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )
        assertEquals(reading, snapshot.status.journalVersion)
    }

    @Test
    fun passesThroughRecoveryAndCustodyFacts() {
        val snapshot = phoneStatusSnapshotOf(
            backlog = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), pendingCount = 0, pendingSourceIds = emptyList()),
            registered = emptyList(),
            awaitingMarkConfirmation = false,
            recoveryCompleted = false,
            audioAwaitingCustody = true,
            unresolvedAudioInterruption = true,
        )
        assertFalse(snapshot.status.recoveryCompleted)
        assertTrue(snapshot.status.audioAwaitingCustody)
        assertTrue(snapshot.status.unresolvedAudioInterruption)
    }

    private fun flagsFor(plStatus: HarnessPlStatus): Pair<Boolean, Boolean> {
        val snapshot = phoneStatusSnapshotOf(
            backlog = HarnessBacklogStatus(plStatus, 0, emptyList()),
            registered = emptyList(),
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            unresolvedAudioInterruption = false,
        )
        return snapshot.status.paired to snapshot.status.online
    }

    private fun source(id: String, wish: SourceWish, state: SourceState): SourceStatus =
        SourceStatus(id, wish, state, ReasonCode.NONE)
}
