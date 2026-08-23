// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneRouteCodecTest {
    @Test
    fun everyRouteRoundTrips() {
        val expected = mapOf(
            PhoneRoute.RouteA to "route-a",
            PhoneRoute.RouteB to "route-b",
            PhoneRoute.RouteC to "route-c",
            PhoneRoute.RouteCChild to "route-c-child",
        )
        expected.forEach { (route, key) ->
            assertEquals(key, encodePhoneRoute(route))
            assertEquals(route, decodePhoneRoute(key))
        }
        val stack = PhoneRouteStack.Empty
            .showInDetail(PhoneRoute.RouteA)
            .pushInDetail(PhoneRoute.RouteCChild)
        assertEquals(stack, decodePhoneRouteStack(encodePhoneRouteStack(stack)))
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
