// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.calendar

import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fixture-only. Nothing here reads the device's calendars.
 */
@RunWith(AndroidJUnit4::class)
class CalendarSourceAndroidTest {
    @Test
    fun instancesWindowIsPathSegmentsRatherThanInstancesQuery() {
        val uri = calendarQueryUri(
            CalendarQuery(
                target = CalendarQueryTarget.INSTANCES,
                projection = emptyArray(),
                selection = "$CALENDAR_COL_CALENDAR_ID IN (?)",
                selectionArgs = arrayOf("4"),
                begin = 10L,
                end = 20L,
            ),
        )
        assertEquals(
            CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath("10").appendPath("20").build(),
            uri,
        )
    }

    @Test
    fun theCheckHasNoNetworkConstraintAndIsNotScheduledWhenOff() {
        val request = newCalendarCheckRequest()
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, request.workSpec.intervalDuration)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        cancelCalendarWork(context)
        val states = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CALENDAR_WORK_NAME).get().map { it.state }
        assertTrue(states.none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING || it == WorkInfo.State.BLOCKED })
        assertFalse(shouldScheduleCalendarWork(confirmed = true, wishOn = false, permission = true))
        assertTrue(shouldScheduleCalendarWork(confirmed = true, wishOn = true, permission = true))
    }

    @Test
    fun grantDoesNotEnableAndDenyLeavesTheWishOffOnlyWithoutAConfirmation() {
        assertFalse(grantEnablesSource())
        assertTrue(denyLeavesSourceOff(confirmed = false))
        assertFalse(denyLeavesSourceOff(confirmed = true))
    }

    @Test
    fun aSyntheticSurfaceKeepsHiddenCalendarsClosesCursorsAndDoesNotTreatCancelAsEmpty() {
        val calendars = TrackingCursor(
            listOf(
                mapOf(
                    CALENDAR_COL_ID to 4L,
                    CALENDAR_COL_DISPLAY_NAME to "Hidden",
                    CALENDAR_COL_OWNER to "owner@example.com",
                    CALENDAR_COL_VISIBLE to 0,
                ),
            ),
        )
        val instances = TrackingCursor(
            listOf(mapOf(CALENDAR_COL_EVENT_ID to 9L, CALENDAR_COL_CALENDAR_ID to 4L, CALENDAR_COL_BEGIN to 1_000L)),
        )
        val queries = mutableListOf<CalendarQuery>()
        val reader = CalendarReader(object : CalendarQuerySurface {
            override fun query(query: CalendarQuery): CalendarCursor? {
                queries += query
                return when (query.target) {
                    CalendarQueryTarget.CALENDARS -> calendars
                    CalendarQueryTarget.INSTANCES -> instances
                    CalendarQueryTarget.ATTENDEES -> TrackingCursor(emptyList())
                    else -> null
                }
            }
        })
        val read = reader.read(10, 20, setOf(4L), "namespace")
        val ok = read as CalendarScan.Ok
        assertEquals(1, ok.calendars.size)
        assertFalse(ok.calendars.single().visible)
        assertTrue(calendars.closed)
        assertTrue(instances.closed)
        assertEquals(null, queries.first { it.target == CalendarQueryTarget.CALENDARS }.selection)
        assertTrue(queries.none { it.selection?.contains(CALENDAR_COL_VISIBLE) == true })

        val failed = CalendarReader(object : CalendarQuerySurface {
            override fun query(query: CalendarQuery): CalendarCursor? = null
        }).read(10, 20, setOf(4L), "namespace")
        assertTrue(failed is CalendarScan.Failed)

        var checks = 0
        val cancelledCursor = TrackingCursor(
            listOf(mapOf(CALENDAR_COL_EVENT_ID to 9L, CALENDAR_COL_CALENDAR_ID to 4L, CALENDAR_COL_BEGIN to 1_000L)),
        )
        val cancelled = CalendarReader(object : CalendarQuerySurface {
            override fun query(query: CalendarQuery): CalendarCursor? = when (query.target) {
                CalendarQueryTarget.CALENDARS -> TrackingCursor(
                    listOf(mapOf(CALENDAR_COL_ID to 4L, CALENDAR_COL_DISPLAY_NAME to "Hidden", CALENDAR_COL_VISIBLE to 0)),
                )
                CalendarQueryTarget.INSTANCES -> cancelledCursor
                else -> null
            }
        }).read(10, 20, setOf(4L), "namespace") { ++checks >= 3 }
        assertTrue(cancelled is CalendarScan.Cancelled)
        assertTrue(cancelledCursor.closed)
    }

    private class TrackingCursor(private val rows: List<Map<String, Any?>>) : CalendarCursor {
        private val columns = rows.flatMap { it.keys }.distinct()
        private var index = -1
        var closed = false
        override fun moveToNext(): Boolean {
            index += 1
            return index < rows.size
        }
        override fun column(name: String): Int = columns.indexOf(name)
        override fun string(index: Int): String? = value(index) as? String
        override fun long(index: Int): Long = (value(index) as Number).toLong()
        override fun int(index: Int): Int = (value(index) as Number).toInt()
        override fun isNull(index: Int): Boolean = index < 0 || value(index) == null
        override fun close() {
            closed = true
        }
        private fun value(index: Int): Any? = rows[this.index][columns[index]]
    }
}
