// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileDotMarkTest {
    /**
     * The plus has to occupy the same footprint as every other mark.
     *
     * ⚠ `StrokeCap.Round` adds half a stroke width beyond each endpoint, so arms drawn to the full
     * radius overhang the box on four sides — the plus rendered wider than the disc, the ring, the
     * square and the diamond, and read as a heavier, button-like glyph rather than a status dot.
     */
    @Test
    fun thePlusArmsStopWhereItsRoundCapsEnd() {
        val diameter = 12f * 0.84f
        val stroke = 12f * 0.18f
        val half = plusArmHalfLength(diameter, stroke)
        assertEquals(diameter / 2f, half + stroke / 2f, 0.0001f)
        // ⛔ The old value, which is what the overhang was.
        assertTrue(half < diameter / 2f)
        // Degenerate input never produces a negative arm.
        assertEquals(0f, plusArmHalfLength(2f, 40f))
    }

    @Test
    fun markCountMatchesSourceStateEntries() {
        assertEquals(TILE_DOT_MARK_COUNT, SourceState.entries.size)
        assertEquals(TILE_DOT_MARK_COUNT, TileDotMark.entries.size)
    }

    @Test
    fun eachStateHasADistinctMark() {
        val marks = SourceState.entries.map(::tileDotMark)
        assertEquals(SourceState.entries.size, marks.distinct().size)
        assertEquals(TileDotMark.DISC, tileDotMark(SourceState.ON))
        assertEquals(TileDotMark.RING, tileDotMark(SourceState.OFF))
        assertEquals(TileDotMark.ARC, tileDotMark(SourceState.SETTING_UP))
        assertEquals(TileDotMark.SQUARE, tileDotMark(SourceState.PAUSED))
        assertEquals(TileDotMark.DIAMOND, tileDotMark(SourceState.NEEDS_ATTENTION))
        assertEquals(TileDotMark.PLUS, tileDotMark(SourceState.READY_TO_SET_UP))
    }
}
