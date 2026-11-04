// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

const val EXTRA_PHONE_ROUTE = "app.solstone.phone.route"

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

    /** One unknown-journal sighting; [address] is null for the one seen through the relay. */
    data class UnknownJournal(val address: String?) : PhoneRoute {
        override val paneTitle: String get() = "surface_unknown_journal"
        override val headingKey: String get() = "heading.unknown_journal"
    }

    data object AboutSolstone : PhoneRoute {
        override val paneTitle: String get() = "surface_about_solstone"
        override val headingKey: String get() = "heading.about_solstone"
    }

    data object Licences : PhoneRoute {
        override val paneTitle: String get() = "surface_licences"
        override val headingKey: String get() = "heading.licences"
    }

    data object Import : PhoneRoute {
        override val paneTitle: String get() = "surface_import"
        override val headingKey: String get() = "heading.import"
    }

    data object AddMore : PhoneRoute {
        override val paneTitle: String get() = "surface_add_more"
        override val headingKey: String get() = "heading.add_more"
    }

    data object YourJournal : PhoneRoute {
        override val paneTitle: String get() = "surface_your_journal"
        override val headingKey: String get() = "heading.your_journal"
    }

    data object JournalSetup : PhoneRoute {
        override val paneTitle: String get() = "surface_journal_setup"
        override val headingKey: String get() = "heading.journal_setup"
    }

    data object ThisDevice : PhoneRoute {
        override val paneTitle: String get() = "surface_this_device"
        override val headingKey: String get() = "heading.this_device"
    }

    data object TechnicalDetails : PhoneRoute {
        override val paneTitle: String get() = "surface_technical_details"
        override val headingKey: String get() = "heading.technical_details"
    }

    data object EventLog : PhoneRoute {
        override val paneTitle: String get() = "surface_event_log"
        override val headingKey: String get() = "heading.event_log"
    }

    data object ProblemReports : PhoneRoute {
        override val paneTitle: String get() = "surface_problem_reports"
        override val headingKey: String get() = "heading.problem_reports"
    }

    data object Notifications : PhoneRoute {
        override val paneTitle: String get() = "surface_notifications"
        override val headingKey: String get() = "heading.notifications"
    }

    data object Help : PhoneRoute {
        override val paneTitle: String get() = "surface_help"
        override val headingKey: String get() = "heading.help"
    }

    data object CalendarSetup : PhoneRoute {
        override val paneTitle: String get() = "surface_calendar_setup"
        override val headingKey: String get() = "heading.calendar"
    }
}

fun encodePhoneRoute(route: PhoneRoute): String = when (route) {
    PhoneRoute.RouteA -> "route-a"
    PhoneRoute.RouteB -> "route-b"
    PhoneRoute.RouteC -> "route-c"
    PhoneRoute.RouteCChild -> "route-c-child"
    is PhoneRoute.SourceDetail -> "sd/${route.sourceId}"
    is PhoneRoute.UnknownJournal -> route.address?.let { "uj/$it" } ?: "uj-relay"
    PhoneRoute.AboutSolstone -> "about-solstone"
    PhoneRoute.Licences -> "licences"
    PhoneRoute.Import -> "import"
    PhoneRoute.AddMore -> "add-more"
    PhoneRoute.YourJournal -> "your-journal"
    PhoneRoute.JournalSetup -> "journal-setup"
    PhoneRoute.ThisDevice -> "this-device"
    PhoneRoute.TechnicalDetails -> "technical-details"
    PhoneRoute.EventLog -> "event-log"
    PhoneRoute.ProblemReports -> "problem-reports"
    PhoneRoute.Notifications -> "notifications"
    PhoneRoute.Help -> "help"
    PhoneRoute.CalendarSetup -> "calendar-setup"
}

fun decodePhoneRoute(key: String): PhoneRoute? {
    val parts = key.split("/", limit = 2)
    if (parts.size == 2 && parts[0] == "sd" && parts[1].isNotEmpty()) {
        return PhoneRoute.SourceDetail(parts[1])
    }
    if (parts.size == 2 && parts[0] == "uj" && parts[1].isNotEmpty()) {
        return PhoneRoute.UnknownJournal(parts[1])
    }
    return when (key) {
        "uj-relay" -> PhoneRoute.UnknownJournal(null)
        "route-a" -> PhoneRoute.RouteA
        "route-b" -> PhoneRoute.RouteB
        "route-c" -> PhoneRoute.RouteC
        "route-c-child" -> PhoneRoute.RouteCChild
        "about-solstone" -> PhoneRoute.AboutSolstone
        "licences" -> PhoneRoute.Licences
        "import" -> PhoneRoute.Import
        "add-more" -> PhoneRoute.AddMore
        "your-journal" -> PhoneRoute.YourJournal
        "journal-setup" -> PhoneRoute.JournalSetup
        "this-device" -> PhoneRoute.ThisDevice
        "technical-details" -> PhoneRoute.TechnicalDetails
        "event-log" -> PhoneRoute.EventLog
        "problem-reports" -> PhoneRoute.ProblemReports
        "notifications" -> PhoneRoute.Notifications
        "help" -> PhoneRoute.Help
        "calendar-setup" -> PhoneRoute.CalendarSetup
        else -> null
    }
}
