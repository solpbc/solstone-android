// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SourceState

fun sourceLabel(sourceId: String): String = when (sourceId) {
    "audio" -> "audio"
    else -> sourceId
}

fun sourceStateCopy(state: SourceState): String? = when (state) {
    SourceState.OFF -> "off"
    SourceState.ON -> "taking it in"
    SourceState.NEEDS_ATTENTION -> "needs attention"
    SourceState.SETTING_UP, SourceState.PAUSED -> null
}

fun headingText(headingKey: String): String? = when (headingKey) {
    "heading.pane_status" -> "what is waiting"
    else -> null
}
