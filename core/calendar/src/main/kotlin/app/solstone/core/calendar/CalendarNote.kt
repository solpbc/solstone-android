// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.calendar

import app.solstone.core.pl.toJson
import app.solstone.core.pl.parseJson
import app.solstone.core.segment.sha256
import app.solstone.core.sources.CALENDAR_STREAM
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

const val CALENDAR_NOTE_VERSION = 1
const val CALENDAR_MEDIA_TYPE = "application/x-ndjson"
const val CALENDAR_MAX_LINES = 5_000
const val CALENDAR_MAX_BYTES = 8 * 1024 * 1024

private val NOTE_KEYS = listOf(
    "account",
    "all_day",
    "attendees",
    "calendar",
    "change",
    "changed_at",
    "end",
    "event",
    "location",
    "notes",
    "occurrence",
    "organizer",
    "repeats",
    "start",
    "time_zone",
    "title",
    "url",
    "v",
)

private val ATTENDEE_KEYS = listOf("email", "name", "status", "you")
private val ORGANIZER_KEYS = listOf("email", "name")

val CALENDAR_CHANGES = setOf("added", "changed", "removed")
val CALENDAR_ATTENDEE_STATUSES = setOf("accepted", "declined", "tentative", "pending", "unknown")
val CALENDAR_REPEATS = setOf("every weekday", "every year", "every day", "every week", "every month")

enum class CalendarChange(val wire: String) {
    ADDED("added"),
    CHANGED("changed"),
    REMOVED("removed"),
}

enum class AttendeeStatus(val wire: String) {
    ACCEPTED("accepted"),
    DECLINED("declined"),
    TENTATIVE("tentative"),
    PENDING("pending"),
    UNKNOWN("unknown"),
}

data class CalendarAttendee(
    val email: String?,
    val name: String?,
    val status: AttendeeStatus,
    val you: Boolean,
)

data class CalendarOrganizer(
    val email: String?,
    val name: String?,
)

data class CalendarNote(
    val v: Int = CALENDAR_NOTE_VERSION,
    val allDay: Boolean?,
    val attendees: List<CalendarAttendee>,
    val calendar: String,
    val change: CalendarChange,
    val changedAt: String,
    val end: String?,
    val event: String,
    val location: String?,
    val notes: String?,
    val occurrence: String?,
    val organizer: CalendarOrganizer?,
    val repeats: String?,
    val start: String?,
    val timeZone: String?,
    val title: String?,
    val url: String? = null,
    /** The name of the account the calendar belongs to. Rides with [calendar]; never part of the fingerprint. */
    val account: String? = null,
)

class CalendarWireException(message: String) : IllegalArgumentException(message)

data class CalendarFileBytes(
    val name: String,
    val bytes: ByteArray,
    val mediaType: String,
    val day: String,
    val segment: String,
    val sourceId: String,
)

fun attendeeStatusWord(providerStatus: Int): String = when (providerStatus) {
    1 -> AttendeeStatus.ACCEPTED.wire
    2 -> AttendeeStatus.DECLINED.wire
    3 -> AttendeeStatus.PENDING.wire
    4 -> AttendeeStatus.TENTATIVE.wire
    else -> AttendeeStatus.UNKNOWN.wire
}

fun encodeCalendarLine(note: CalendarNote): String = encodeCalendarObject(note.toWireMap())

/**
 * Encodes one object. Every note key must already be present. A bad version, a missing key, or a
 * value outside the note's enums is refused here, before any file is sealed.
 */
fun encodeCalendarObject(fields: Map<String, Any?>): String {
    val missing = NOTE_KEYS.filter { it !in fields }
    if (missing.isNotEmpty()) {
        throw CalendarWireException("missing key")
    }
    if (fields["v"] != CALENDAR_NOTE_VERSION) {
        throw CalendarWireException("bad version")
    }
    val change = fields["change"]
    if (change !in CALENDAR_CHANGES) {
        throw CalendarWireException("bad change")
    }
    val repeats = fields["repeats"]
    if (repeats != null && repeats !is String) {
        throw CalendarWireException("bad repeats")
    }
    val account = fields["account"]
    if (account != null && account !is String) {
        throw CalendarWireException("bad account")
    }
    val attendees = fields["attendees"] as? List<*>
        ?: throw CalendarWireException("bad attendees")
    attendees.forEach { item ->
        val map = item as? Map<*, *> ?: throw CalendarWireException("bad attendee")
        if (ATTENDEE_KEYS.any { it !in map }) throw CalendarWireException("missing key")
        if (map["status"] !in CALENDAR_ATTENDEE_STATUSES) throw CalendarWireException("bad status")
    }
    val organizer = fields["organizer"]
    if (organizer != null) {
        val map = organizer as? Map<*, *> ?: throw CalendarWireException("bad organizer")
        if (ORGANIZER_KEYS.any { it !in map }) throw CalendarWireException("missing key")
    }
    val ordered = linkedMapOf<String, Any?>()
    NOTE_KEYS.forEach { key -> ordered[key] = fields[key] }
    return toJson(ordered)
}

fun encodeCalendarFiles(notes: List<CalendarNote>): List<CalendarFileBytes> {
    if (notes.isEmpty()) return emptyList()
    val grouped = notes.groupBy { note ->
        val keys = calendarWindow(note.changedAt)
        Triple(keys.day, keys.segment, keys.startEpochMs)
    }
    return grouped.keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third })).flatMap { key ->
        val windowNotes = grouped.getValue(key)
        val keys = calendarWindow(windowNotes.first().changedAt)
        packLines(windowNotes).map { bytes ->
            CalendarFileBytes(
                name = "calendar_" + sha256(ByteArrayInputStream(bytes)) + ".jsonl",
                bytes = bytes,
                mediaType = CALENDAR_MEDIA_TYPE,
                day = keys.day,
                segment = keys.segment,
                sourceId = CALENDAR_STREAM,
            )
        }
    }
}

private fun packLines(notes: List<CalendarNote>): List<ByteArray> {
    val lines = notes.sortedWith(noteOrder).map { note ->
        (encodeCalendarLine(note) + "\n").toByteArray(Charsets.UTF_8)
    }
    val files = mutableListOf<ByteArray>()
    var current = ByteArrayOutputStream()
    var count = 0
    for (line in lines) {
        if (line.size > CALENDAR_MAX_BYTES) throw CalendarWireException("note exceeds file limit")
        val overflow = count > 0 && (count >= CALENDAR_MAX_LINES || current.size() + line.size > CALENDAR_MAX_BYTES)
        if (overflow) {
            files += current.toByteArray()
            current = ByteArrayOutputStream()
            count = 0
        }
        current.write(line)
        count += 1
    }
    if (count > 0) files += current.toByteArray()
    return files
}

private val noteOrder = compareBy<CalendarNote>({ it.event }, { it.occurrence != null }, { it.occurrence ?: "" })

private val attendeeOrder = Comparator<CalendarAttendee> { left, right ->
    val byEmail = compareNullable(left.email, right.email)
    if (byEmail != 0) byEmail else {
        val byName = compareNullable(left.name, right.name)
        if (byName != 0) byName else {
            val byStatus = left.status.wire.compareTo(right.status.wire)
            if (byStatus != 0) byStatus else left.you.compareTo(right.you)
        }
    }
}

private fun compareNullable(left: String?, right: String?): Int = when {
    left == null && right == null -> 0
    left == null -> -1
    right == null -> 1
    else -> left.compareTo(right)
}

private fun CalendarNote.toWireMap(): LinkedHashMap<String, Any?> = linkedMapOf(
    "account" to account,
    "all_day" to allDay,
    "attendees" to attendees.sortedWith(attendeeOrder).map { it.toWireMap() },
    "calendar" to calendar,
    "change" to change.wire,
    "changed_at" to changedAt,
    "end" to end,
    "event" to event,
    "location" to location,
    "notes" to notes,
    "occurrence" to occurrence,
    "organizer" to organizer?.toWireMap(),
    "repeats" to repeats,
    "start" to start,
    "time_zone" to timeZone,
    "title" to title,
    "url" to url,
    "v" to v,
)

private fun CalendarAttendee.toWireMap(): LinkedHashMap<String, Any?> = linkedMapOf(
    "email" to email,
    "name" to name,
    "status" to status.wire,
    "you" to you,
)

private fun CalendarOrganizer.toWireMap(): LinkedHashMap<String, Any?> = linkedMapOf(
    "email" to email,
    "name" to name,
)

fun contentFingerprint(note: CalendarNote): String {
    val fields = note.toWireMap()
    fields.remove("change")
    fields.remove("changed_at")
    // An account difference alone is never a change, and an upgraded phone keeps every fingerprint
    // it already stored: the account first goes out with an event's next real change.
    fields.remove("account")
    return sha256(ByteArrayInputStream(toJson(fields).toByteArray(Charsets.UTF_8)))
}

/** Reads the app's hash-verified private snapshot, through the same typed wire encoder. */
fun decodeCalendarLine(line: String): CalendarNote {
    val m = parseJson(line) as? Map<*, *> ?: throw CalendarWireException("bad note")
    fun text(key: String): String? = m[key] as? String
    require((m["v"] as? Number)?.toInt() == 1)
    val attendees = (m["attendees"] as List<*>).map { item ->
        val a = item as Map<*, *>
        CalendarAttendee(a["email"] as? String, a["name"] as? String,
            AttendeeStatus.entries.first { it.wire == a["status"] }, a["you"] as Boolean)
    }
    val organizer = (m["organizer"] as? Map<*, *>)?.let {
        CalendarOrganizer(it["email"] as? String, it["name"] as? String)
    }
    return CalendarNote(allDay = m["all_day"] as? Boolean, attendees = attendees,
        calendar = text("calendar") ?: throw CalendarWireException("bad calendar"),
        change = CalendarChange.entries.first { it.wire == text("change") },
        changedAt = text("changed_at") ?: throw CalendarWireException("bad time"),
        end = text("end"), event = text("event") ?: throw CalendarWireException("bad event"),
        location = text("location"), notes = text("notes"), occurrence = text("occurrence"),
        organizer = organizer, repeats = text("repeats"), start = text("start"),
        timeZone = text("time_zone"), title = text("title"), url = text("url"),
        account = text("account")).also { encodeCalendarLine(it) }
}
