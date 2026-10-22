// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarBehaviorTest {
    private val denver = ZoneId.of("America/Denver")

    @Test
    fun nullsSortBeforeAttendeesAndStayInTheLine() {
        val line = encodeCalendarLine(
            CalendarNote(
                allDay = false,
                attendees = listOf(
                    CalendarAttendee("zoe@example.com", "Zoe", AttendeeStatus.ACCEPTED, false),
                    CalendarAttendee(null, "No Mail", AttendeeStatus.UNKNOWN, false),
                    CalendarAttendee("zoe@example.com", null, AttendeeStatus.DECLINED, false),
                ),
                calendar = "Work",
                change = CalendarChange.ADDED,
                changedAt = "2026-10-19T09:42:00-06:00",
                end = null,
                event = "e",
                location = null,
                notes = null,
                occurrence = null,
                organizer = null,
                repeats = null,
                start = "2026-10-19T09:00:00-06:00",
                timeZone = "America/Denver",
                title = null,
            ),
        )
        val emails = line.substringAfter("\"attendees\":[").substringBefore("]")
        assertTrue(emails.indexOf("\"email\":null") < emails.indexOf("zoe@example.com"))
        assertTrue(emails.indexOf("\"name\":null") < emails.lastIndexOf("Zoe"))
        assertTrue(line.contains("\"location\":null"))
        assertTrue(line.contains("\"organizer\":null"))
        assertTrue(line.contains("\"url\":null"))
    }

    @Test
    fun windowUsesTheLinesOwnOffsetOnTheFiveMinuteGrid() {
        val keys = calendarWindow("2026-10-19T09:42:00-06:00")
        assertEquals("20261019", keys.day)
        assertEquals("094000_300", keys.segment)
    }

    @Test
    fun secondsOffsetIsWrittenInUtc() {
        val zone = ZoneOffset.ofTotalSeconds(-(5 * 3600 + 59 * 60 + 30))
        val instant = Instant.parse("2026-10-19T15:42:30Z")
        val written = formatChangedAt(instant, zone)
        assertEquals("2026-10-19T15:42:30Z", written)
        val keys = calendarWindow(written)
        assertEquals("20261019", keys.day)
        assertEquals("154000_300", keys.segment)
    }

    @Test
    fun splitsAtFiveThousandLinesAndEightMebibytesAndNamesBySha256() {
        val tiny = (0 until 5_001).map { index -> sample(event = "%05d".format(index)) }
        val tinyFiles = encodeCalendarFiles(tiny)
        assertEquals(2, tinyFiles.size)
        assertEquals(5_000, tinyFiles[0].bytes.count { it == '\n'.code.toByte() })
        assertEquals(1, tinyFiles[1].bytes.count { it == '\n'.code.toByte() })
        tinyFiles.forEach { file ->
            assertTrue(file.bytes.size <= CALENDAR_MAX_BYTES)
            assertEquals("calendar_" + sha(file.bytes) + ".jsonl", file.name)
            assertEquals(CALENDAR_MEDIA_TYPE, file.mediaType)
            assertTrue(file.bytes.last() == '\n'.code.toByte())
        }
        val fat = (0 until 9).map { index -> sample(event = "f$index", notes = "n".repeat(1_000_000)) }
        val fatFiles = encodeCalendarFiles(fat)
        assertTrue(fatFiles.size > 1)
        assertTrue(fatFiles.all { it.bytes.size <= CALENDAR_MAX_BYTES })
        val reversed = encodeCalendarFiles(fat.asReversed())
        assertEquals(fatFiles.size, reversed.size)
        fatFiles.zip(reversed).forEach { (left, right) -> assertContentEquals(left.bytes, right.bytes) }
    }

    @Test
    fun encoderRefusesABadVersionAMissingKeyOrABadEnum() {
        assertFailsWith<CalendarWireException> { encodeCalendarLine(sample().copy(v = 2)) }
        assertTrue(encodeCalendarLine(sample().copy(repeats = "every other week")).contains("every other week"))
        assertFailsWith<CalendarWireException> { encodeCalendarFiles(listOf(sample(notes = "n".repeat(CALENDAR_MAX_BYTES)))) }
        val map = linkedMapOf<String, Any?>(
            "all_day" to false,
            "attendees" to emptyList<Any>(),
            "calendar" to "Work",
            "change" to "added",
            "changed_at" to "2026-10-19T09:42:00-06:00",
            "end" to null,
            "event" to "e",
            "location" to null,
            "notes" to null,
            "occurrence" to null,
            "organizer" to null,
            "repeats" to null,
            "start" to null,
            "time_zone" to null,
            "title" to null,
            "url" to null,
        )
        assertFailsWith<CalendarWireException> { encodeCalendarObject(map) }
        assertFailsWith<CalendarWireException> { encodeCalendarObject(map + ("v" to 1) + ("change" to "nope")) }
    }

    @Test
    fun identityPrefersUidStripsRidAndFallsBackToADigest() {
        assertEquals("uid", calendarEventIdentity("uid/RID=4", false, null, 9, 1, "ns"))
        assertEquals("master", calendarEventIdentity(null, true, "master/RID=2", 9, 1, "ns"))
        val fallback = calendarEventIdentity(null, false, null, 9, 1, "ns")
        assertTrue(fallback.startsWith("solstone:calendar:"))
        assertTrue(fallback.removePrefix("solstone:calendar:").length == 64)
        assertTrue(fallback != "9")
        assertEquals(fallback, calendarEventIdentity(null, false, null, 9, 1, "ns"))
        assertTrue(fallback != calendarEventIdentity(null, false, null, 10, 1, "ns"))
    }

    @Test
    fun repeatsOnlyMatchTheBareForms() {
        assertEquals("every weekday", classifyRepeats("FREQ=WEEKLY;BYDAY=FR,MO,TU,WE,TH"))
        assertEquals("every year", classifyRepeats("RRULE:FREQ=YEARLY"))
        assertEquals("every day", classifyRepeats("FREQ=DAILY"))
        assertEquals("every week", classifyRepeats("FREQ=WEEKLY"))
        assertEquals("every month", classifyRepeats("FREQ=MONTHLY"))
        assertNull(classifyRepeats("FREQ=WEEKLY;BYDAY=MO"))
        assertNull(classifyRepeats("FREQ=YEARLY;INTERVAL=1"))
        assertNull(classifyRepeats("FREQ=YEARLY;BYMONTH=1"))
        assertNull(classifyRepeats(null))
    }

    @Test
    fun movedCancelledAllDayDstAndZones() {
        val moved = normalizeInstance(raw(recurring = true, original = instant("2026-10-21T15:00:00Z"), begin = instant("2026-10-21T15:30:00Z"), end = instant("2026-10-21T15:45:00Z"), zone = "America/Denver", rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"))
        assertEquals("2026-10-21T15:00:00Z", moved!!.occurrence)
        assertEquals("2026-10-21T09:30:00-06:00", moved.note.start)
        assertEquals("every weekday", moved.note.repeats)
        assertNull(normalizeInstance(raw(cancelled = true)))

        val holiday = normalizeInstance(raw(allDay = true, begin = instant("2026-10-31T00:00:00Z"), end = instant("2026-11-01T00:00:00Z"), title = "Halloween"))
        assertEquals("2026-10-31", holiday!!.note.start)
        assertEquals("2026-11-01", holiday.note.end)
        assertNull(holiday.note.timeZone)
        assertEquals(true, holiday.note.allDay)

        val winter = normalizeInstance(raw(begin = instant("2026-01-15T16:00:00Z"), end = instant("2026-01-15T17:00:00Z"), zone = "America/Denver"))
        assertEquals("2026-01-15T09:00:00-07:00", winter!!.note.start)
        assertEquals("America/Denver", winter.note.timeZone)

        val missing = normalizeInstance(raw(begin = instant("2026-10-21T15:30:00Z"), end = instant("2026-10-21T15:00:00Z"), zone = "Not/AZone"))
        assertTrue(missing!!.note.start!!.endsWith("Z"))
        assertNull(missing.note.end)
        assertNull(missing.note.timeZone)

        val endZone = normalizeInstance(
            raw(begin = instant("2026-01-15T16:00:00Z"), end = instant("2026-01-15T17:00:00Z"), zone = "America/Denver").copy(
                eventEndTimeZone = "America/New_York",
            ),
        )
        assertEquals("2026-01-15T12:00:00-05:00", endZone!!.note.end)
    }

    @Test
    fun unchangedReplayKeepsChangedAtAndAChangeAdvancesByWholeSeconds() {
        val zone = denver
        val first = reconcileScan(emptyList(), listOf(seen("e", "Work", 1)), setOf(1), ScanOutcome.COMPLETE, instant("2026-10-19T15:42:00Z"), zone, 1, 1, Long.MIN_VALUE)
        assertEquals("2026-10-19T09:42:00-06:00", first.notes.single().changedAt)
        val sealed = publish(first)
        val again = reconcileScan(sealed, listOf(seen("e", "Work", 1)), setOf(1), ScanOutcome.COMPLETE, instant("2026-10-19T16:10:00Z"), zone, 1, 1, Long.MIN_VALUE)
        assertTrue(again.notes.isEmpty())
        assertEquals(first.notes.single().changedAt, sealed.single().publishedChangedAt)

        val renamed = reconcileScan(sealed, listOf(seen("e", "Work", 1, title = "later")), setOf(1), ScanOutcome.COMPLETE, instant("2026-10-19T15:42:00.400Z"), zone, 1, 1, Long.MIN_VALUE)
        assertEquals("2026-10-19T09:42:01-06:00", renamed.notes.single().changedAt)
    }

    @Test
    fun twoHoldersUncheckAndRename() {
        val zone = denver
        val both = reconcileScan(
            emptyList(),
            listOf(seen("e", "Work", 1), seen("e", "Home", 2, title = "e")),
            setOf(1, 2),
            ScanOutcome.COMPLETE,
            instant("2026-10-19T15:42:00Z"),
            zone,
            1,
            1,
            Long.MIN_VALUE,
        )
        assertEquals(1, both.notes.size)
        assertEquals("Work", both.notes.single().calendar)
        var identities = publish(both)
        identities = dropCalendar(identities, 1)
        val afterUncheck = reconcileScan(identities, listOf(seen("e", "Home", 2)), setOf(2), ScanOutcome.COMPLETE, instant("2026-10-19T16:00:00Z"), zone, 1, 1, Long.MIN_VALUE)
        assertTrue(afterUncheck.notes.isEmpty())
        val renamed = reconcileScan(identities, listOf(seen("e", "Renamed", 1), seen("e", "Work", 2)), setOf(1, 2), ScanOutcome.COMPLETE, instant("2026-10-19T16:00:00Z"), zone, 1, 1, Long.MIN_VALUE)
        assertEquals("Renamed", renamed.notes.single().calendar)
        assertEquals(CalendarChange.CHANGED, renamed.notes.single().change)
    }

    @Test
    fun removalNeedsTwoCompleteMissesTenMinutesApartAndAPresentReadClearsThem() {
        val zone = denver
        var identities = publish(reconcileScan(emptyList(), listOf(seen("e", "Work", 1, title = "design review")), setOf(1), ScanOutcome.COMPLETE, t0, zone, 1, 1, Long.MIN_VALUE))
        val firstMiss = reconcileScan(identities, emptyList(), setOf(1), ScanOutcome.COMPLETE, t0 + 60_000, zone, 1, 1, Long.MIN_VALUE)
        assertTrue(firstMiss.notes.isEmpty())
        assertEquals(1, firstMiss.identities.single().missCount)
        val tooSoon = reconcileScan(firstMiss.identities, emptyList(), setOf(1), ScanOutcome.COMPLETE, t0 + 9 * 60_000, zone, 1, 1, Long.MIN_VALUE)
        assertTrue(tooSoon.notes.isEmpty())
        val removed = reconcileScan(firstMiss.identities, emptyList(), setOf(1), ScanOutcome.COMPLETE, t0 + 60_000 + CALENDAR_MISS_GAP_MS, zone, 1, 1, Long.MIN_VALUE)
        assertEquals(CalendarChange.REMOVED, removed.notes.single().change)
        assertEquals("design review", removed.notes.single().title)
        assertEquals("Work", removed.notes.single().calendar)
        assertNull(removed.notes.single().start)
        assertTrue(removed.notes.single().attendees.isEmpty())

        val cleared = reconcileScan(firstMiss.identities, listOf(seen("e", "Work", 1, title = "design review")), setOf(1), ScanOutcome.COMPLETE, t0 + 11 * 60_000, zone, 1, 1, Long.MIN_VALUE)
        assertEquals(0, cleared.identities.single().missCount)
        assertNull(cleared.identities.single().firstMissAt)
        assertTrue(cleared.notes.isEmpty())
    }

    @Test
    fun failedAccessLostPauseRevokeAndUncheckAreNotMisses() {
        val zone = denver
        val identities = publish(reconcileScan(emptyList(), listOf(seen("e", "Work", 1)), setOf(1), ScanOutcome.COMPLETE, t0, zone, 1, 1, Long.MIN_VALUE))
        listOf(ScanOutcome.FAILED, ScanOutcome.ACCESS_LOST, ScanOutcome.PAUSED, ScanOutcome.CANCELLED, ScanOutcome.PARTIAL, ScanOutcome.STORAGE_FAILED).forEach { outcome ->
            val result = reconcileScan(identities, emptyList(), setOf(1), outcome, t0 + 20 * 60_000, zone, 1, 1, Long.MIN_VALUE)
            assertTrue(result.discarded)
            assertEquals(0, result.identities.single().missCount)
            assertTrue(!result.advanceLastSuccess)
        }
        val unchecked = reconcileScan(dropCalendar(identities, 1), emptyList(), emptySet(), ScanOutcome.COMPLETE, t0 + 20 * 60_000, zone, 1, 1, Long.MIN_VALUE)
        assertTrue(unchecked.notes.isEmpty())
        assertEquals(0, unchecked.identities.single().missCount)
    }

    @Test
    fun aFailedMoveLookupIsNotAMissAndAMoveIsPresent() {
        val zone = denver
        val identities = publish(reconcileScan(emptyList(), listOf(seen("e", "Work", 1)), setOf(1), ScanOutcome.COMPLETE, t0, zone, 1, 1, Long.MIN_VALUE))
        val failedLookup = reconcileScan(identities, emptyList(), setOf(1), ScanOutcome.COMPLETE, t0 + 20 * 60_000, zone, 1, 1, Long.MIN_VALUE) {
            MoveLookup(readable = false, stillPresent = false)
        }
        assertEquals(0, failedLookup.identities.single().missCount)
        val moved = reconcileScan(identities, emptyList(), setOf(1), ScanOutcome.COMPLETE, t0 + 20 * 60_000, zone, 1, 1, Long.MIN_VALUE) {
            MoveLookup(readable = true, stillPresent = true, seen = seen("e", "Work", 1, title = "moved"))
        }
        assertEquals(0, moved.identities.single().missCount)
        assertEquals("moved", moved.notes.single().title)
    }

    @Test
    fun supersededTokenAndAnOlderScanDoNotPublish() {
        val zone = denver
        val newer = publish(reconcileScan(emptyList(), listOf(seen("e", "Work", 1, title = "new")), setOf(1), ScanOutcome.COMPLETE, t0 + 60_000, zone, 2, 2, Long.MIN_VALUE))
        val stale = reconcileScan(newer, listOf(seen("e", "Work", 1, title = "old")), setOf(1), ScanOutcome.COMPLETE, t0, zone, 2, 2, Long.MIN_VALUE)
        assertTrue(stale.notes.isEmpty())
        assertEquals("new", newer.single().lastTitle)
        val wrongToken = reconcileScan(newer, listOf(seen("e", "Other", 1, title = "x")), setOf(1), ScanOutcome.COMPLETE, t0 + 120_000, zone, 1, 2, Long.MIN_VALUE)
        assertTrue(wrongToken.discarded)
        assertTrue(wrongToken.notes.isEmpty())
        assertEquals(newer, wrongToken.identities)
    }

    @Test
    fun crashBeforeSealDoesNotAdvanceTheLedgerAndReopenSealsTheSameBytesOnce() {
        val zone = denver
        val planned = reconcileScan(emptyList(), listOf(seen("e", "Work", 1, title = "design review")), setOf(1), ScanOutcome.COMPLETE, t0, zone, 1, 1, Long.MIN_VALUE)
        val staged = stageNotes(planned.identities, emptyList(), planned.notes)
        assertNull(staged.identities.single().publishedFingerprint)
        assertTrue(staged.intents.single().bytes.isNotEmpty())
        assertTrue(!staged.intents.single().sealed)
        val sealedBytes = staged.intents.single().bytes.copyOf()
        val steps = reopenSteps(staged.intents) { false }
        assertEquals(true, steps.single().seal)
        assertContentEquals(sealedBytes, steps.single().intent.bytes)
        val durable = completeSeal(staged.identities, staged.intents, staged.intents.single().id)
        assertEquals(planned.notes.single().let(::contentFingerprint), durable.identities.single().publishedFingerprint)
        assertTrue(durable.intents.single().sealed)
        assertTrue(reopenSteps(durable.intents) { true }.isEmpty())
        val present = reopenSteps(staged.intents) { true }
        assertEquals(false, present.single().seal)
        assertTrue(reopenSteps(emptyList()) { false }.isEmpty())
    }

    @Test
    fun routineWindowDoesNotImportBelowTheConfirmedBoundAndNoneBeforeTodayMoves() {
        val zone = denver
        val today = LocalDate.of(2026, 10, 22)
        val bound = confirmedLowerBound(HistoryKind.DAYS_30, today, zone, today.minusDays(30).toEpochDay())
        val old = reconcileScan(
            emptyList(),
            listOf(seen("old", "Work", 1, start = bound - 1)),
            setOf(1),
            ScanOutcome.COMPLETE,
            t0,
            zone,
            1,
            1,
            bound,
        )
        assertTrue(old.notes.isEmpty())
        val todayStart = confirmedLowerBound(HistoryKind.NONE_BEFORE_TODAY, today, zone, today.toEpochDay())
        val tomorrow = confirmedLowerBound(HistoryKind.NONE_BEFORE_TODAY, today.plusDays(1), zone, today.toEpochDay())
        assertEquals(todayStart, tomorrow)
        val window = routineReadWindow(t0, zone, t0 - 40L * 24 * 60 * 60 * 1000)
        assertTrue(window.first <= t0 - 40L * 24 * 60 * 60 * 1000)
        assertTrue(window.second > t0)
        val slice = nextDeepSlice(window.first, zone, window.second)
        assertTrue(slice != null && slice.second > slice.first)
    }

    @Test
    fun attendeeStatusWordsMatchTheProviderInts() {
        assertEquals("accepted", attendeeStatusWord(1))
        assertEquals("declined", attendeeStatusWord(2))
        assertEquals("pending", attendeeStatusWord(3))
        assertEquals("tentative", attendeeStatusWord(4))
        assertEquals("unknown", attendeeStatusWord(0))
        assertEquals("unknown", attendeeStatusWord(9))
    }

    @Test
    fun aReadEmptyPreviousCalendarAllowsARealMoveWithoutAToggleRename() {
        val initial = publish(reconcileScan(emptyList(), listOf(seen("e", "A", 1)), setOf(1, 2),
            ScanOutcome.COMPLETE, t0, denver, 1, 1, Long.MIN_VALUE))
        val moved = reconcileScan(initial, listOf(seen("e", "B", 2)), setOf(1, 2),
            ScanOutcome.COMPLETE, t0 + 1000, denver, 1, 1, Long.MIN_VALUE)
        assertEquals("B", moved.notes.single().calendar)
        assertEquals(CalendarChange.CHANGED, moved.notes.single().change)
        assertEquals(2, moved.identities.single().nameHolder)
        val unchecked = reconcileScan(dropCalendar(initial, 1), listOf(seen("e", "B", 2)), setOf(2),
            ScanOutcome.COMPLETE, t0 + 1000, denver, 1, 1, Long.MIN_VALUE)
        assertTrue(unchecked.notes.isEmpty())
    }

    @Test
    fun fallbackItemsAndSeriesKeepIdentityAcrossMovesAndExceptions() {
        val first = raw().copy(uid2445 = null)
        val moved = first.copy(beginEpochMs = first.beginEpochMs + 86_400_000L)
        assertEquals(normalizeInstance(first)!!.event, normalizeInstance(moved)!!.event)
        val master = first.copy(recurring = true, rrule = "FREQ=WEEKLY")
        val exception = master.copy(eventId = 8, masterEventId = master.eventId, exception = true,
            originalInstanceEpochMs = master.beginEpochMs, beginEpochMs = master.beginEpochMs + 86_400_000L)
        val series = normalizeInstance(master)!!
        val occurrence = normalizeInstance(exception)!!
        assertEquals(series.event, occurrence.event)
        assertEquals(series.occurrence, occurrence.occurrence)
        assertEquals("2026-10-21T15:30:00Z", series.occurrence)
        assertEquals("master", calendarEventIdentity("exception-uid", true, "master", 8, 1, "ns"))
    }

    @Test
    fun previewPagesDoNotAccumulateMissesForOtherPagesAndOldEventsCanLeaveTheReadWindow() {
        val initial = publish(reconcileScan(emptyList(), listOf(seen("old", "A", 1)), setOf(1),
            ScanOutcome.COMPLETE, t0, denver, 1, 1, Long.MIN_VALUE))
        val page = reconcileScan(initial, listOf(seen("new", "A", 1)), setOf(1),
            ScanOutcome.COMPLETE, t0 + CALENDAR_MISS_GAP_MS, denver, 1, 1, Long.MIN_VALUE,
            detectMissing = { false })
        assertEquals(0, page.identities.first { it.event == "old" }.missCount)
        var lookedUp = false
        val outside = reconcileScan(initial, emptyList(), setOf(1), ScanOutcome.COMPLETE,
            t0 + CALENDAR_MISS_GAP_MS, denver, 1, 1, Long.MIN_VALUE,
            detectMissing = { it.lastStartEpochMs!! >= t0 + 1 },
            moveLookup = { lookedUp = true; MoveLookup(true, false) })
        assertTrue(!lookedUp)
        assertTrue(outside.notes.isEmpty())
    }

    @Test
    fun repeatedWallClockWindowsStaySeparateAndAttendeeTiesAreDeterministic() {
        val notes = listOf(sample("before").copy(changedAt = "2026-11-01T01:42:00-06:00"),
            sample("after").copy(changedAt = "2026-11-01T01:42:00-07:00"))
        assertEquals(2, encodeCalendarFiles(notes).size)
        assertEquals("094000_300", calendarWindow("2026-10-19T09:42:00+00:01").segment)
        val people = listOf(CalendarAttendee("a@example.com", "A", AttendeeStatus.ACCEPTED, false),
            CalendarAttendee("a@example.com", "A", AttendeeStatus.DECLINED, true))
        assertEquals(encodeCalendarLine(sample().copy(attendees = people)),
            encodeCalendarLine(sample().copy(attendees = people.asReversed())))
    }

    private fun publish(result: ReconcileResult): List<CalendarIdentityRecord> {
        val staged = stageNotes(result.identities, emptyList(), result.notes)
        return result.notes.fold(staged) { state, _ ->
            val id = state.intents.first { !it.sealed }.id
            val done = completeSeal(state.identities, state.intents, id)
            StageResult(done.identities, done.intents)
        }.identities.ifEmpty { result.identities }
    }

    private fun sample(event: String = "e", notes: String? = null) = CalendarNote(
        allDay = false,
        attendees = emptyList(),
        calendar = "Work",
        change = CalendarChange.ADDED,
        changedAt = "2026-10-19T09:42:00-06:00",
        end = null,
        event = event,
        location = null,
        notes = notes,
        occurrence = null,
        organizer = null,
        repeats = null,
        start = "2026-10-19T09:00:00-06:00",
        timeZone = "America/Denver",
        title = "t",
    )

    private fun seen(
        event: String,
        calendar: String,
        calendarId: Long,
        title: String = "t",
        start: Long = t0,
    ): SeenEvent {
        val note = sample(event).copy(calendar = calendar, title = title, changedAt = "2026-10-19T09:42:00-06:00")
        return SeenEvent(event, null, calendarId, calendar, start, note)
    }

    private fun raw(
        recurring: Boolean = false,
        original: Long? = null,
        begin: Long = instant("2026-10-21T15:30:00Z"),
        end: Long? = instant("2026-10-21T16:30:00Z"),
        zone: String? = "America/Denver",
        rrule: String? = null,
        allDay: Boolean = false,
        title: String? = "standup",
        cancelled: Boolean = false,
    ) = RawCalendarInstance(
        calendarId = 1,
        calendarName = "Work",
        ownerAccount = "owner@example.com",
        eventId = 4,
        uid2445 = "uid",
        masterUid = null,
        exception = false,
        recurring = recurring,
        originalInstanceEpochMs = original,
        beginEpochMs = begin,
        endEpochMs = end,
        allDay = allDay,
        eventTimeZone = zone,
        eventEndTimeZone = null,
        title = title,
        location = null,
        description = null,
        rrule = rrule,
        organizer = "mailto:alex@example.com",
        organizerName = "Alex",
        cancelled = cancelled,
        attendees = listOf(RawAttendee("owner@example.com", null, 4), RawAttendee("alex@example.com", "Alex", 1)),
        fallbackNamespace = "ns",
    )

    private fun instant(text: String): Long = Instant.parse(text).toEpochMilli()

    private fun sha(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val t0 = instant("2026-10-19T15:42:00Z")
}
