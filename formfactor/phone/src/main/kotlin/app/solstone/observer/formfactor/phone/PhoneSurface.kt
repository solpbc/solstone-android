// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

interface PhoneSurface {
    val paneTitle: String
    val headingKey: String
}

data object PhoneDeck : PhoneSurface {
    override val paneTitle: String get() = "surface_deck"
    override val headingKey: String get() = "heading.deck"
}

fun phoneSurfaces(): List<PhoneSurface> = listOf(
    PhoneDeck,
    PhonePane.SHELF,
    PhonePane.JOURNAL,
    PhonePane.STATUS,
    PhoneRoute.RouteA,
    PhoneRoute.RouteB,
    PhoneRoute.RouteC,
    PhoneRoute.RouteCChild,
    PhoneRoute.SourceDetail("audio"),
    PhoneRoute.AboutSolstone,
    PhoneRoute.Licences,
    PhoneRoute.Import,
    PhoneRoute.AddMore,
    PhoneRoute.YourJournal,
    PhoneRoute.ThisDevice,
    PhoneRoute.Notifications,
    PhoneRoute.Help,
)
