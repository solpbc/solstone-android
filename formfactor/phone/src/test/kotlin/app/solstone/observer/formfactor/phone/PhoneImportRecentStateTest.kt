// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhoneImportRecentStateTest {
    @Test
    fun whatCanStillMoveIsExplainedByTheWaitingReason() {
        ImportWaiting.values().forEach { reason ->
            assertEquals(ImportRecentState.valueOf(reason.name), importRecentState(reason, pendingCount = 1, attentionCount = 1, deliveredToCurrentJournal = true))
        }
    }

    @Test
    fun whenAllThatIsLeftNeedsTheOwnerItSaysSoRatherThanWaiting() {
        assertEquals(ImportRecentState.NEEDS_YOU, importRecentState(ImportWaiting.WAITING, pendingCount = 0, attentionCount = 2, deliveredToCurrentJournal = true))
    }

    @Test
    fun deliveredIsSaidOnlyForTheJournalThisDeviceIsPairedWithNow() {
        assertEquals(ImportRecentState.DELIVERED, importRecentState(ImportWaiting.WAITING, 0, 0, deliveredToCurrentJournal = true))
        assertEquals(ImportRecentState.NOTHING_YET, importRecentState(ImportWaiting.WAITING, 0, 0, deliveredToCurrentJournal = false))
    }

    @Test
    fun nothingWaitingAndUnpairedIsNotADeliveryClaim() {
        assertEquals(ImportRecentState.NOTHING_YET, importRecentState(ImportWaiting.UNPAIRED, 0, 0, deliveredToCurrentJournal = false))
        assertEquals(ImportRecentState.UNPAIRED, importRecentState(ImportWaiting.UNPAIRED, 1, 0, deliveredToCurrentJournal = false))
    }

    @Test
    fun onlyWhatWaitsOnTheOwnerKeepsStatusFromReadingCaughtUp() {
        val needsOwner = ImportRecentState.values().filter { it.needsOwner }.toSet()
        assertEquals(setOf(ImportRecentState.NEEDS_YOU, ImportRecentState.JOURNAL_TOO_OLD), needsOwner)
    }

    @Test
    fun aRefusedPairingAndAnIncompleteOneReadDifferently() {
        assertNotEquals(recentText(ImportRecentState.PAIRING_REFUSED), recentText(ImportRecentState.PAIRING_INCOMPLETE))
        assertTrue(recentText(ImportRecentState.PAIRING_REFUSED).contains("pair again"))
        assertTrue(recentText(ImportRecentState.PAIRING_INCOMPLETE).contains("connect a journal"))
    }

    @Test
    fun nothingSavedIsSaidAsNothingRatherThanZero() {
        assertEquals("nothing was saved on this device.", intakeText(PhoneIntakeLine.Finished(0, emptyList())))
        assertEquals("saved on this device: 2", intakeText(PhoneIntakeLine.Finished(2, emptyList())))
    }

    @Test
    fun aShareWithNothingAddableOffersOnlyClose() {
        val refusedOnly = ShareSheetState.Review(listOf(ShareSheetItem("clip", "file", null, IntakeRefusalReason.UNSUPPORTED_TYPE)), textPreview = null)
        assertFalse(refusedOnly.addable)
        assertTrue(refusedOnly.copy(items = refusedOnly.items + ShareSheetItem("a", "PDF", null)).addable)
    }
}
