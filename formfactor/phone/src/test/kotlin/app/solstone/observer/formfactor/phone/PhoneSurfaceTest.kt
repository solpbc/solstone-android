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
    fun surfaceCountIsEleven() {
        assertEquals(11, phoneSurfaces().size)
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
    }

    private fun registered(route: PhoneRoute): Boolean = when (route) {
        PhoneRoute.RouteA -> PhoneRoute.RouteA in phoneSurfaces()
        PhoneRoute.RouteB -> PhoneRoute.RouteB in phoneSurfaces()
        PhoneRoute.RouteC -> PhoneRoute.RouteC in phoneSurfaces()
        PhoneRoute.RouteCChild -> PhoneRoute.RouteCChild in phoneSurfaces()
        PhoneRoute.AboutSolstone -> PhoneRoute.AboutSolstone in phoneSurfaces()
        PhoneRoute.Licences -> PhoneRoute.Licences in phoneSurfaces()
        is PhoneRoute.SourceDetail -> phoneSurfaces().any { it is PhoneRoute.SourceDetail }
    }
}
