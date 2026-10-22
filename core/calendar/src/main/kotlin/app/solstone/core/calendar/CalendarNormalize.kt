// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.calendar

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val WEEKDAYS = setOf("MO", "TU", "WE", "TH", "FR")

data class RawAttendee(
    val email: String?,
    val name: String?,
    val status: Int,
)

data class RawCalendarInstance(
    val calendarId: Long,
    val calendarName: String,
    val ownerAccount: String?,
    val eventId: Long,
    val uid2445: String?,
    val masterUid: String?,
    val exception: Boolean,
    val recurring: Boolean,
    val originalInstanceEpochMs: Long?,
    val beginEpochMs: Long,
    val endEpochMs: Long?,
    val allDay: Boolean,
    val eventTimeZone: String?,
    val eventEndTimeZone: String?,
    val title: String?,
    val location: String?,
    val description: String?,
    val rrule: String?,
    val organizer: String?,
    val organizerName: String?,
    val cancelled: Boolean,
    val attendees: List<RawAttendee>,
    val fallbackNamespace: String,
    val masterEventId: Long? = null,
)

data class NormalizedInstance(
    val event: String,
    val occurrence: String?,
    val calendarId: Long,
    val calendarName: String,
    val startEpochMs: Long,
    val note: CalendarNote,
    val providerEventIds: Set<Long> = emptySet(),
)

fun calendarEventIdentity(
    uid2445: String?,
    exception: Boolean,
    masterUid: String?,
    providerEventId: Long,
    originalInstanceEpochMs: Long,
    fallbackNamespace: String,
): String {
    fun strip(uid: String): String? = uid.substringBefore("/RID=").takeIf { it.isNotBlank() }
    if (exception) strip(masterUid.orEmpty())?.let { return it }
    strip(uid2445.orEmpty())?.let { return it }
    val material = "$fallbackNamespace\u0000$providerEventId"
    val hex = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "solstone:calendar:$hex"
}

fun classifyRepeats(rrule: String?): String? {
    if (rrule.isNullOrBlank()) return null
    val body = rrule.trim().removePrefix("RRULE:").removePrefix("rrule:")
    val parts = body.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    val keys = linkedMapOf<String, String>()
    for (part in parts) {
        val eq = part.indexOf('=')
        if (eq <= 0) return null
        val key = part.substring(0, eq).uppercase()
        if (keys.put(key, part.substring(eq + 1)) != null) return null
    }
    val freq = keys["FREQ"]?.uppercase() ?: return null
    if ((keys.keys - "FREQ" - "BYDAY").isNotEmpty()) return null
    val byday = keys["BYDAY"]?.uppercase()
    if (freq == "WEEKLY" && byday != null) {
        val days = byday.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (days.size == WEEKDAYS.size && days.toSet() == WEEKDAYS) "every weekday" else null
    }
    if (byday != null) return null
    return when (freq) {
        "DAILY" -> "every day"
        "WEEKLY" -> "every week"
        "MONTHLY" -> "every month"
        "YEARLY" -> "every year"
        else -> null
    }
}

fun organizerEmail(raw: String?): String? {
    val trimmed = raw?.trim()?.ifBlank { null } ?: return null
    val value = if (trimmed.startsWith("mailto:", ignoreCase = true)) trimmed.substring(7) else trimmed
    return value.trim().ifBlank { null }
}

/** A cancelled instance is absent. Every other instance becomes one normalized note body. */
fun normalizeInstance(raw: RawCalendarInstance): NormalizedInstance? {
    if (raw.cancelled) return null
    val origin = raw.originalInstanceEpochMs ?: raw.beginEpochMs
    val event = calendarEventIdentity(
        uid2445 = raw.uid2445,
        exception = raw.exception,
        masterUid = raw.masterUid,
        providerEventId = raw.masterEventId ?: raw.eventId,
        originalInstanceEpochMs = origin,
        fallbackNamespace = raw.fallbackNamespace,
    )
    val occurrence = if (raw.recurring || raw.exception) {
        if (raw.allDay) utcDate(origin) else formatChangedAt(Instant.ofEpochMilli(origin), ZoneOffset.UTC)
    } else {
        null
    }
    val start = if (raw.allDay) utcDate(raw.beginEpochMs) else formatZoned(raw.beginEpochMs, raw.eventTimeZone).first
    val endInstant = raw.endEpochMs
    val end = when {
        endInstant == null -> null
        endInstant < raw.beginEpochMs -> null
        raw.allDay -> utcDate(endInstant).takeUnless { it < start }
        else -> formatZoned(endInstant, raw.eventEndTimeZone ?: raw.eventTimeZone).first
    }
    val zone = if (raw.allDay) null else formatZoned(raw.beginEpochMs, raw.eventTimeZone).second
    val attendees = raw.attendees.map { attendee ->
        val email = attendee.email?.trim()?.ifBlank { null }
        val status = attendeeStatusWord(attendee.status)
        CalendarAttendee(
            email = email,
            name = attendee.name?.trim()?.ifBlank { null },
            status = AttendeeStatus.entries.first { it.wire == status },
            you = email != null && raw.ownerAccount != null && email.equals(raw.ownerAccount.trim(), ignoreCase = true),
        )
    }
    val organizerMail = organizerEmail(raw.organizer)
    val organizer = if (organizerMail == null && raw.organizerName.isNullOrBlank()) {
        null
    } else {
        CalendarOrganizer(organizerMail, raw.organizerName?.trim()?.ifBlank { null })
    }
    val note = CalendarNote(
        allDay = raw.allDay,
        attendees = attendees,
        calendar = raw.calendarName,
        change = CalendarChange.ADDED,
        changedAt = "1970-01-01T00:00:00Z",
        end = end,
        event = event,
        location = raw.location?.trim()?.ifBlank { null },
        notes = raw.description?.trim()?.ifBlank { null },
        occurrence = occurrence,
        organizer = organizer,
        repeats = classifyRepeats(raw.rrule),
        start = start,
        timeZone = zone,
        title = raw.title?.trim()?.ifBlank { null },
        url = null,
    )
    return NormalizedInstance(
        event = event,
        occurrence = occurrence,
        calendarId = raw.calendarId,
        calendarName = raw.calendarName,
        startEpochMs = raw.beginEpochMs,
        note = note,
        providerEventIds = setOfNotNull(raw.eventId, raw.masterEventId),
    )
}

fun removedNote(
    event: String,
    occurrence: String?,
    title: String?,
    calendar: String,
    changedAt: String,
): CalendarNote = CalendarNote(
    allDay = null,
    attendees = emptyList(),
    calendar = calendar,
    change = CalendarChange.REMOVED,
    changedAt = changedAt,
    end = null,
    event = event,
    location = null,
    notes = null,
    occurrence = occurrence,
    organizer = null,
    repeats = null,
    start = null,
    timeZone = null,
    title = title,
    url = null,
)

private fun utcDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toLocalDate().toString()

private fun formatZoned(epochMs: Long, zoneId: String?): Pair<String, String?> {
    val instant = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS)
    val zone = zoneId?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    if (zone == null) return formatChangedAt(instant, ZoneOffset.UTC) to null
    val offset = instant.atZone(zone).offset
    if (offset.totalSeconds % 60 != 0) return formatChangedAt(instant, ZoneOffset.UTC) to null
    return formatChangedAt(instant, zone) to zone.id
}
