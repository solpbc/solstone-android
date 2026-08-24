// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneObserverPanesTest {
    @Test
    fun deckWidthUsesDefaultForMediumAndExpandedWindows() {
        assertEquals(360.dp, deckPaneWidth(WidthClass.MEDIUM))
        assertEquals(360.dp, deckPaneWidth(WidthClass.EXPANDED))
    }

    @Test
    fun deckWidthUsesWiderColumnForHigherWindows() {
        assertEquals(412.dp, deckPaneWidth(WidthClass.LARGE))
        assertEquals(412.dp, deckPaneWidth(WidthClass.EXTRA_LARGE))
    }

    @Test
    fun splitRequiresTwoPartitionsAndTheDeckFloor() {
        assertFalse(shouldRenderSplit(maxHorizontalPartitions = 1, deckPaneWidthDp = 412))
        assertFalse(shouldRenderSplit(maxHorizontalPartitions = 2, deckPaneWidthDp = 135))
        assertTrue(shouldRenderSplit(maxHorizontalPartitions = 2, deckPaneWidthDp = 136))
    }
}
