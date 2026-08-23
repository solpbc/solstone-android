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

    data class SourceDetail(val sourceId: String) : PhoneRoute {
        override val paneTitle: String get() = "surface_source_detail"
        override val headingKey: String get() = "heading.source_detail"
    }
}

fun encodePhoneRoute(route: PhoneRoute): String = when (route) {
    PhoneRoute.RouteA -> "route-a"
    PhoneRoute.RouteB -> "route-b"
    PhoneRoute.RouteC -> "route-c"
    PhoneRoute.RouteCChild -> "route-c-child"
    is PhoneRoute.SourceDetail -> "sd/${route.sourceId}"
}

fun decodePhoneRoute(key: String): PhoneRoute? {
    val parts = key.split("/", limit = 2)
    if (parts.size == 2 && parts[0] == "sd" && parts[1].isNotEmpty()) {
        return PhoneRoute.SourceDetail(parts[1])
    }
    return when (key) {
        "route-a" -> PhoneRoute.RouteA
        "route-b" -> PhoneRoute.RouteB
        "route-c" -> PhoneRoute.RouteC
        "route-c-child" -> PhoneRoute.RouteCChild
        else -> null
    }
}
