// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.observer.harness.SourceWish

enum class ScreenToggleAction {
    Proceed,
    OpenDetail,
    LaunchConsent,
    TurnOff,
    OpenCalendarSetup,
    TurnOffCalendar,
}

fun screenToggleRoute(
    sourceId: String,
    wish: SourceWish,
    fromDetail: Boolean,
): ScreenToggleAction {
    if (sourceId == "calendar") {
        return if (wish == SourceWish.On) ScreenToggleAction.OpenCalendarSetup else ScreenToggleAction.TurnOffCalendar
    }
    if (sourceId != "screen") return ScreenToggleAction.Proceed
    return when (wish) {
        SourceWish.Off -> ScreenToggleAction.TurnOff
        // Google Play requires an in-app disclosure immediately before this prompt, in normal use
        // of the app. The source's own page carries it, so the list opens that page first.
        SourceWish.On -> if (fromDetail) ScreenToggleAction.LaunchConsent else ScreenToggleAction.OpenDetail
    }
}
