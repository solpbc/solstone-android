// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneSurfaceTest {
    @Test
    fun paneTitlesAreUnique() {
        val titles = phoneSurfaces().map { it.paneTitle }
        assertTrue(titles.all { it.isNotBlank() })
        assertEquals(titles.size, titles.distinct().size)
        assertEquals(phoneSurfaces().size, titles.distinct().size)
    }

    @Test
    fun headingKeysAreUnique() {
        val keys = phoneSurfaces().map { it.headingKey }
        assertTrue(keys.all { it.isNotBlank() })
        assertEquals(keys.size, keys.distinct().size)
        assertEquals(phoneSurfaces().size, keys.distinct().size)
    }

    @Test
    fun surfaceCountIsTwentyTwo() {
        assertEquals(22, phoneSurfaces().size)
    }

    @Test
    fun deckIsAMember() {
        assertTrue(PhoneDeck in phoneSurfaces())
    }

    @Test
    fun everySealedPhoneRouteIsRegistered() {
        assertTrue(registered(PhoneRoute.RouteA))
        assertTrue(registered(PhoneRoute.RouteB))
        assertTrue(registered(PhoneRoute.RouteC))
        assertTrue(registered(PhoneRoute.RouteCChild))
        assertTrue(registered(PhoneRoute.SourceDetail("audio")))
        assertTrue(registered(PhoneRoute.AboutSolstone))
        assertTrue(registered(PhoneRoute.Licences))
        assertTrue(registered(PhoneRoute.Import))
        assertTrue(registered(PhoneRoute.AddMore))
        assertTrue(registered(PhoneRoute.YourJournal))
        assertTrue(registered(PhoneRoute.JournalSetup))
        assertTrue(registered(PhoneRoute.ThisDevice))
        assertTrue(registered(PhoneRoute.TechnicalDetails))
        assertTrue(registered(PhoneRoute.EventLog))
        assertTrue(registered(PhoneRoute.ProblemReports))
        assertTrue(registered(PhoneRoute.Notifications))
        assertTrue(registered(PhoneRoute.Help))
        assertTrue(registered(PhoneRoute.CalendarSetup))
    }

    @Test
    fun shelfReachablePaneTitlesDoNotReuseShippedTitles() {
        val shelfRoutes = listOf(
            PhoneRoute.YourJournal,
            PhoneRoute.ThisDevice,
            PhoneRoute.Notifications,
            PhoneRoute.Help,
            PhoneRoute.AboutSolstone,
        )
        val shipped = phoneSurfaces().filterNot { it in shelfRoutes }
        assertEquals(shelfRoutes.size, shelfRoutes.map { it.paneTitle }.distinct().size)
        assertTrue(shelfRoutes.none { route -> shipped.any { it.paneTitle == route.paneTitle } })
    }

    private fun registered(route: PhoneRoute): Boolean = when (route) {
        PhoneRoute.RouteA -> PhoneRoute.RouteA in phoneSurfaces()
        PhoneRoute.RouteB -> PhoneRoute.RouteB in phoneSurfaces()
        PhoneRoute.RouteC -> PhoneRoute.RouteC in phoneSurfaces()
        PhoneRoute.RouteCChild -> PhoneRoute.RouteCChild in phoneSurfaces()
        PhoneRoute.AboutSolstone -> PhoneRoute.AboutSolstone in phoneSurfaces()
        PhoneRoute.Licences -> PhoneRoute.Licences in phoneSurfaces()
        PhoneRoute.Import -> PhoneRoute.Import in phoneSurfaces()
        PhoneRoute.AddMore -> PhoneRoute.AddMore in phoneSurfaces()
        PhoneRoute.YourJournal -> PhoneRoute.YourJournal in phoneSurfaces()
        PhoneRoute.JournalSetup -> PhoneRoute.JournalSetup in phoneSurfaces()
        PhoneRoute.ThisDevice -> PhoneRoute.ThisDevice in phoneSurfaces()
        PhoneRoute.TechnicalDetails -> PhoneRoute.TechnicalDetails in phoneSurfaces()
        PhoneRoute.EventLog -> PhoneRoute.EventLog in phoneSurfaces()
        PhoneRoute.ProblemReports -> PhoneRoute.ProblemReports in phoneSurfaces()
        PhoneRoute.Notifications -> PhoneRoute.Notifications in phoneSurfaces()
        PhoneRoute.Help -> PhoneRoute.Help in phoneSurfaces()
        PhoneRoute.CalendarSetup -> PhoneRoute.CalendarSetup in phoneSurfaces()
        is PhoneRoute.SourceDetail -> phoneSurfaces().any { it is PhoneRoute.SourceDetail }
    }

    @Test
    fun everySurfaceWithApprovedCopyIsAnnouncedWithIt() {
        val announced = phoneSurfaces().filterNot { it == PhoneDeck }.associateWith { spokenPaneTitle(it) }
        val stillAnIdentifier = announced.filterValues { it.startsWith("surface_") || it.startsWith("pane_") }
        assertEquals(
            setOf(
                "surface_route_a",
                "surface_route_b",
                "surface_route_c",
                "surface_route_c_child",
            ),
            stillAnIdentifier.values.toSet(),
        )
    }

    @Test
    fun announcedPaneNamesAreUnique() {
        assertEquals(spokenPaneTitle(PhoneRoute.YourJournal), spokenPaneTitle(PhoneRoute.JournalSetup))
        val announced = phoneSurfaces()
            .filterNot { it == PhoneDeck || it == PhoneRoute.JournalSetup }
            .map { spokenPaneTitle(it) } + greetingFor(5)
        assertTrue(announced.all { it.isNotBlank() })
        assertEquals(announced.size, announced.distinct().size)
    }
}
