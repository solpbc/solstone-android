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
        )

        assertEquals(1, snapshot.status.pendingCount)
        assertFalse(snapshot.status.hasContentPending)
        assertTrue(snapshot.waiting.isEmpty())
    }

    private fun flagsFor(plStatus: HarnessPlStatus): Pair<Boolean, Boolean> {
        val snapshot = phoneStatusSnapshotOf(HarnessBacklogStatus(plStatus, 0, emptyList()), emptyList())
        return snapshot.status.paired to snapshot.status.online
    }

    private fun source(id: String, wish: SourceWish, state: SourceState): SourceStatus =
        SourceStatus(id, wish, state, ReasonCode.NONE)
}
