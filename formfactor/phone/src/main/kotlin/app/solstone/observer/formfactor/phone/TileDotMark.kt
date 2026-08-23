// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SourceState

const val TILE_DOT_MARK_COUNT = 5

enum class TileDotMark {
    RING,
    ARC,
    DISC,
    SQUARE,
    DIAMOND,
}

fun tileDotMark(state: SourceState): TileDotMark = when (state) {
    SourceState.OFF -> TileDotMark.RING
    SourceState.SETTING_UP -> TileDotMark.ARC
    SourceState.ON -> TileDotMark.DISC
    SourceState.PAUSED -> TileDotMark.SQUARE
    SourceState.NEEDS_ATTENTION -> TileDotMark.DIAMOND
}
