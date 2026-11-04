// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.pl.ClaimedJournalMark
import app.solstone.core.pl.UnknownJournalSighting
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.UnknownJournalAnswer
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

    // Online only after a successful check. Falsified by reading any HTTP answer as connected: a
    // journal answering 401 or 403 has not accepted this device.
    @Test
    fun aRefusedOrUnauthorizedAnswerIsPairedButNotOnline() {
        assertEquals(true to false, flagsFor(HarnessPlStatus.Reachable(401)))
        assertEquals(true to false, flagsFor(HarnessPlStatus.Reachable(403)))
        assertEquals(true to false, flagsFor(HarnessPlStatus.Reachable(503)))
    }

    // Falsified by reading any not-online as refused, which tells an owner with a passing outage or a
    // journal answering 500 to re-pair.
    @Test
    fun onlyAStoppedCredentialOrA401Or403IsARefusal() {
        fun refused(plStatus: HarnessPlStatus) = modelFor(plStatus).refused
        assertTrue(refused(HarnessPlStatus.PairedButUnreachable("identity not paired", stopped = true)))
        assertTrue(refused(HarnessPlStatus.Reachable(401)))
        assertTrue(refused(HarnessPlStatus.Reachable(403)))

        assertFalse(refused(HarnessPlStatus.NotPaired))
        assertFalse(refused(HarnessPlStatus.PairedButUnreachable("offline")))
        assertFalse(refused(HarnessPlStatus.PairedButUnreachable("x", unknownJournal = UnknownJournalAnswer("10.0.0.1:7657"))))
        listOf(200, 400, 404, 429, 500, 502, 503).forEach { assertFalse(refused(HarnessPlStatus.Reachable(it)), "HTTP $it") }
    }

    @Test
    fun aRefusalSitsAboveOfflineAndHeldAndBelowNotPaired() {
        val refused = modelFor(HarnessPlStatus.Reachable(403))
        assertEquals(StatusPillKind.REFUSED, statusPillKind(refused, 0L))
        assertEquals(StatusPillKind.REFUSED, statusPillKind(refused.copy(awaitingMarkConfirmation = true), 0L))
        assertEquals(StatusPillKind.NOT_PAIRED, statusPillKind(refused.copy(paired = false), 0L))
        assertEquals(StatusPillKind.OFFLINE, statusPillKind(refused.copy(refused = false), 0L))
    }

    @Test
    fun aRefusalCarriesTheCountOnThePillAndAPaneThatOffersPairAgain() {
        val refused = modelFor(HarnessPlStatus.Reachable(401))
        assertEquals(PAIR_AGAIN, statusPillText(refused, 0L))
        val waiting = statusPillText(refused.copy(pendingCount = 3), 0L)
        assertTrue(waiting.startsWith(PAIR_AGAIN))
        assertTrue(waiting.contains("3"))

        val pane = requireNotNull(statusPaneCopy(refused.copy(pendingCount = 3), 0L))
        assertEquals(STATUS_REFUSED_LEAD, pane.lead)
        assertFalse(pane.count)
        assertFalse(pane.confirm)
        assertTrue(pane.pairAgain)
        assertEquals(1, pane.lines.size)
        assertFalse(requireNotNull(statusPaneCopy(modelFor(HarnessPlStatus.Reachable(503)), 0L)).pairAgain)
    }

    @Test
    fun aCheckOfARefusingJournalSaysSoNotThatItReachedIt() {
        assertEquals(STATUS_REFUSED_LEAD, checkConnectionResult(HarnessPlStatus.Reachable(401)))
        assertEquals(STATUS_REFUSED_LEAD, checkConnectionResult(HarnessPlStatus.PairedButUnreachable("x", stopped = true)))
    }

    // Falsified by any pill or pane state reading the sightings: the notice is additive.
    @Test
    fun aSightingChangesNoStateOfTheConnection() {
        val sighting = UnknownJournalSighting("10.0.0.1:7657", null, ClaimedJournalMark.NotPresented)
        listOf(
            HarnessPlStatus.NotPaired,
            HarnessPlStatus.PairedButUnreachable("offline"),
            HarnessPlStatus.Reachable(200),
        ).forEach { plStatus ->
            fun model(sightings: List<UnknownJournalSighting>) = phoneStatusSnapshotOf(
                backlog = HarnessBacklogStatus(plStatus, 1, emptyList()),
                registered = emptyList(),
                awaitingMarkConfirmation = false,
                recoveryCompleted = true,
                audioAwaitingCustody = false,
                unknownJournals = sightings,
            ).status
            val plain = model(emptyList())
            val seen = model(listOf(sighting))
            assertEquals(listOf(sighting), seen.unknownJournals)
            assertEquals(statusPillKind(plain, 0L), statusPillKind(seen, 0L))
            assertEquals(statusPillText(plain, 0L), statusPillText(seen, 0L))
            assertEquals(statusPaneCopy(plain, 0L), statusPaneCopy(seen, 0L))
        }
    }

    @Test
    fun aCheckThatAnUnknownJournalAnsweredNamesWhatAnswered() {
        assertEquals(
            unknownJournalRowText("10.0.0.1:7657"),
            checkConnectionResult(
                HarnessPlStatus.PairedButUnreachable("x", "10.0.0.1:7657", UnknownJournalAnswer("10.0.0.1:7657")),
            ),
        )
        assertEquals(
            unknownJournalRowText(null),
            checkConnectionResult(HarnessPlStatus.PairedButUnreachable("x", "10.0.0.1:7657", UnknownJournalAnswer(null))),
        )
        assertEquals(
            checkConnectionUnreached("10.0.0.1:7657"),
            checkConnectionResult(HarnessPlStatus.PairedButUnreachable("x", "10.0.0.1:7657")),
        )
        assertEquals(CHECK_CONNECTION_UNREACHED, checkConnectionResult(HarnessPlStatus.PairedButUnreachable("x")))
        assertEquals(CHECK_CONNECTION_UNREACHED, checkConnectionResult(null))
        assertEquals(CHECK_CONNECTION_REACHED, checkConnectionResult(HarnessPlStatus.Reachable(200)))
    }

    private fun modelFor(plStatus: HarnessPlStatus) = phoneStatusSnapshotOf(
        backlog = HarnessBacklogStatus(plStatus, 0, emptyList()),
        registered = emptyList(),
        awaitingMarkConfirmation = false,
        recoveryCompleted = true,
        audioAwaitingCustody = false,
    ).status

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
        )
        assertEquals(reading, snapshot.status.journalVersion)
    }

    @Test
    fun passesThroughRecoveryAndCustodyFacts() {
        val loss = PhoneGapLoss("loss", 1_700_000_000_000L, 1_700_000_000_000L, false)
        val snapshot = phoneStatusSnapshotOf(
            backlog = HarnessBacklogStatus(HarnessPlStatus.Reachable(200), pendingCount = 0, pendingSourceIds = emptyList()),
            registered = emptyList(),
            awaitingMarkConfirmation = false,
            recoveryCompleted = false,
            audioAwaitingCustody = true,
            gaps = PhoneGapStatus(
                losses = listOf(loss),
                unknownPresent = true,
                unknownUnseen = true,
                unseenUnknownKeys = listOf("draft/leaf"),
                gateOpen = true,
                technicalDetails = "complete 1",
            ),
        )
        assertFalse(snapshot.status.recoveryCompleted)
        assertTrue(snapshot.status.audioAwaitingCustody)
        assertEquals(listOf(loss), snapshot.status.gapLosses)
        assertTrue(snapshot.status.unknownPresent)
        assertTrue(snapshot.status.unknownUnseen)
        assertEquals(listOf("draft/leaf"), snapshot.status.unseenUnknownKeys)
        assertTrue(snapshot.status.gateOpen)
        assertEquals("complete 1", snapshot.status.gapTechnicalDetails)
    }

    private fun flagsFor(plStatus: HarnessPlStatus): Pair<Boolean, Boolean> {
        val snapshot = phoneStatusSnapshotOf(
            backlog = HarnessBacklogStatus(plStatus, 0, emptyList()),
            registered = emptyList(),
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
        )
        return snapshot.status.paired to snapshot.status.online
    }

    private fun source(id: String, wish: SourceWish, state: SourceState): SourceStatus =
        SourceStatus(id, wish, state, ReasonCode.NONE)
}
