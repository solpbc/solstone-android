// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneRouteCodecTest {
    @Test
    fun everyRouteRoundTrips() {
        val routes = phoneSurfaces().filterIsInstance<PhoneRoute>()
        assertTrue(routes.isNotEmpty())
        assertEquals(phoneSurfaces().count { it is PhoneRoute }, routes.size)
        routes.forEach { route ->
            assertEquals(route, decodePhoneRoute(encodePhoneRoute(route)))
        }
        val stack = PhoneRouteStack.Empty
            .showInDetail(PhoneRoute.RouteA)
            .pushInDetail(PhoneRoute.RouteCChild)
        assertEquals(stack, decodePhoneRouteStack(encodePhoneRouteStack(stack)))
    }

    @Test
    fun sourceDetailWithDelimiterRoundTrips() {
        val route = PhoneRoute.SourceDetail("a/b/c")
        assertEquals(route, decodePhoneRoute(encodePhoneRoute(route)))
        assertEquals("sd/a/b/c", encodePhoneRoute(route))
    }

    @Test
    fun malformedSourceDetailDecodesToNull() {
        assertNull(decodePhoneRoute("sd/"))
        assertNull(decodePhoneRoute("sd"))
        assertNull(decodePhoneRoute("x/y"))
    }

    // Falsified by a stack restore that loses which sighting was open, or merges relay and direct.
    @Test
    fun unknownJournalRoutesRoundTripPerAddressAndRelay() {
        listOf("192.168.1.20:7657", "[fe80::1%wlan0]:7657", null).forEach { address ->
            val route = PhoneRoute.UnknownJournal(address)
            assertEquals(route, decodePhoneRoute(encodePhoneRoute(route)))
        }
    }

    @Test
    fun decodeGarbageReturnsNull() {
        assertNull(decodePhoneRoute("not-a-route"))
    }

    @Test
    fun decodeStackDropsUnknownKeys() {
        val stack = decodePhoneRouteStack(listOf("route-a", "nope", "route-c-child"))
        assertEquals(listOf(PhoneRoute.RouteA, PhoneRoute.RouteCChild), stack.toList())
    }

    @Test
    fun decodeAllGarbageIsEmptyStack() {
        assertEquals(PhoneRouteStack.Empty, decodePhoneRouteStack(emptyList()))
        assertEquals(PhoneRouteStack.Empty, decodePhoneRouteStack(listOf("x", "y")))
    }
}
