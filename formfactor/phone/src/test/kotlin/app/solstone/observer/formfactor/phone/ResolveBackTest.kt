// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResolveBackTest {
    @Test
    fun compactOpenShelfClosesShelf() {
        assertEquals(
            BackOutcome.ClosePane(PhonePane.SHELF),
            resolveBack(PaneStates.Empty.open(PhonePane.SHELF), PhoneRouteStack.Empty, WidthClass.COMPACT),
        )
    }

    @Test
    fun compactOpenJournalClosesJournal() {
        assertEquals(
            BackOutcome.ClosePane(PhonePane.JOURNAL),
            resolveBack(PaneStates.Empty.open(PhonePane.JOURNAL), PhoneRouteStack.Empty, WidthClass.COMPACT),
        )
    }

    @Test
    fun compactOpenStatusClosesStatus() {
        assertEquals(
            BackOutcome.ClosePane(PhonePane.STATUS),
            resolveBack(PaneStates.Empty.open(PhonePane.STATUS), PhoneRouteStack.Empty, WidthClass.COMPACT),
        )
    }

    @Test
    fun compactPaneBeatsDepthTwoPop() {
        val stack = PhoneRouteStack.Empty
            .showInDetail(PhoneRoute.RouteA)
            .pushInDetail(PhoneRoute.RouteCChild)
        assertEquals(
            BackOutcome.ClosePane(PhonePane.SHELF),
            resolveBack(PaneStates.Empty.open(PhonePane.SHELF), stack, WidthClass.COMPACT),
        )
    }

    @Test
    fun compactDepthTwoNoPanePopsDetail() {
        val stack = PhoneRouteStack.Empty
            .showInDetail(PhoneRoute.RouteA)
            .pushInDetail(PhoneRoute.RouteCChild)
        assertEquals(
            BackOutcome.PopDetail,
            resolveBack(PaneStates.Empty, stack, WidthClass.COMPACT),
        )
    }

    @Test
    fun compactDepthZeroNoPaneFallsThrough() {
        assertEquals(
            BackOutcome.FallThroughToSystem,
            resolveBack(PaneStates.Empty, PhoneRouteStack.Empty, WidthClass.COMPACT),
        )
    }

    @Test
    fun compactDepthOneNoPaneFallsThrough() {
        val stack = PhoneRouteStack.Empty.showInDetail(PhoneRoute.RouteA)
        assertEquals(
            BackOutcome.FallThroughToSystem,
            resolveBack(PaneStates.Empty, stack, WidthClass.COMPACT),
        )
    }

    @Test
    fun allThreeOpenClosesShelf() {
        val panes = PaneStates.Empty
            .open(PhonePane.SHELF)
            .open(PhonePane.JOURNAL)
            .open(PhonePane.STATUS)
        assertEquals(
            BackOutcome.ClosePane(PhonePane.SHELF),
            resolveBack(panes, PhoneRouteStack.Empty, WidthClass.COMPACT),
        )
    }

    @Test
    fun journalAndStatusOpenClosesJournal() {
        val panes = PaneStates.Empty.open(PhonePane.JOURNAL).open(PhonePane.STATUS)
        assertEquals(
            BackOutcome.ClosePane(PhonePane.JOURNAL),
            resolveBack(panes, PhoneRouteStack.Empty, WidthClass.COMPACT),
        )
    }

    @Test
    fun statusOnlyClosesStatus() {
        assertEquals(
            BackOutcome.ClosePane(PhonePane.STATUS),
            resolveBack(PaneStates.Empty.open(PhonePane.STATUS), PhoneRouteStack.Empty, WidthClass.COMPACT),
        )
    }

    @Test
    fun expandedAgreesWithCompact() {
        assertAgreesWithCompact(WidthClass.EXPANDED)
    }

    @Test
    fun largeAgreesWithCompact() {
        assertAgreesWithCompact(WidthClass.LARGE)
    }

    @Test
    fun extraLargeAgreesWithCompact() {
        assertAgreesWithCompact(WidthClass.EXTRA_LARGE)
    }

    @Test
    fun mediumAgreesWithCompact() {
        assertAgreesWithCompact(WidthClass.MEDIUM)
    }

    @Test
    fun classifyWindowWidth600IsMedium() {
        assertEquals(WidthClass.MEDIUM, classifyWindowWidth(600))
    }

    @Test
    fun closesPaneMatchesClosePaneOutcome() {
        assertTrue(BackOutcome.ClosePane(PhonePane.SHELF).closesPane(PhonePane.SHELF))
        assertFalse(BackOutcome.ClosePane(PhonePane.SHELF).closesPane(PhonePane.JOURNAL))
        assertFalse(BackOutcome.PopDetail.closesPane(PhonePane.SHELF))
        assertFalse(BackOutcome.FallThroughToSystem.closesPane(PhonePane.SHELF))
    }

    @Test
    fun popsDetailTrueOnlyForPopDetail() {
        assertTrue(BackOutcome.PopDetail.popsDetail)
        assertFalse(BackOutcome.ClosePane(PhonePane.SHELF).popsDetail)
        assertFalse(BackOutcome.FallThroughToSystem.popsDetail)
    }

    private fun assertAgreesWithCompact(width: WidthClass) {
        widthInvariantCases().forEach { (panes, stack, expected) ->
            val compact = resolveBack(panes, stack, WidthClass.COMPACT)
            assertEquals(expected, compact)
            assertEquals(compact, resolveBack(panes, stack, width))
        }
    }

    private fun widthInvariantCases(): List<Triple<PaneStates, PhoneRouteStack, BackOutcome>> {
        val depthTwo = PhoneRouteStack.Empty
            .showInDetail(PhoneRoute.RouteA)
            .pushInDetail(PhoneRoute.RouteCChild)
        return listOf(
            Triple(
                PaneStates.Empty.open(PhonePane.SHELF),
                PhoneRouteStack.Empty,
                BackOutcome.ClosePane(PhonePane.SHELF),
            ),
            Triple(PaneStates.Empty, depthTwo, BackOutcome.PopDetail),
            Triple(PaneStates.Empty, PhoneRouteStack.Empty, BackOutcome.FallThroughToSystem),
            Triple(
                PaneStates.Empty.open(PhonePane.SHELF).open(PhonePane.JOURNAL).open(PhonePane.STATUS),
                PhoneRouteStack.Empty,
                BackOutcome.ClosePane(PhonePane.SHELF),
            ),
            Triple(
                PaneStates.Empty.open(PhonePane.JOURNAL).open(PhonePane.STATUS),
                PhoneRouteStack.Empty,
                BackOutcome.ClosePane(PhonePane.JOURNAL),
            ),
            Triple(
                PaneStates.Empty.open(PhonePane.STATUS),
                PhoneRouteStack.Empty,
                BackOutcome.ClosePane(PhonePane.STATUS),
            ),
        )
    }
}
