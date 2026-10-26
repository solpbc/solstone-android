// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.spool.GAP_HEADLINE
import app.solstone.core.spool.GAP_TODAY
import app.solstone.core.spool.GAP_UNDATED
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneStatusModelTest {
    @Test
    fun pillOrderPutsWaitingAheadOfEitherNotice() {
        val now = at(2026, 10, 25, 15, 0)
        val gap = ready(
            losses = listOf(loss("a", now, now)),
            unknownPresent = true,
            unknownUnseen = true,
            gateOpen = true,
        )
        assertEquals("not paired", statusPillText(gap.copy(paired = false), now, ny))
        assertEquals(StatusPillKind.NOT_PAIRED, statusPillKind(gap.copy(paired = false), now, ny))
        assertEquals("offline · 0 waiting", statusPillText(gap.copy(online = false), now, ny))
        assertEquals(StatusPillKind.OFFLINE, statusPillKind(gap.copy(online = false), now, ny))
        assertEquals("confirm the mark", statusPillText(gap.copy(awaitingMarkConfirmation = true), now, ny))
        assertEquals(
            "confirm the mark · 2 waiting",
            statusPillText(gap.copy(awaitingMarkConfirmation = true, pendingCount = 2, hasContentPending = true), now, ny),
        )
        assertEquals(StatusPillKind.AWAITING_MARK_CONFIRMATION, statusPillKind(gap.copy(awaitingMarkConfirmation = true), now, ny))
        assertEquals("4 syncing", statusPillText(gap.copy(pendingCount = 4, hasContentPending = true), now, ny))
        assertEquals(StatusPillKind.SYNCING, statusPillKind(gap.copy(pendingCount = 4, hasContentPending = true), now, ny))
        assertEquals(STATUS_WAITING_TO_SYNC_LEAD, statusPillText(gap.copy(audioAwaitingCustody = true), now, ny))
        assertEquals(StatusPillKind.SYNCING, statusPillKind(gap.copy(audioAwaitingCustody = true), now, ny))
        assertEquals("0 syncing", statusPillText(gap.copy(recoveryCompleted = false), now, ny))
        assertEquals(StatusPillKind.SYNCING, statusPillKind(gap.copy(recoveryCompleted = false), now, ny))
        assertEquals(STATUS_UNKNOWN_RECOVERY_LEAD, statusPillText(gap, now, ny))
        assertEquals(StatusPillKind.NOTICE, statusPillKind(gap, now, ny))
        val gapOnly = gap.copy(unknownUnseen = false, unseenUnknownKeys = emptyList())
        assertEquals(GAP_HEADLINE, statusPillText(gapOnly, now, ny))
        assertEquals(StatusPillKind.NOTICE, statusPillKind(gapOnly, now, ny))
        assertEquals("connected", statusPillText(ready(gateOpen = true), now, ny))
        assertEquals(StatusPillKind.CONNECTED, statusPillKind(ready(gateOpen = true), now, ny))
    }

    @Test
    fun aClosedGateShowsNeitherNotice() {
        val now = at(2026, 10, 25, 15, 0)
        val closed = ready(
            losses = listOf(loss("a", now, now)),
            unknownPresent = true,
            unknownUnseen = true,
            gateOpen = false,
        )
        assertEquals("connected", statusPillText(closed, now, ny))
        assertEquals(StatusPillKind.CONNECTED, statusPillKind(closed, now, ny))
        assertNull(gapDaySentence(closed, now, ny))
        assertEquals(emptyList(), audioCustodyLines(closed, now, ny))
    }

    @Test
    fun failedCheckNamesAnAddressOnlyWhenOneWasDialed() {
        assertEquals("couldn't reach your journal at 192.0.2.1:7657", checkConnectionUnreached("192.0.2.1:7657"))
        // A check that never dialed (a missing credential, say) keeps the plain words.
        assertEquals("couldn't reach your journal", checkConnectionUnreached(null))
        assertEquals("couldn't reach your journal", checkConnectionUnreached(""))
    }

    @Test
    fun fourPillStatesRenderQuotedCopy() {
        assertEquals(
            "connected",
            statusPillText(PhoneStatusModel(paired = true, online = true, pendingCount = 0, hasContentPending = false, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false)),
        )
        assertEquals(
            "3 syncing",
            statusPillText(PhoneStatusModel(paired = true, online = true, pendingCount = 3, hasContentPending = true, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false)),
        )
        assertEquals(
            "offline · 2 waiting",
            statusPillText(PhoneStatusModel(paired = true, online = false, pendingCount = 2, hasContentPending = true, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false)),
        )
        assertEquals(
            "not paired",
            statusPillText(PhoneStatusModel(paired = false, online = true, pendingCount = 4, hasContentPending = true, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false)),
        )
    }

    @Test
    fun awaitingMarkConfirmationPrecedence() {
        val beatsConnected = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = true,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
        )
        assertEquals(StatusPillKind.AWAITING_MARK_CONFIRMATION, statusPillKind(beatsConnected))

        val beatsSyncing = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 5,
            hasContentPending = true,
            awaitingMarkConfirmation = true,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
        )
        assertEquals(StatusPillKind.AWAITING_MARK_CONFIRMATION, statusPillKind(beatsSyncing))

        val offlineBeatsAwaiting = PhoneStatusModel(
            paired = true,
            online = false,
            pendingCount = 2,
            hasContentPending = true,
            awaitingMarkConfirmation = true,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
        )
        assertEquals(StatusPillKind.OFFLINE, statusPillKind(offlineBeatsAwaiting))
        assertEquals("offline · 2 waiting", statusPillText(offlineBeatsAwaiting))

        val notPairedModel = PhoneStatusModel(
            paired = false,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = true,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
        )
        assertEquals(StatusPillKind.NOT_PAIRED, statusPillKind(notPairedModel))
        assertEquals("not paired", statusPillText(notPairedModel))
    }

    @Test
    fun pendingFlagIsNotSummedIntoCount() {
        val model = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 3,
            hasContentPending = true,
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
        )
        assertEquals("3 syncing", statusPillText(model))
        assertFalse(statusPillText(model).contains("4"))
    }

    @Test
    fun retiredOfflineFormDoesNotAppear() {
        val text = statusPillText(
            PhoneStatusModel(paired = true, online = false, pendingCount = 38, hasContentPending = true, awaitingMarkConfirmation = false, recoveryCompleted = true, audioAwaitingCustody = false),
        )
        assertEquals("offline · 38 waiting", text)
        assertFalse(text.contains("38 offline"))
    }

    @Test
    fun custodyStillWaitsAndTheGapStaysInTheLines() {
        val now = at(2026, 10, 25, 15, 0)
        val custody = ready(audioAwaitingCustody = true)
        assertEquals(StatusPillKind.SYNCING, statusPillKind(custody, now, ny))
        assertEquals(STATUS_WAITING_TO_SYNC_LEAD, statusPillText(custody, now, ny))
        assertEquals(
            listOf(STATUS_WAITING_TO_SYNC_LEAD, STATUS_ON_THIS_DEVICE),
            audioCustodyLines(custody, now, ny),
        )

        val both = ready(losses = listOf(loss("a", now, now)), gateOpen = true, audioAwaitingCustody = true)
        assertEquals(StatusPillKind.SYNCING, statusPillKind(both, now, ny))
        assertEquals(STATUS_WAITING_TO_SYNC_LEAD, statusPillText(both, now, ny))
        assertEquals(
            listOf(GAP_HEADLINE, GAP_TODAY, STATUS_WAITING_TO_SYNC_LEAD, STATUS_ON_THIS_DEVICE),
            audioCustodyLines(both, now, ny),
        )

        val recovering = ready(recoveryCompleted = false, losses = listOf(loss("a", now, now)), gateOpen = true)
        assertEquals(StatusPillKind.SYNCING, statusPillKind(recovering, now, ny))
        assertEquals("0 syncing", statusPillText(recovering, now, ny))
        assertEquals(listOf(GAP_HEADLINE, GAP_TODAY), audioCustodyLines(recovering, now, ny))
    }

    @Test
    fun calendarHeldLeavesThePillConnectedBesideTheGap() {
        val now = at(2026, 10, 25, 15, 0)
        val held = ready(
            losses = listOf(loss("a", now, now)),
            gateOpen = true,
            calendarHeldForJournal = true,
        )
        assertEquals("connected", statusPillText(held, now, ny))
        assertEquals(StatusPillKind.CONNECTED, statusPillKind(held, now, ny))
        val pane = statusPaneCopy(held, now, ny)!!
        assertEquals("journal needs an update", pane.lead)
        assertTrue(GAP_HEADLINE in pane.lines)
        assertTrue(GAP_TODAY in pane.lines)
        assertFalse("all caught up" == pane.lead || "all caught up" in pane.lines)
    }

    @Test
    fun dayLineFollowsTheClockWithoutPublish() {
        val found = at(2026, 10, 25, 15, 0)
        val model = ready(losses = listOf(loss("a", found, found)), gateOpen = true)
        assertEquals(GAP_HEADLINE, statusPillText(model, found, ny))
        assertEquals(GAP_TODAY, gapDaySentence(model, found, ny))
        val later = at(2026, 11, 2, 15, 0)
        assertEquals("connected", statusPillText(model, later, ny))
        assertEquals(StatusPillKind.CONNECTED, statusPillKind(model, later, ny))
        assertNull(gapDaySentence(model, later, ny))
    }

    @Test
    fun captureNineDaysBeforeALossFoundTodayIsUndated() {
        val found = at(2026, 10, 25, 15, 0)
        val captured = at(2026, 10, 16, 15, 0)
        val model = ready(losses = listOf(loss("a", captured, found)), gateOpen = true)
        assertEquals(GAP_UNDATED, gapDaySentence(model, found, ny))
        assertEquals(GAP_HEADLINE, statusPillText(model, found, ny))
    }

    @Test
    fun unseenLossOutsideTheWindowDoesNotChangeTheLine() {
        val now = at(2026, 10, 25, 15, 0)
        val outside = at(2026, 10, 17, 15, 0)
        val onlyOutside = ready(losses = listOf(loss("old", outside, outside)), gateOpen = true)
        assertNull(gapDaySentence(onlyOutside, now, ny))
        assertEquals("connected", statusPillText(onlyOutside, now, ny))
        val both = ready(
            losses = listOf(loss("today", now, now), loss("old", now, outside)),
            gateOpen = true,
        )
        assertEquals(GAP_TODAY, gapDaySentence(both, now, ny))
    }

    @Test
    fun daylightSavingBoundaryStaysInsideTheSevenDateWindow() {
        val found = at(2026, 11, 1, 0, 30)
        val now = at(2026, 11, 7, 0, 30)
        val model = ready(losses = listOf(loss("dst", found, found)), gateOpen = true)
        assertEquals(GAP_HEADLINE, statusPillText(model, now, ny))
        assertEquals("some of what you shared on sunday didn't reach it.", gapDaySentence(model, now, ny))
        assertEquals(at(2026, 11, 8, 0, 0), gapAlarmAt(model, now, ny))
    }

    @Test
    fun supportCopyDropsTheGapAndKeepsTheConnectionWords() {
        val now = at(2026, 10, 25, 15, 0)
        val gap = ready(
            losses = listOf(loss("a", now, now)),
            unknownPresent = true,
            unknownUnseen = true,
            gateOpen = true,
            audioAwaitingCustody = true,
            pendingCount = 3,
            awaitingMarkConfirmation = true,
        )
        val cleared = gap.clearedForSupport()
        assertEquals(gap.audioAwaitingCustody, cleared.audioAwaitingCustody)
        assertEquals(gap.recoveryCompleted, cleared.recoveryCompleted)
        assertEquals(gap.pendingCount, cleared.pendingCount)
        assertEquals(gap.awaitingMarkConfirmation, cleared.awaitingMarkConfirmation)
        assertTrue(cleared.gapLosses.isEmpty())
        assertFalse(cleared.unknownPresent)
        assertFalse(cleared.unknownUnseen)
        assertFalse(cleared.gateOpen)
        assertEquals("confirm the mark · 3 waiting", supportOf(cleared))
        assertEquals(
            "connected",
            supportOf(
                ready(
                    losses = listOf(loss("a", now, now)),
                    unknownPresent = true,
                    unknownUnseen = true,
                    gateOpen = true,
                ).clearedForSupport(),
            ),
        )
        assertEquals("offline · 2 waiting", supportOf(ready(online = false, pendingCount = 2).clearedForSupport()))
        assertEquals("not paired", supportState(PhoneDefaultDetailStatus.Unpaired))
        assertEquals("4 syncing", supportOf(ready(pendingCount = 4).clearedForSupport()))
        assertEquals("confirm the mark", supportOf(ready(awaitingMarkConfirmation = true).clearedForSupport()))
        assertEquals(STATUS_WAITING_TO_SYNC_LEAD, supportOf(ready(audioAwaitingCustody = true).clearedForSupport()))
        assertEquals("0 syncing", supportOf(ready(recoveryCompleted = false).clearedForSupport()))
        val reported = supportOf(cleared)
        assertFalse(reported.contains(GAP_HEADLINE))
        assertFalse(reported.contains(STATUS_UNKNOWN_RECOVERY_LEAD))
    }

    @Test
    fun oneAlarmUsesTheEarliestWindowEndAndCancelsOtherwise() {
        val now = at(2026, 10, 25, 15, 0)
        val earlier = at(2026, 10, 23, 9, 0)
        val model = ready(
            losses = listOf(loss("late", now, now), loss("early", earlier, earlier)),
            gateOpen = true,
        )
        val scheduled = mutableListOf<Long?>()
        syncGapAlarm(GapWindowScheduler { scheduled += it }, model, now, ny)
        assertEquals(at(2026, 10, 30, 0, 0), scheduled.single())
        syncGapAlarm(GapWindowScheduler { scheduled += it }, model.copy(gapLosses = model.gapLosses.map { it.copy(seen = true) }), now, ny)
        syncGapAlarm(GapWindowScheduler { scheduled += it }, model.copy(calendarHeldForJournal = true), now, ny)
        syncGapAlarm(GapWindowScheduler { scheduled += it }, ready(), now, ny)
        assertEquals(listOf(at(2026, 10, 30, 0, 0), null, null, null), scheduled)
    }

    @Test
    fun nothingWaitingAndAnUnseenGapReplacesAllCaughtUp() {
        val now = at(2026, 10, 25, 15, 0)
        val pane = statusPaneCopy(ready(losses = listOf(loss("a", now, now)), gateOpen = true), now, ny)!!
        assertEquals(GAP_HEADLINE, pane.lead)
        assertEquals(listOf(GAP_TODAY, "connected"), pane.lines)
        assertFalse("all caught up" in pane.lines || pane.lead == "all caught up")
        assertFalse("everything is in your journal" in pane.lines)
    }

    @Test
    fun seenUnknownNoticeEndsWithoutAssertingDeliveryAndANewItemShowsAgain() {
        val seen = ready(unknownPresent = true, unknownUnseen = false, gateOpen = true)
        assertTrue(audioCustodyLines(seen).isEmpty())
        val pane = statusPaneCopy(seen)!!
        assertFalse(pane.lead == STATUS_UNKNOWN_RECOVERY_LEAD)
        assertFalse(pane.lines.contains(STATUS_UNKNOWN_RECOVERY_LEAD))
        assertFalse(pane.lines.contains("everything is in your journal"))
        val new = seen.copy(unknownUnseen = true, unseenUnknownKeys = listOf("new"))
        assertEquals(STATUS_UNKNOWN_RECOVERY_LEAD, statusPaneCopy(new)!!.lead)
        assertEquals(STATUS_UNKNOWN_RECOVERY_LEAD, audioCustodyLines(new).first())
    }

    @Test
    fun caughtUpCopyIsUnchangedWhenNothingIsUnseen() {
        val now = at(2026, 10, 25, 15, 0)
        val pane = statusPaneCopy(ready(gateOpen = true), now, ny)!!
        assertEquals("all caught up", pane.lead)
        assertEquals(listOf("everything is in your journal"), pane.lines)
        val imports = statusPaneCopy(ready(), now, ny, importsNeedYou = true)!!
        assertEquals("imports need you", imports.lead)
        assertEquals(listOf("open import to see why"), imports.lines)
        val held = statusPaneCopy(ready(calendarHeldForJournal = true), now, ny)!!
        assertEquals("journal needs an update", held.lead)
    }

    @Test
    fun aGapRendersBesideACountAndAMarkLine() {
        val now = at(2026, 10, 25, 15, 0)
        val count = statusPaneCopy(
            ready(losses = listOf(loss("a", now, now)), gateOpen = true, pendingCount = 3),
            now,
            ny,
        )!!
        assertTrue(count.count)
        assertNull(count.lead)
        assertTrue("syncing to your journal…" in count.lines)
        assertTrue(GAP_HEADLINE in count.lines)
        assertTrue(GAP_TODAY in count.lines)
        assertFalse("all caught up" in count.lines)
        val marked = statusPaneCopy(
            ready(losses = listOf(loss("a", now, now)), gateOpen = true, awaitingMarkConfirmation = true),
            now,
            ny,
        )!!
        assertEquals("waiting for you to confirm your journal's mark", marked.lead)
        assertTrue(marked.confirm)
        assertTrue(GAP_HEADLINE in marked.lines)
    }

    @Test
    fun journalVersionDisplayTextFormatsExpectedCopy() {
        assertEquals("unknown", journalVersionDisplayText(null))
        assertEquals(
            "unknown",
            journalVersionDisplayText(
                app.solstone.core.pl.JournalVersionReading(
                    version = "0.9.1",
                    freshness = app.solstone.core.pl.JournalVersionFreshness.NEVER_OBSERVED,
                ),
            ),
        )
        assertEquals(
            "0.9.1 (last known)",
            journalVersionDisplayText(
                app.solstone.core.pl.JournalVersionReading(
                    version = "0.9.1",
                    freshness = app.solstone.core.pl.JournalVersionFreshness.LAST_KNOWN,
                ),
            ),
        )
        assertEquals(
            "0.9.1",
            journalVersionDisplayText(
                app.solstone.core.pl.JournalVersionReading(
                    version = "0.9.1",
                    freshness = app.solstone.core.pl.JournalVersionFreshness.CURRENT,
                ),
            ),
        )
    }

    private val ny: ZoneId = ZoneId.of("America/New_York")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ny).toInstant().toEpochMilli()

    private fun loss(id: String, captured: Long?, found: Long, seen: Boolean = false) =
        PhoneGapLoss(id, captured, found, seen)

    private fun ready(
        losses: List<PhoneGapLoss> = emptyList(),
        unknownPresent: Boolean = false,
        unknownUnseen: Boolean = false,
        gateOpen: Boolean = false,
        audioAwaitingCustody: Boolean = false,
        recoveryCompleted: Boolean = true,
        pendingCount: Int = 0,
        awaitingMarkConfirmation: Boolean = false,
        online: Boolean = true,
        paired: Boolean = true,
        calendarHeldForJournal: Boolean = false,
    ) = PhoneStatusModel(
        paired = paired,
        online = online,
        pendingCount = pendingCount,
        hasContentPending = pendingCount > 0,
        awaitingMarkConfirmation = awaitingMarkConfirmation,
        recoveryCompleted = recoveryCompleted,
        audioAwaitingCustody = audioAwaitingCustody,
        gapLosses = losses,
        unknownPresent = unknownPresent,
        unknownUnseen = unknownUnseen,
        unseenUnknownKeys = if (unknownUnseen) listOf("draft") else emptyList(),
        gateOpen = gateOpen,
        calendarHeldForJournal = calendarHeldForJournal,
    )

    private fun supportOf(model: PhoneStatusModel): String =
        supportState(PhoneDefaultDetailStatus.Paired(PhoneStatusSnapshot(model, emptyList())))
}
