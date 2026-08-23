// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneRouteStackTest {
    @Test
    fun emptyStackIsAtDeckDepthZero() {
        assertEquals(0, PhoneRouteStack.Empty.depth)
        assertEquals(emptyList(), PhoneRouteStack.Empty.toList())
    }

    @Test
    fun showInDetailAlwaysExactlyOneRoute() {
        val fromEmpty = PhoneRouteStack.Empty.showInDetail(PhoneRoute.RouteA)
        assertEquals(1, fromEmpty.depth)
        assertEquals(listOf(PhoneRoute.RouteA), fromEmpty.toList())

        val fromShown = fromEmpty.showInDetail(PhoneRoute.RouteB)
        assertEquals(1, fromShown.depth)
        assertEquals(listOf(PhoneRoute.RouteB), fromShown.toList())

        val fromPushed = PhoneRouteStack.Empty
            .showInDetail(PhoneRoute.RouteA)
            .pushInDetail(PhoneRoute.RouteCChild)
            .showInDetail(PhoneRoute.RouteC)
        assertEquals(1, fromPushed.depth)
        assertEquals(listOf(PhoneRoute.RouteC), fromPushed.toList())
    }

    @Test
    fun threeDifferentShowInDetailThenResolveBackPopsDetail() {
        val stack = PhoneRouteStack.Empty
            .showInDetail(PhoneRoute.RouteA)
            .showInDetail(PhoneRoute.RouteB)
            .showInDetail(PhoneRoute.RouteC)
        assertEquals(1, stack.depth)
        assertEquals(
            BackOutcome.PopDetail,
            resolveBack(PaneStates.Empty, stack, WidthClass.COMPACT),
        )
    }

    @Test
    fun pushInDetailAppendsAndIncrementsDepth() {
        val one = PhoneRouteStack.Empty.pushInDetail(PhoneRoute.RouteA)
        assertEquals(1, one.depth)
        assertEquals(listOf(PhoneRoute.RouteA), one.toList())
        val two = one.pushInDetail(PhoneRoute.RouteCChild)
        assertEquals(2, two.depth)
        assertEquals(listOf(PhoneRoute.RouteA, PhoneRoute.RouteCChild), two.toList())
    }

    @Test
    fun showAfterPushWipesToShownRoute() {
        val stack = PhoneRouteStack.Empty
            .pushInDetail(PhoneRoute.RouteA)
            .pushInDetail(PhoneRoute.RouteB)
            .showInDetail(PhoneRoute.RouteC)
        assertEquals(listOf(PhoneRoute.RouteC), stack.toList())
        assertEquals(1, stack.depth)
    }

    @Test
    fun pushAfterShowAppendsOnShownRoute() {
        val stack = PhoneRouteStack.Empty
            .showInDetail(PhoneRoute.RouteA)
            .pushInDetail(PhoneRoute.RouteB)
        assertEquals(listOf(PhoneRoute.RouteA, PhoneRoute.RouteB), stack.toList())
    }

    @Test
    fun popRungFiresAtDepthOne() {
        val stack = PhoneRouteStack.Empty.showInDetail(PhoneRoute.RouteA)
        assertEquals(
            BackOutcome.PopDetail,
            resolveBack(PaneStates.Empty, stack, WidthClass.COMPACT),
        )
    }

    @Test
    fun toListIsDefensiveCopy() {
        val stack = PhoneRouteStack.Empty.showInDetail(PhoneRoute.RouteA)
        val copy = stack.toList() as MutableList
        copy.clear()
        assertEquals(1, stack.depth)
        assertEquals(listOf(PhoneRoute.RouteA), stack.toList())
    }
}
