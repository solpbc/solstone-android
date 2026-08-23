// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SourceState

internal val phoneSourceLabels: Map<String, String> = mapOf(
    "audio" to "audio",
    "location" to "location",
    "camera" to "camera",
)

fun sourceLabel(sourceId: String): String = phoneSourceLabels[sourceId] ?: sourceId

fun sourceStateCopy(state: SourceState): String = when (state) {
    SourceState.OFF -> "off"
    SourceState.SETTING_UP -> "setting up"
    SourceState.ON -> "on"
    SourceState.PAUSED -> "paused"
    SourceState.NEEDS_ATTENTION -> "needs attention"
}

fun headingText(surface: PhoneSurface): String? = when (surface) {
    is PhoneRoute.SourceDetail -> sourceLabel(surface.sourceId)
    else -> when (surface.headingKey) {
        "heading.pane_status" -> "status"
        else -> null
    }
}
