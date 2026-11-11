// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneLargeTextTest {
    @Test
    fun aLayoutThatBreaksAWordIsCaughtAndOneThatBreaksAtSpacesIsNot() {
        assertTrue(breaksInsideAWord("screenshots", lineEnds = listOf(6, 11), overflowedWidth = false), "screen/shots")
        assertFalse(breaksInsideAWord("ready to set up", lineEnds = listOf(6, 9, 13, 15), overflowedWidth = false))
        assertFalse(breaksInsideAWord("screenshots", lineEnds = listOf(11), overflowedWidth = false))
        assertTrue(breaksInsideAWord("calendar", lineEnds = listOf(8), overflowedWidth = true), "clipped on one line")
    }

    @Test
    fun aLabelAndValueStackOnlyWhenTheirLongestWordsCannotSitSideBySide() {
        assertEquals(RowFit.SIDE_BY_SIDE, rowFit(startMinPx = 200, endMinPx = 150, gapPx = 30, availablePx = 400))
        assertEquals(RowFit.STACKED, rowFit(startMinPx = 260, endMinPx = 150, gapPx = 30, availablePx = 400))
    }

    @Test
    fun theJournalPillBandFollowsTheMeasuredPillAndNeverShrinksBelowItsSlot() {
        assertEquals(56.dp, journalPillBand(measured = 44.dp, slot = 56.dp))
        assertEquals(130.dp, journalPillBand(measured = 130.dp, slot = 56.dp))
    }

    @Test
    fun onlySyncingPulsesSoAStillPillAsksForNoFrames() {
        assertTrue(statusPillPulses(StatusPillKind.SYNCING))
        StatusPillKind.entries.filter { it != StatusPillKind.SYNCING }.forEach { assertFalse(statusPillPulses(it), it.name) }
    }
}
