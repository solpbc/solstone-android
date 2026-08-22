// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class ClassifyWindowWidthTest {
    @Test
    fun compactBelowMediumLowerBound() {
        assertEquals(WidthClass.COMPACT, classifyWindowWidth(0))
        assertEquals(WidthClass.COMPACT, classifyWindowWidth(599))
    }

    @Test
    fun mediumFromMediumLowerBound() {
        assertEquals(WidthClass.MEDIUM, classifyWindowWidth(600))
        assertEquals(WidthClass.MEDIUM, classifyWindowWidth(839))
    }

    @Test
    fun expandedFromExpandedLowerBound() {
        assertEquals(WidthClass.EXPANDED, classifyWindowWidth(840))
        assertEquals(WidthClass.EXPANDED, classifyWindowWidth(1199))
    }

    @Test
    fun largeFromLargeLowerBoundIncludingFourteenHundred() {
        assertEquals(WidthClass.LARGE, classifyWindowWidth(1200))
        assertEquals(WidthClass.LARGE, classifyWindowWidth(1400))
        assertEquals(WidthClass.LARGE, classifyWindowWidth(1599))
    }

    @Test
    fun extraLargeFromExtraLargeLowerBound() {
        assertEquals(WidthClass.EXTRA_LARGE, classifyWindowWidth(1600))
    }
}
