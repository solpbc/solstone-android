// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.model.SourceState
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.core.watch.courier.WatchCourierStrings
import app.solstone.observer.harness.SourceWish

internal val phoneSourceLabels: Map<String, String> get() = mapOf(
    "audio" to phoneText("android.phone.audio"),
    "location" to phoneText("android.gap.location"),
    "camera" to phoneText("android.gap.camera"),
    "screen" to phoneText("android.phone.screen"),
    "calendar" to phoneText("android.phone.calendar"),
    "obsidian" to phoneText("android.phone.source.obsidian"),
    "screenshots" to phoneText("android.screenshots.screenshots"),
    "folders" to phoneText("android.folders.source.label"),
)

fun sourceLabel(sourceId: String): String = when (sourceId) {
    WatchCourierStrings.WATCH, WatchCourierProtocol.SOURCE_ID_WATCH_AUDIO -> phoneText("android.phone.source.watch")
    else -> phoneSourceLabels[sourceId] ?: sourceId
}

fun sourceStateCopy(state: SourceState): String = when (state) {
    SourceState.OFF -> phoneText("android.push.off")
    SourceState.READY_TO_SET_UP -> phoneText("android.source.ready.set.up")
    SourceState.SETTING_UP -> phoneText("android.push.setting.up")
    SourceState.ON -> phoneText("android.push.on")
    SourceState.PAUSED -> phoneText("android.source.paused")
    SourceState.NEEDS_ATTENTION -> phoneText("android.push.needs.attention")
}

fun sourceWishCopy(wish: SourceWish): String = when (wish) {
    SourceWish.Off -> phoneText("android.push.off")
    SourceWish.On -> phoneText("android.push.on")
}

fun headingText(surface: PhoneSurface): String? = when (surface) {
    is PhoneRoute.SourceDetail -> sourceLabel(surface.sourceId)
    is PhoneRoute.FolderDetail -> phoneText("android.folders.source.label")
    else -> when (surface.headingKey) {
        "heading.pane_status" -> phoneText("android.shelf.status")
        "heading.about_solstone" -> phoneText("android.source.about.solstone")
        "heading.licences" -> phoneText("android.source.licenses")
        "heading.import" -> phoneText("android.setup.import")
        "heading.add_more" -> phoneText("android.setup.add.more")
        "heading.your_journal" -> phoneText("android.source.journal")
        "heading.journal_setup" -> phoneText("android.source.journal")
        "heading.this_device" -> phoneText("android.source.device")
        "heading.technical_details" -> phoneText("android.migration.technical.details")
        "heading.unknown_journal" -> phoneText("android.source.unknownjournal")
        "heading.event_log" -> phoneText("android.shelf.event.log")
        "heading.problem_reports" -> phoneText("android.shelf.problem.reports")
        "heading.notifications" -> phoneText("android.phone.notifications")
        "heading.help" -> phoneText("android.phone.help")
        "heading.calendar" -> phoneText("android.phone.calendar")
        "heading.pane_shelf" -> phoneText("android.shelf.settings")
        else -> null
    }
}

/**
 * The name a screen reader announces when a pane appears.
 *
 * A surface's [PhoneSurface.paneTitle] is an internal identifier that tests match on; it is not
 * owner-facing text. Resolve the visible heading first so a pane is announced with the same words
 * it displays. Only deliberately unimplemented placeholder routes may expose an identifier.
 * [PhonePane.JOURNAL] announces [journalMark]; the default is only the static catalog value (the
 * generic mark). A live journal sheet passes the presentation it was given.
 */
fun spokenPaneTitle(
    surface: PhoneSurface,
    journalMark: JournalMarkPresentation = JournalMarkPresentation.Generic,
): String = when (surface) {
    PhonePane.JOURNAL -> journalMarkSpokenForm(journalMark)
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
