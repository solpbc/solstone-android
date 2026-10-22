// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.observer.harness.SourceWish
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenToggleRouteTest {
    @Test
    fun nonScreenSourcesAlwaysProceed() {
        listOf("audio", "location", "camera").forEach { sourceId ->
            assertEquals(ScreenToggleAction.Proceed, screenToggleRoute(sourceId, SourceWish.On, fromDetail = false))
            assertEquals(ScreenToggleAction.Proceed, screenToggleRoute(sourceId, SourceWish.On, fromDetail = true))
            assertEquals(ScreenToggleAction.Proceed, screenToggleRoute(sourceId, SourceWish.Off, fromDetail = false))
            assertEquals(ScreenToggleAction.Proceed, screenToggleRoute(sourceId, SourceWish.Off, fromDetail = true))
        }
    }

    @Test
    fun calendarOnOpensSetupFromTheDeckAndFromDetail() {
        assertEquals(ScreenToggleAction.OpenCalendarSetup, screenToggleRoute("calendar", SourceWish.On, fromDetail = false))
        assertEquals(ScreenToggleAction.OpenCalendarSetup, screenToggleRoute("calendar", SourceWish.On, fromDetail = true))
    }

    @Test
    fun calendarOffTurnsOffFromTheDeckAndFromDetail() {
        assertEquals(ScreenToggleAction.TurnOffCalendar, screenToggleRoute("calendar", SourceWish.Off, fromDetail = false))
        assertEquals(ScreenToggleAction.TurnOffCalendar, screenToggleRoute("calendar", SourceWish.Off, fromDetail = true))
    }

    @Test
    fun screenOnFromDeckOpensDetailWithoutConsentPicker() {
        assertEquals(ScreenToggleAction.OpenDetail, screenToggleRoute("screen", SourceWish.On, fromDetail = false))
    }

    @Test
    fun screenOnFromDetailLaunchesConsent() {
        assertEquals(ScreenToggleAction.LaunchConsent, screenToggleRoute("screen", SourceWish.On, fromDetail = true))
    }

    @Test
    fun screenOffTurnsOffFromDeckAndDetail() {
        assertEquals(ScreenToggleAction.TurnOff, screenToggleRoute("screen", SourceWish.Off, fromDetail = false))
        assertEquals(ScreenToggleAction.TurnOff, screenToggleRoute("screen", SourceWish.Off, fromDetail = true))
    }
}
