// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SourceWish

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

fun sourceWishCopy(wish: SourceWish): String = when (wish) {
    SourceWish.Off -> "off"
    SourceWish.On -> "on"
}

fun headingText(surface: PhoneSurface): String? = when (surface) {
    is PhoneRoute.SourceDetail -> sourceLabel(surface.sourceId)
    else -> when (surface.headingKey) {
        "heading.pane_status" -> "status"
        "heading.about_solstone" -> "about solstone"
        "heading.licences" -> "licenses"
        "heading.import" -> "import"
        "heading.add_more" -> "add more"
        "heading.your_journal" -> "your journal"
        "heading.this_device" -> "this device"
        "heading.notifications" -> "notifications"
        "heading.help" -> "help"
        "heading.pane_shelf" -> "settings"
        else -> null
    }
}

/**
 * The name a screen reader announces when a pane appears.
 *
 * A surface's [PhoneSurface.paneTitle] is an internal identifier that tests match on; it is not
 * owner-facing text. Resolve the visible heading first so a pane is announced with the same words
 * it displays. Only deliberately unimplemented placeholder routes may expose an identifier.
 */
fun spokenPaneTitle(surface: PhoneSurface): String = when (surface) {
    PhonePane.JOURNAL -> "your journal, not set up yet"
    else -> headingText(surface) ?: placeholderPaneTitle(surface)
}

private fun placeholderPaneTitle(surface: PhoneSurface): String {
    check(
        surface in setOf(
            PhoneRoute.RouteA,
            PhoneRoute.RouteB,
            PhoneRoute.RouteC,
            PhoneRoute.RouteCChild,
        ),
    ) { "A rendered owner surface needs an approved pane title: ${surface.paneTitle}" }
    return surface.paneTitle
}
