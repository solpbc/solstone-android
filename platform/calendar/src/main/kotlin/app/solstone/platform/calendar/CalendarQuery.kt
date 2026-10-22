// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.calendar

import app.solstone.core.calendar.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import app.solstone.core.calendar.RawAttendee
import app.solstone.core.calendar.RawCalendarInstance

const val CALENDAR_COL_ID = "_id"
const val CALENDAR_COL_CALENDAR_ID = "calendar_id"
const val CALENDAR_COL_DISPLAY_NAME = "calendar_displayName"
const val CALENDAR_COL_OWNER = "ownerAccount"
const val CALENDAR_COL_ACCOUNT_TYPE = "account_type"
const val CALENDAR_COL_VISIBLE = "visible"
const val CALENDAR_COL_EVENT_ID = "event_id"
const val CALENDAR_COL_TITLE = "title"
const val CALENDAR_COL_LOCATION = "eventLocation"
const val CALENDAR_COL_DESCRIPTION = "description"
const val CALENDAR_COL_BEGIN = "begin"
const val CALENDAR_COL_END = "end"
const val CALENDAR_COL_ALL_DAY = "allDay"
const val CALENDAR_COL_RRULE = "rrule"
const val CALENDAR_COL_RDATE = "rdate"
const val CALENDAR_COL_TIMEZONE = "eventTimezone"
const val CALENDAR_COL_END_TIMEZONE = "eventEndTimezone"
const val CALENDAR_COL_STATUS = "eventStatus"
const val CALENDAR_COL_ORIGINAL_INSTANCE = "originalInstanceTime"
const val CALENDAR_COL_ORIGINAL_ID = "original_id"
const val CALENDAR_COL_UID = "uid2445"
const val CALENDAR_COL_ORGANIZER = "organizer"
const val CALENDAR_COL_ATTENDEE_EMAIL = "attendeeEmail"
const val CALENDAR_COL_ATTENDEE_NAME = "attendeeName"
const val CALENDAR_COL_ATTENDEE_STATUS = "attendeeStatus"

const val CALENDAR_STATUS_CANCELED = 2

enum class CalendarQueryTarget { CALENDARS, INSTANCES, EVENTS, ATTENDEES }

data class CalendarQuery(
    val target: CalendarQueryTarget,
    val projection: Array<String>,
    val selection: String?,
    val selectionArgs: Array<String>?,
    val begin: Long? = null,
    val end: Long? = null,
    val sortOrder: String? = null,
)

interface CalendarCursor : AutoCloseable {
    fun moveToNext(): Boolean
    fun column(name: String): Int
    fun string(index: Int): String?
    fun long(index: Int): Long
    fun int(index: Int): Int
    fun isNull(index: Int): Boolean
    override fun close()
}

/** Production binds this to [android.content.ContentResolver.query]. It does not call `Instances.query`. */
interface CalendarQuerySurface {
    fun query(query: CalendarQuery): CalendarCursor?
}

data class ProviderCalendar(
    val id: Long,
    val name: String,
    val ownerAccount: String?,
    val accountType: String?,
    val visible: Boolean,
)

sealed interface CalendarScan {
    data class Ok(
        val calendars: List<ProviderCalendar>,
        val instances: List<RawCalendarInstance>,
    ) : CalendarScan

    data object Failed : CalendarScan
    data object Cancelled : CalendarScan
    data object AccessLost : CalendarScan
}

/** A null cursor, unavailable selected calendar, or partial read is never an empty result. */
class CalendarReader(private val surface: CalendarQuerySurface) {
    fun read(begin: Long, end: Long, calendarIds: Set<Long>, fallbackNamespace: String,
        cancelled: () -> Boolean = { false }): CalendarScan {
        return try {
            if (cancelled()) return CalendarScan.Cancelled
            val calendars = readCalendars() ?: return CalendarScan.Failed
            if (!calendars.map { it.id }.containsAll(calendarIds)) return CalendarScan.Failed
            if (calendarIds.isEmpty()) return CalendarScan.Ok(calendars, emptyList())
            val instances = readRows(CalendarQuery(CalendarQueryTarget.INSTANCES, instanceProjection,
                "$CALENDAR_COL_CALENDAR_ID IN (${marks(calendarIds.size)})", calendarIds.map { it.toString() }.toTypedArray(),
                begin, end), calendars.associateBy { it.id }, fallbackNamespace, cancelled) ?: return CalendarScan.Failed
            if (cancelled()) return CalendarScan.Cancelled
            CalendarScan.Ok(calendars, withAttendees(instances, cancelled) ?: return CalendarScan.Failed)
        } catch (_: ScanCancelled) { CalendarScan.Cancelled }
          catch (_: SecurityException) { CalendarScan.AccessLost }
          catch (_: Exception) { CalendarScan.Failed }
    }

    /** The provider's actual oldest start, without an arbitrary history cutoff. */
    fun earliestStart(calendarIds: Set<Long>): Long? = try {
        if (calendarIds.isEmpty()) null else {
            val cursor = surface.query(CalendarQuery(CalendarQueryTarget.EVENTS, arrayOf("dtstart"),
                "$CALENDAR_COL_CALENDAR_ID IN (${marks(calendarIds.size)}) AND dtstart IS NOT NULL",
                calendarIds.map { it.toString() }.toTypedArray(), sortOrder = "dtstart ASC"))
            cursor?.use { rows -> if (rows.moveToNext()) rows.long("dtstart") else System.currentTimeMillis() }
        }
    } catch (_: Exception) { null }

    /** Resolve a missing known item through Events; expand only its original occurrence's range. */
    fun lookup(identity: CalendarIdentityRecord, selected: Set<Long>, namespace: String,
        consentZone: ZoneId = ZoneId.systemDefault(), cancelled: () -> Boolean = { false }): MoveLookup = try {
        if (cancelled()) throw ScanCancelled()
        val calendars = readCalendars() ?: throw QueryFailed()
        if (!calendars.map { it.id }.containsAll(selected)) throw QueryFailed()
        val args = selected.map { it.toString() }.toMutableList()
        args += identity.event
        val providerIds = identity.providerEventIds.toList()
        val byRow = if (providerIds.isEmpty()) "" else {
            args += providerIds.map { it.toString() }; args += providerIds.map { it.toString() }
            " OR $CALENDAR_COL_ID IN (${marks(providerIds.size)}) OR $CALENDAR_COL_ORIGINAL_ID IN (${marks(providerIds.size)})"
        }
        val rows = readRows(CalendarQuery(CalendarQueryTarget.EVENTS, eventProjection,
            "$CALENDAR_COL_CALENDAR_ID IN (${marks(selected.size)}) AND ($CALENDAR_COL_UID = ?$byRow)", args.toTypedArray()),
            calendars.associateBy { it.id }, namespace, cancelled) ?: throw QueryFailed()
        fun seen(items: List<RawCalendarInstance>): List<SeenEvent> =
            (withAttendees(items, cancelled) ?: throw QueryFailed()).mapNotNull(::normalizeInstance)
                .filter { it.event == identity.event && occurrenceKey(it.occurrence) == identity.occurrence }
                .map { n -> SeenEvent(n.event, n.occurrence, n.calendarId, n.calendarName,
                    if (n.note.allDay == true) LocalDate.parse(n.note.start).atStartOfDay(consentZone).toInstant().toEpochMilli()
                        else n.startEpochMs, n.note, n.providerEventIds) }
        val target = identity.occurrence.takeIf { it.isNotEmpty() }?.let {
            if (it.length == 10) LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            else Instant.parse(it).toEpochMilli()
        }
        val exceptions = if (target == null) emptyList() else rows.filter { it.exception && it.originalInstanceEpochMs == target }
        val matched = if (exceptions.isNotEmpty()) {
            // A cancellation exception wins over a master that could otherwise recreate it.
            seen(exceptions)
        } else if (target == null) {
            seen(rows)
        } else {
            val masters = rows.filter { !it.exception && !it.cancelled }
            if (masters.isEmpty()) emptyList() else {
                val read = read(target - 86_400_000L, target + 86_400_000L, selected, namespace, cancelled)
                if (read !is CalendarScan.Ok) throw QueryFailed()
                seen(read.instances)
            }
        }
        if (cancelled()) throw ScanCancelled()
        MoveLookup(true, matched.isNotEmpty(), seenAll = matched)
    } catch (_: Exception) { MoveLookup(false, false) }

    fun readCalendars(): List<ProviderCalendar>? {
        val cursor = surface.query(
            CalendarQuery(
                target = CalendarQueryTarget.CALENDARS,
                projection = arrayOf(
                    CALENDAR_COL_ID,
                    CALENDAR_COL_DISPLAY_NAME,
                    CALENDAR_COL_OWNER,
                    CALENDAR_COL_ACCOUNT_TYPE,
                    CALENDAR_COL_VISIBLE,
                ),
                selection = null,
                selectionArgs = null,
            ),
        ) ?: return null
        cursor.use { rows ->
            val out = mutableListOf<ProviderCalendar>()
            while (rows.moveToNext()) {
                val id = rows.long(CALENDAR_COL_ID) ?: continue
                out += ProviderCalendar(
                    id = id,
                    name = rows.string(CALENDAR_COL_DISPLAY_NAME).orEmpty(),
                    ownerAccount = rows.string(CALENDAR_COL_OWNER),
                    accountType = rows.string(CALENDAR_COL_ACCOUNT_TYPE),
                    visible = (rows.int(CALENDAR_COL_VISIBLE) ?: 1) != 0,
                )
            }
            return out
        }
    }


    private fun readRows(query: CalendarQuery, calendars: Map<Long, ProviderCalendar>, namespace: String,
        cancelled: () -> Boolean): List<RawCalendarInstance>? {
        val cursor = surface.query(query) ?: return null
        val masters = mutableMapOf<Long, String?>()
        return cursor.use { rows ->
            val out = mutableListOf<RawCalendarInstance>()
            while (rows.moveToNext()) {
                if (cancelled()) throw ScanCancelled()
                val events = query.target == CalendarQueryTarget.EVENTS
                val id = rows.long(if (events) CALENDAR_COL_ID else CALENDAR_COL_EVENT_ID) ?: throw QueryFailed()
                val calendarId = rows.long(CALENDAR_COL_CALENDAR_ID) ?: throw QueryFailed()
                val calendar = calendars[calendarId] ?: throw QueryFailed()
                val originalId = rows.long(CALENDAR_COL_ORIGINAL_ID)?.takeIf { it > 0 }
                val exception = originalId != null
                val original = rows.long(CALENDAR_COL_ORIGINAL_INSTANCE)
                val rrule = rows.string(CALENDAR_COL_RRULE)
                val rdate = rows.string(CALENDAR_COL_RDATE)
                val masterUid = if (originalId == null) null else {
                    if (!masters.containsKey(originalId)) masters[originalId] = readMasterUid(originalId)
                    masters[originalId]
                }
                val begin = rows.long(if (events) "dtstart" else CALENDAR_COL_BEGIN) ?: throw QueryFailed()
                out += RawCalendarInstance(calendarId, calendar.name, calendar.ownerAccount, id,
                    rows.string(CALENDAR_COL_UID), masterUid, exception,
                    !rrule.isNullOrBlank() || !rdate.isNullOrBlank() || exception || original != null,
                    original, begin, rows.long(if (events) "dtend" else CALENDAR_COL_END),
                    (rows.int(CALENDAR_COL_ALL_DAY) ?: 0) != 0, rows.string(CALENDAR_COL_TIMEZONE),
                    rows.string(CALENDAR_COL_END_TIMEZONE), rows.string(CALENDAR_COL_TITLE),
                    rows.string(CALENDAR_COL_LOCATION), rows.string(CALENDAR_COL_DESCRIPTION), rrule,
                    rows.string(CALENDAR_COL_ORGANIZER), null,
                    (rows.int(CALENDAR_COL_STATUS) ?: 0) == CALENDAR_STATUS_CANCELED, emptyList(), namespace, originalId)
            }
            out
        }
    }

    private fun readMasterUid(eventId: Long): String? {
        val cursor = surface.query(CalendarQuery(CalendarQueryTarget.EVENTS, arrayOf(CALENDAR_COL_UID),
            "$CALENDAR_COL_ID = ?", arrayOf(eventId.toString()))) ?: throw QueryFailed()
        return cursor.use { rows -> if (rows.moveToNext()) rows.string(CALENDAR_COL_UID) else null }
    }

    private fun withAttendees(raw: List<RawCalendarInstance>, cancelled: () -> Boolean): List<RawCalendarInstance>? {
        val all = mutableMapOf<Long, MutableList<RawAttendee>>()
        for (ids in raw.map { it.eventId }.distinct().chunked(400)) {
            if (cancelled()) throw ScanCancelled()
            val cursor = surface.query(CalendarQuery(CalendarQueryTarget.ATTENDEES, arrayOf(CALENDAR_COL_EVENT_ID,
                CALENDAR_COL_ATTENDEE_EMAIL, CALENDAR_COL_ATTENDEE_NAME, CALENDAR_COL_ATTENDEE_STATUS),
                "$CALENDAR_COL_EVENT_ID IN (${marks(ids.size)})", ids.map { it.toString() }.toTypedArray())) ?: return null
            cursor.use { rows -> while (rows.moveToNext()) {
                if (cancelled()) throw ScanCancelled()
                val id = rows.long(CALENDAR_COL_EVENT_ID) ?: throw QueryFailed()
                all.getOrPut(id) { mutableListOf() } += RawAttendee(rows.string(CALENDAR_COL_ATTENDEE_EMAIL),
                    rows.string(CALENDAR_COL_ATTENDEE_NAME), rows.int(CALENDAR_COL_ATTENDEE_STATUS) ?: 0)
            } }
        }
        return raw.map { it.copy(attendees = all[it.eventId].orEmpty()) }
    }

    private fun marks(count: Int): String = List(count) { "?" }.joinToString(",")
    private val sharedProjection = arrayOf(CALENDAR_COL_CALENDAR_ID, CALENDAR_COL_TITLE, CALENDAR_COL_LOCATION,
        CALENDAR_COL_DESCRIPTION, CALENDAR_COL_ALL_DAY, CALENDAR_COL_RRULE, CALENDAR_COL_RDATE,
        CALENDAR_COL_TIMEZONE, CALENDAR_COL_END_TIMEZONE, CALENDAR_COL_STATUS, CALENDAR_COL_ORIGINAL_INSTANCE,
        CALENDAR_COL_ORIGINAL_ID, CALENDAR_COL_UID, CALENDAR_COL_ORGANIZER)
    private val instanceProjection = arrayOf(CALENDAR_COL_EVENT_ID, CALENDAR_COL_BEGIN, CALENDAR_COL_END) + sharedProjection
    private val eventProjection = arrayOf(CALENDAR_COL_ID, "dtstart", "dtend") + sharedProjection
}

private class ScanCancelled : RuntimeException()

private class QueryFailed : RuntimeException()

private fun CalendarCursor.string(name: String): String? {
    val index = column(name)
    if (index < 0 || isNull(index)) return null
    return string(index)?.takeIf { it.isNotEmpty() }
}

private fun CalendarCursor.long(name: String): Long? {
    val index = column(name)
    if (index < 0 || isNull(index)) return null
    return long(index)
}

private fun CalendarCursor.int(name: String): Int? {
    val index = column(name)
    if (index < 0 || isNull(index)) return null
    return int(index)
}
