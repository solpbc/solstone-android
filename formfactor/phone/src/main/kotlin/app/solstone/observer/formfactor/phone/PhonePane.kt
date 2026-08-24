// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

enum class PaneBackDismissRequirement {
    DRAWER_STATE_OVERLOAD,
    PARTIAL_EXPAND_COLLAPSES_FIRST,
    NO_LIBRARY_BACK,
}

enum class PhonePane(
    override val paneTitle: String,
    override val headingKey: String,
    val backDismissRequirement: PaneBackDismissRequirement,
) : PhoneSurface {
    SHELF(
        paneTitle = "pane_shelf",
        headingKey = "heading.pane_shelf",
        // The drawer performs the close, but this requirement preserves the
        // resolver's SHELF outcome so detail back cannot win while it is open.
        backDismissRequirement = PaneBackDismissRequirement.DRAWER_STATE_OVERLOAD,
    ),
    JOURNAL(
        paneTitle = "pane_journal",
        headingKey = "heading.pane_journal",
        backDismissRequirement = PaneBackDismissRequirement.PARTIAL_EXPAND_COLLAPSES_FIRST,
    ),
    STATUS(
        paneTitle = "pane_status",
        headingKey = "heading.pane_status",
        backDismissRequirement = PaneBackDismissRequirement.NO_LIBRARY_BACK,
    ),
}

fun encodePhonePane(pane: PhonePane): String = when (pane) {
    PhonePane.SHELF -> "pane-shelf"
    PhonePane.JOURNAL -> "pane-journal"
    PhonePane.STATUS -> "pane-status"
}

fun decodePhonePane(key: String): PhonePane? = when (key) {
    "pane-shelf" -> PhonePane.SHELF
    "pane-journal" -> PhonePane.JOURNAL
    "pane-status" -> PhonePane.STATUS
    else -> null
}
