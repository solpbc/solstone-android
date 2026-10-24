// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.calendar

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CalendarGoldenLinesTest {
    @Test
    fun eachGoldenLineIsTheEncoderOutputPlusANewline() {
        GOLDEN.forEach { (note, line) ->
            val file = encodeCalendarFiles(listOf(note)).single()
            assertContentEquals((line + "\n").toByteArray(Charsets.UTF_8), file.bytes)
        }
    }

    @Test
    fun anyInputOrderYieldsTheSameBytes() {
        val notes = GOLDEN.map { it.first }
        val forward = encodeCalendarFiles(notes).map { it.bytes }
        val backward = encodeCalendarFiles(notes.asReversed()).map { it.bytes }
        assertEquals(forward.size, backward.size)
        forward.zip(backward).forEach { (left, right) -> assertContentEquals(left, right) }
    }

    @Test
    fun sharedWindowSortsByEventThenOccurrence() {
        val shared = listOf(GOLDEN[1], GOLDEN[2], GOLDEN[3]).map { it.first }.asReversed()
        val file = encodeCalendarFiles(shared).single()
        val expected = listOf(GOLDEN[2].second, GOLDEN[3].second, GOLDEN[1].second).joinToString("\n", postfix = "\n")
        assertContentEquals(expected.toByteArray(Charsets.UTF_8), file.bytes)
    }

    @Test
    fun aRemovalFromTheLedgerIsGoldenLine5a() {
        val observed = Instant.parse("2026-10-22T14:05:12Z").toEpochMilli()
        val sent = CalendarIdentityRecord(
            event = "6CF25C80-68D2-44D9-B52C-6CAD5BEC8C10",
            occurrence = "",
            publishedFingerprint = contentFingerprint(GOLDEN[0].first),
            publishedChangedAt = "2026-10-19T09:42:00-06:00",
            publishedChangedAtEpochMs = Instant.parse("2026-10-19T15:42:00Z").toEpochMilli(),
            lastTitle = "design review",
            lastCalendar = "Work",
            lastAccount = "iCloud",
            holders = setOf(1),
            winningHolder = 1,
            missCount = 1,
            firstMissAt = observed - CALENDAR_MISS_GAP_MS,
            lastChange = "added",
        )
        val removed = reconcileScan(listOf(sent), emptyList(), setOf(1), ScanOutcome.COMPLETE, observed,
            ZoneId.of("America/Denver"), 1, 1, Long.MIN_VALUE).notes.single()
        assertEquals(GOLDEN[4].second, encodeCalendarLine(removed))
    }
}

/** Contract lines 1a and 5a are 1 and 5 with their account; 2-4 now carry `"account":null`. */
private val GOLDEN: List<Pair<CalendarNote, String>> = listOf(
    note(
        allDay = false,
        attendees = listOf(
            CalendarAttendee("owner@example.com", null, AttendeeStatus.TENTATIVE, true),
            CalendarAttendee("alex@example.com", "Alex Rivera", AttendeeStatus.ACCEPTED, false),
        ),
        calendar = "Work",
        change = CalendarChange.ADDED,
        changedAt = "2026-10-19T09:42:00-06:00",
        end = "2026-10-27T11:00:00-06:00",
        event = "6CF25C80-68D2-44D9-B52C-6CAD5BEC8C10",
        location = "room 4",
        notes = "bring the mocks",
        occurrence = null,
        organizer = CalendarOrganizer("alex@example.com", "Alex Rivera"),
        repeats = null,
        start = "2026-10-27T10:00:00-06:00",
        timeZone = "America/Denver",
        title = "design review",
        account = "iCloud",
    ) to """{"account":"iCloud","all_day":false,"attendees":[{"email":"alex@example.com","name":"Alex Rivera","status":"accepted","you":false},{"email":"owner@example.com","name":null,"status":"tentative","you":true}],"calendar":"Work","change":"added","changed_at":"2026-10-19T09:42:00-06:00","end":"2026-10-27T11:00:00-06:00","event":"6CF25C80-68D2-44D9-B52C-6CAD5BEC8C10","location":"room 4","notes":"bring the mocks","occurrence":null,"organizer":{"email":"alex@example.com","name":"Alex Rivera"},"repeats":null,"start":"2026-10-27T10:00:00-06:00","time_zone":"America/Denver","title":"design review","url":null,"v":1}""",
    note(
        allDay = false,
        attendees = emptyList(),
        calendar = "Work",
        change = CalendarChange.CHANGED,
        changedAt = "2026-10-20T16:30:00-06:00",
        end = "2026-10-21T09:45:00-06:00",
        event = "CCBF5C02-6D92-477A-AB25-1A2ED19E1D67",
        location = null,
        notes = null,
        occurrence = "2026-10-21T15:00:00Z",
        organizer = null,
        repeats = "every weekday",
        start = "2026-10-21T09:30:00-06:00",
        timeZone = "America/Denver",
        title = "standup",
    ) to """{"account":null,"all_day":false,"attendees":[],"calendar":"Work","change":"changed","changed_at":"2026-10-20T16:30:00-06:00","end":"2026-10-21T09:45:00-06:00","event":"CCBF5C02-6D92-477A-AB25-1A2ED19E1D67","location":null,"notes":null,"occurrence":"2026-10-21T15:00:00Z","organizer":null,"repeats":"every weekday","start":"2026-10-21T09:30:00-06:00","time_zone":"America/Denver","title":"standup","url":null,"v":1}""",
    note(
        allDay = true,
        attendees = emptyList(),
        calendar = "US Holidays",
        change = CalendarChange.ADDED,
        changedAt = "2026-10-20T16:31:00-06:00",
        end = "2026-11-01",
        event = "48fa6175-2399-3bac-960a-fff947563ba7",
        location = null,
        notes = null,
        occurrence = null,
        organizer = null,
        repeats = null,
        start = "2026-10-31",
        timeZone = null,
        title = "Halloween",
    ) to """{"account":null,"all_day":true,"attendees":[],"calendar":"US Holidays","change":"added","changed_at":"2026-10-20T16:31:00-06:00","end":"2026-11-01","event":"48fa6175-2399-3bac-960a-fff947563ba7","location":null,"notes":null,"occurrence":null,"organizer":null,"repeats":null,"start":"2026-10-31","time_zone":null,"title":"Halloween","url":null,"v":1}""",
    note(
        allDay = true,
        attendees = emptyList(),
        calendar = "Birthdays",
        change = CalendarChange.ADDED,
        changedAt = "2026-10-20T16:31:00-06:00",
        end = "2027-01-21",
        event = "701BCFB9-A70C-42C2-873F-34990264C973",
        location = null,
        notes = null,
        occurrence = "2027-01-20",
        organizer = null,
        repeats = "every year",
        start = "2027-01-20",
        timeZone = null,
        title = "Kate Bell's Birthday",
    ) to """{"account":null,"all_day":true,"attendees":[],"calendar":"Birthdays","change":"added","changed_at":"2026-10-20T16:31:00-06:00","end":"2027-01-21","event":"701BCFB9-A70C-42C2-873F-34990264C973","location":null,"notes":null,"occurrence":"2027-01-20","organizer":null,"repeats":"every year","start":"2027-01-20","time_zone":null,"title":"Kate Bell's Birthday","url":null,"v":1}""",
    note(
        allDay = null,
        attendees = emptyList(),
        calendar = "Work",
        change = CalendarChange.REMOVED,
        changedAt = "2026-10-22T08:05:12-06:00",
        end = null,
        event = "6CF25C80-68D2-44D9-B52C-6CAD5BEC8C10",
        location = null,
        notes = null,
        occurrence = null,
        organizer = null,
        repeats = null,
        start = null,
        timeZone = null,
        title = "design review",
        account = "iCloud",
    ) to """{"account":"iCloud","all_day":null,"attendees":[],"calendar":"Work","change":"removed","changed_at":"2026-10-22T08:05:12-06:00","end":null,"event":"6CF25C80-68D2-44D9-B52C-6CAD5BEC8C10","location":null,"notes":null,"occurrence":null,"organizer":null,"repeats":null,"start":null,"time_zone":null,"title":"design review","url":null,"v":1}""",
)

private fun note(
    allDay: Boolean?,
    attendees: List<CalendarAttendee>,
    calendar: String,
    change: CalendarChange,
    changedAt: String,
    end: String?,
    event: String,
    location: String?,
    notes: String?,
    occurrence: String?,
    organizer: CalendarOrganizer?,
    repeats: String?,
    start: String?,
    timeZone: String?,
    title: String?,
    account: String? = null,
): CalendarNote = CalendarNote(
    allDay = allDay,
    attendees = attendees,
    calendar = calendar,
    change = change,
    changedAt = changedAt,
    end = end,
    event = event,
    location = location,
    notes = notes,
    occurrence = occurrence,
    organizer = organizer,
    repeats = repeats,
    start = start,
    timeZone = timeZone,
    title = title,
    url = null,
    account = account,
)
