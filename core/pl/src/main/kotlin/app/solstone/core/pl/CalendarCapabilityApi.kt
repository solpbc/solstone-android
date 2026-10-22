// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

const val CALENDAR_NOTES_CAPABILITY = "calendar_notes"

sealed interface CalendarCapabilityRead {
    data class Parsed(val calendarNotes: Boolean) : CalendarCapabilityRead
    data object Failed : CalendarCapabilityRead
}

/** A valid older status object is unsupported; failed responses remain unknown. */
fun parseCalendarCapability(status: Int, bodyText: String): CalendarCapabilityRead {
    if (status != 200) return CalendarCapabilityRead.Failed
    val root = runCatching { parseJson(bodyText) as? Map<*, *> }.getOrNull() ?: return CalendarCapabilityRead.Failed
    if (!root.containsKey("capabilities")) return CalendarCapabilityRead.Parsed(false)
    val caps = root["capabilities"] as? List<*> ?: return CalendarCapabilityRead.Failed
    if (caps.any { it !is String }) return CalendarCapabilityRead.Failed
    val notes = caps.any { it == CALENDAR_NOTES_CAPABILITY }
    return CalendarCapabilityRead.Parsed(notes)
}

fun fetchCalendarCapability(client: PlHttpClient): CalendarCapabilityRead {
    val response = client.request(
        "GET",
        "/api/system/status",
        mapOf("Cache-Control" to "no-cache"),
        null,
        maxResponseBytes = 64 * 1024,
    )
    return parseCalendarCapability(response.status, response.bodyText())
}
