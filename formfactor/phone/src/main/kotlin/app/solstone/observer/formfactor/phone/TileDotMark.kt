// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SourceState

const val TILE_DOT_MARK_COUNT = 6

enum class TileDotMark {
    RING,
    ARC,
    DISC,
    SQUARE,
    DIAMOND,

    /**
     * `ready to set up`. Crossing strokes — nothing else in this set has them, which is what
     * keeps every state's mark distinct at 12dp.
     *
     * ⛔ Not [RING]: that is `off`'s mark, and collapsing off with never-set-up tells an owner they
     * made a choice they did not make. Grounded in the symbol iOS already ships for this state
     * (`plus.circle`), and it rhymes with the deck's own `add more` tile a row up, so it reads as
     * *something you can start*.
     */
    PLUS,
}

fun tileDotMark(state: SourceState): TileDotMark = when (state) {
    SourceState.OFF -> TileDotMark.RING
    SourceState.SETTING_UP -> TileDotMark.ARC
    SourceState.ON -> TileDotMark.DISC
    SourceState.PAUSED -> TileDotMark.SQUARE
    SourceState.NEEDS_ATTENTION -> TileDotMark.DIAMOND
    SourceState.READY_TO_SET_UP -> TileDotMark.PLUS
}
