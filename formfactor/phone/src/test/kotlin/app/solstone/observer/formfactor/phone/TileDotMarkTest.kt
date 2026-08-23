// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals

class TileDotMarkTest {
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
    }
}
