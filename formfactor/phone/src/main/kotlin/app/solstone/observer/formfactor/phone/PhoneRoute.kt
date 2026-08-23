// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

sealed interface PhoneRoute : PhoneSurface {
    data object RouteA : PhoneRoute {
        override val paneTitle: String get() = "surface_route_a"
        override val headingKey: String get() = "heading.route_a"
    }

    data object RouteB : PhoneRoute {
        override val paneTitle: String get() = "surface_route_b"
        override val headingKey: String get() = "heading.route_b"
    }

    data object RouteC : PhoneRoute {
        override val paneTitle: String get() = "surface_route_c"
        override val headingKey: String get() = "heading.route_c"
    }

    data object RouteCChild : PhoneRoute {
        override val paneTitle: String get() = "surface_route_c_child"
        override val headingKey: String get() = "heading.route_c_child"
    }
}

fun encodePhoneRoute(route: PhoneRoute): String = when (route) {
    PhoneRoute.RouteA -> "route-a"
    PhoneRoute.RouteB -> "route-b"
    PhoneRoute.RouteC -> "route-c"
    PhoneRoute.RouteCChild -> "route-c-child"
}

fun decodePhoneRoute(key: String): PhoneRoute? = when (key) {
    "route-a" -> PhoneRoute.RouteA
    "route-b" -> PhoneRoute.RouteB
    "route-c" -> PhoneRoute.RouteC
    "route-c-child" -> PhoneRoute.RouteCChild
    else -> null
}
