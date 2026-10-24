// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.calendar

import app.solstone.core.calendar.SeenEvent
import app.solstone.core.calendar.CalendarIdentityRecord
import app.solstone.core.calendar.CalendarIntentRecord
import app.solstone.core.calendar.HistoryKind
import app.solstone.platform.persistence.room.CalendarCalendarRow
import app.solstone.platform.persistence.room.CalendarCountRow
import app.solstone.platform.persistence.room.CalendarSettingsRow
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarRuntimeTest {
    @Test
    fun grantDoesNotEnableAndDenyTurnsOffOnlyWithoutAConfirmation() {
        assertFalse(grantEnablesSource())
        assertTrue(denyLeavesSourceOff(confirmed = false))
        assertFalse(denyLeavesSourceOff(confirmed = true))
        assertFalse(shouldScheduleCalendarWork(confirmed = true, wishOn = false, permission = true))
        assertFalse(shouldScheduleCalendarWork(confirmed = true, wishOn = true, permission = false))
        assertTrue(shouldScheduleCalendarWork(confirmed = true, wishOn = true, permission = true))

        val harness = Harness()
        harness.wish = true
        harness.runtime.onPermissionResult(true)
        assertTrue(harness.enabledCalls.none { it })
        assertEquals(true, harness.wish)
        assertFalse(harness.ledger.settings().enabled)
        assertTrue(harness.sealed == 0)
        assertTrue(harness.ledger.intents().isEmpty())

        val denied = Harness()
        denied.wish = true
        denied.runtime.onPermissionResult(false)
        assertEquals(listOf(false), denied.enabledCalls)
        assertFalse(denied.wish)
        assertTrue(denied.scheduled.last() == false)

        val kept = Harness()
        kept.wish = true
        kept.ledger.saveSettings(
            kept.ledger.settings().copy(confirmedGeneration = 1, confirmedHistoryKind = HistoryKind.DAYS_30.name, enabled = true),
        )
        val consent = kept.ledger.settings().consentToken
        kept.runtime.onPermissionResult(false)
        assertTrue(kept.enabledCalls.isEmpty())
        assertTrue(kept.wish)
        assertTrue(kept.ledger.settings().enabled)
        assertEquals(consent + 1, kept.ledger.settings().consentToken)
    }

    @Test
    fun confirmImportsAndResumeDoesNotMintACount() {
        val harness = Harness()
        assertFalse(harness.runtime.confirm())
        assertTrue(harness.enabledCalls.isEmpty())

        harness.runtime.count()
        val counted = harness.ledger.countFor(harness.ledger.settings().countGeneration)
        assertEquals("ready", counted?.status)
        assertTrue(harness.ledger.intents().isEmpty())
        assertEquals(0, harness.sealed)

        assertTrue(harness.runtime.confirm())
        assertEquals(listOf(true), harness.enabledCalls)
        assertEquals(true, harness.scheduled.last())
        assertTrue(harness.ledger.settings().enabled)
        assertEquals(harness.ledger.settings().countGeneration, harness.ledger.settings().confirmedGeneration)

        val generation = harness.ledger.settings().countGeneration
        val counts = harness.ledger.countGenerations()
        harness.runtime.turnOff()
        assertFalse(harness.wish)
        assertFalse(harness.ledger.settings().enabled)
        assertEquals(generation, harness.ledger.settings().countGeneration)
        assertTrue(harness.runtime.primary())
        assertEquals(generation, harness.ledger.settings().countGeneration)
        assertEquals(counts, harness.ledger.countGenerations())
        assertTrue(harness.wish)
    }

    @Test
    fun spanAndSelectionChangesInvalidateTheCountAndUncheckDoesNotRemove() {
        val harness = Harness()
        harness.ledger.saveCalendars(listOf(row(7, selected = true, confirmed = true)))
        val generation = harness.ledger.settings().countGeneration
        val consent = harness.ledger.settings().consentToken
        harness.runtime.setHistory(HistoryKind.MONTHS_6)
        assertEquals(generation + 1, harness.ledger.settings().countGeneration)
        assertEquals(consent + 1, harness.ledger.settings().consentToken)

        harness.runtime.setSelected(7, false)
        assertFalse(harness.ledger.calendars().single().selected)
        assertFalse(harness.ledger.calendars().single().confirmed)
        assertTrue(harness.ledger.intents().isEmpty())
        assertEquals(0, harness.sealed)
    }

    @Test
    fun firstSyncStartsWithEveryCalendarOffAndBringsInOnlyWhatTheOwnerSwitchesOn() {
        val harness = Harness(listOf(1L to "Family", 2L to "Birthdays", 3L to "Holidays"))
        harness.ledger.saveCalendars(emptyList())
        harness.runtime.refreshPreview()
        harness.runtime.count()
        val first = harness.runtime.ui
        assertEquals(listOf(1L, 2L, 3L), first.rows.map { it.id })
        assertTrue(first.rows.none { it.selected })
        assertNull(first.countStatus)
        assertNull(first.countValue)
        assertFalse(first.confirmEnabled)
        assertFalse(harness.runtime.primary())
        assertTrue(harness.enabledCalls.isEmpty())
        assertTrue(harness.ledger.intents().isEmpty())

        harness.runtime.setSelected(2, true)
        harness.runtime.count()
        assertEquals("ready", harness.runtime.ui.countStatus)
        assertTrue(harness.runtime.ui.confirmEnabled)
        assertTrue(harness.runtime.primary())
        assertEquals(listOf(2L), harness.ledger.calendars().filter { it.confirmed }.map { it.providerId })
    }

    @Test
    fun anEarlierChoiceIsKeptAndACalendarAddedLaterStartsOff() {
        val harness = Harness(listOf(7L to "Work", 8L to "Added later"))
        harness.ledger.saveCalendars(listOf(row(7, selected = true, confirmed = true)))
        harness.ledger.saveSettings(harness.ledger.settings().copy(enabled = true, confirmedGeneration = 1,
            confirmedHistoryKind = HistoryKind.DAYS_30.name))
        harness.runtime.refreshPreview()
        val rows = harness.ledger.calendars().associateBy { it.providerId }
        assertTrue(rows.getValue(7).selected)
        assertTrue(rows.getValue(7).confirmed)
        assertFalse(rows.getValue(8).selected)
        assertTrue(harness.runtime.ui.resume)
    }

    @Test
    fun abandonTurnsOffOnlyAFirstSetupAndKeepsAConfirmedSource() {
        val first = Harness()
        first.wish = true
        first.runtime.abandon()
        assertFalse(first.wish)
        assertFalse(first.ledger.settings().enabled)
        assertNull(first.ledger.settings().confirmedGeneration)

        val widen = Harness()
        widen.ledger.saveCalendars(listOf(row(3, selected = true, confirmed = false)))
        widen.ledger.saveSettings(
            widen.ledger.settings().copy(
                enabled = false,
                confirmedGeneration = 2,
                confirmedHistoryKind = HistoryKind.DAYS_30.name,
                historyKind = HistoryKind.ALL.name,
            ),
        )
        val consent = widen.ledger.settings().consentToken
        val generation = widen.ledger.settings().countGeneration
        widen.runtime.abandon()
        assertFalse(widen.ledger.settings().enabled)
        assertEquals(HistoryKind.DAYS_30.name, widen.ledger.settings().historyKind)
        assertFalse(widen.ledger.calendars().single().selected)
        assertEquals(consent + 1, widen.ledger.settings().consentToken)
        assertEquals(generation + 1, widen.ledger.settings().countGeneration)
        assertTrue(widen.enabledCalls.isEmpty())
    }

    @Test
    fun hiddenCalendarsAreReadAndACancelledOrFailedReadIsNotEmpty() {
        val calendars = MapCursor(
            listOf(
                mapOf(
                    CALENDAR_COL_ID to 4L,
                    CALENDAR_COL_DISPLAY_NAME to "Hidden",
                    CALENDAR_COL_OWNER to "owner@example.com",
                    CALENDAR_COL_ACCOUNT_TYPE to "local",
                    CALENDAR_COL_VISIBLE to 0,
                    CALENDAR_COL_ACCOUNT_NAME to "owner@example.com",
                ),
            ),
        )
        val queries = mutableListOf<CalendarQuery>()
        val reader = CalendarReader(object : CalendarQuerySurface {
            override fun query(query: CalendarQuery): CalendarCursor? {
                queries += query
                return when (query.target) {
                    CalendarQueryTarget.CALENDARS -> calendars
                    CalendarQueryTarget.INSTANCES -> MapCursor(emptyList())
                    else -> null
                }
            }
        })

        val read = reader.read(10, 20, setOf(4), "ns")
        val ok = assertIs<CalendarScan.Ok>(read)
        assertEquals(listOf(4L), ok.calendars.map { it.id })
        assertFalse(ok.calendars.single().visible)
        assertEquals("owner@example.com", ok.calendars.single().accountName)
        assertTrue(CALENDAR_COL_ACCOUNT_NAME in queries.first { it.target == CalendarQueryTarget.CALENDARS }.projection)
        assertTrue(ok.instances.isEmpty())
        assertTrue(calendars.closed)
        assertNull(queries.first { it.target == CalendarQueryTarget.CALENDARS }.selection)
        val instances = queries.first { it.target == CalendarQueryTarget.INSTANCES }
        assertEquals(listOf("4"), instances.selectionArgs?.toList())
        assertTrue(instances.selection!!.contains(CALENDAR_COL_CALENDAR_ID))
        assertTrue(queries.none { it.selection?.contains(CALENDAR_COL_VISIBLE) == true })

        val failed = CalendarReader(object : CalendarQuerySurface {
            override fun query(query: CalendarQuery): CalendarCursor? = null
        }).read(10, 20, setOf(4), "ns")
        assertIs<CalendarScan.Failed>(failed)

        var checks = 0
        val cancelledCursor = MapCursor(
            listOf(mapOf(CALENDAR_COL_EVENT_ID to 9L, CALENDAR_COL_CALENDAR_ID to 4L, CALENDAR_COL_BEGIN to 1_000L)),
        )
        val cancelled = CalendarReader(object : CalendarQuerySurface {
            override fun query(query: CalendarQuery): CalendarCursor? = when (query.target) {
                CalendarQueryTarget.CALENDARS -> MapCursor(
                    listOf(mapOf(CALENDAR_COL_ID to 4L, CALENDAR_COL_DISPLAY_NAME to "Hidden", CALENDAR_COL_VISIBLE to 0)),
                )
                CalendarQueryTarget.INSTANCES -> cancelledCursor
                else -> null
            }
        }).read(10, 20, setOf(4), "ns") { ++checks >= 3 }
        assertIs<CalendarScan.Cancelled>(cancelled)
        assertTrue(cancelledCursor.closed)
    }

    @Test
    fun cancelledOccurrenceDoesNotHideAnotherCalendarHolder() {
        val target = Instant.parse("2025-10-23T10:00:00Z").toEpochMilli()
        for (cancelA in listOf(false, true)) {
            for (includeB in listOf(false, true)) {
                val selected = if (includeB) setOf(1L, 2L) else setOf(1L)
                fun master(id: Long, calendar: Long) = mapOf(
                    CALENDAR_COL_ID to id, CALENDAR_COL_CALENDAR_ID to calendar,
                    CALENDAR_COL_UID to "series", "dtstart" to target,
                    "dtend" to target + 3_600_000L, CALENDAR_COL_RRULE to "FREQ=DAILY",
                )
                val rows = listOf(master(10, 1), master(20, 2)) + if (cancelA) listOf(
                    mapOf(CALENDAR_COL_ID to 11L, CALENDAR_COL_CALENDAR_ID to 1L,
                        CALENDAR_COL_UID to "series", CALENDAR_COL_ORIGINAL_ID to 10L,
                        CALENDAR_COL_ORIGINAL_INSTANCE to target, "dtstart" to target,
                        CALENDAR_COL_STATUS to CALENDAR_STATUS_CANCELED),
                ) else emptyList()
                val reader = CalendarReader(object : CalendarQuerySurface {
                    override fun query(query: CalendarQuery): CalendarCursor = when (query.target) {
                        CalendarQueryTarget.CALENDARS -> MapCursor(listOf(
                            mapOf(CALENDAR_COL_ID to 1L, CALENDAR_COL_DISPLAY_NAME to "A"),
                            mapOf(CALENDAR_COL_ID to 2L, CALENDAR_COL_DISPLAY_NAME to "B"),
                        ))
                        CalendarQueryTarget.EVENTS -> if (query.projection.contentEquals(arrayOf(CALENDAR_COL_UID))) {
                            MapCursor(listOf(mapOf(CALENDAR_COL_UID to "series")))
                        } else MapCursor(rows.filter { it[CALENDAR_COL_CALENDAR_ID] in selected })
                        CalendarQueryTarget.INSTANCES -> MapCursor(if (includeB) listOf(
                            mapOf(CALENDAR_COL_EVENT_ID to 20L, CALENDAR_COL_CALENDAR_ID to 2L,
                                CALENDAR_COL_UID to "series", CALENDAR_COL_BEGIN to target,
                                CALENDAR_COL_END to target + 3_600_000L, CALENDAR_COL_RRULE to "FREQ=DAILY"),
                        ) else emptyList())
                        CalendarQueryTarget.ATTENDEES -> MapCursor(emptyList())
                    }
                })
                val identity = CalendarIdentityRecord("series", "2025-10-23T10:00:00Z",
                    holders = selected, winningHolder = 1L, providerEventIds = setOf(10, 20))
                val lookup = reader.lookup(identity, selected, "namespace", ZoneOffset.UTC)
                assertTrue(lookup.readable)
                assertEquals(includeB, lookup.stillPresent,
                    "cancellation in A must preserve B, and canceled-only must remain absent")
                assertEquals(if (includeB) setOf(2L) else emptySet(), lookup.seenAll.map { it.calendarId }.toSet())
            }
        }
    }

    private class Harness(listed: List<Pair<Long, String>> = listOf(1L to "Synthetic")) {
        var wish = false
        val enabledCalls = mutableListOf<Boolean>()
        val scheduled = mutableListOf<Boolean>()
        var sealed = 0
        val ledger = MemoryLedger()
        val runtime = CalendarRuntime(
            ledger = ledger,
            reader = CalendarReader(object : CalendarQuerySurface {
                override fun query(query: CalendarQuery): CalendarCursor? = when (query.target) {
                    CalendarQueryTarget.CALENDARS -> MapCursor(listed.map { (id, name) -> mapOf(CALENDAR_COL_ID to id, CALENDAR_COL_DISPLAY_NAME to name) })
                    else -> MapCursor(emptyList())
                }
            }),
            sealer = {
                sealed += 1
                true
            },
            segmentExists = { false },
            permission = { true },
            wishOn = { wish },
            setEnabled = { on ->
                enabledCalls += on
                wish = on
                true
            },
            schedule = { scheduled += it },
            clock = { Instant.parse("2026-10-22T16:00:00Z").toEpochMilli() },
            zone = { ZoneOffset.UTC },
        )
    }

    private class MemoryLedger : CalendarLedger {
        private var settings = defaultCalendarSettings().copy(fallbackNamespace = "ns")
        private var calendars = listOf(CalendarCalendarRow(1, "Synthetic", null, null, true, false, true))
        private var identities = emptyList<CalendarIdentityRecord>()
        private var intents = emptyList<CalendarIntentRecord>()
        private val counts = mutableMapOf<Long, CalendarCountRow>()

        override fun settings(): CalendarSettingsRow = settings
        override fun saveSettings(row: CalendarSettingsRow) {
            settings = row.copy(id = 0)
        }
        override fun calendars(): List<CalendarCalendarRow> = calendars
        override fun saveCalendars(rows: List<CalendarCalendarRow>) {
            calendars = rows
        }
        override fun identities(): List<CalendarIdentityRecord> = identities
        override fun saveIdentities(rows: List<CalendarIdentityRecord>) {
            identities = rows
        }
        override fun intents(): List<CalendarIntentRecord> = intents
        override fun saveIntents(rows: List<CalendarIntentRecord>) {
            intents = rows
        }
        override fun countFor(generation: Long): CalendarCountRow? = counts[generation]
        override fun saveCount(row: CalendarCountRow) {
            counts[row.generation] = row
        }
        override fun saveConfiguration(settings: CalendarSettingsRow, calendars: List<CalendarCalendarRow>) {
            saveSettings(settings); saveCalendars(calendars)
        }
        override fun stage(identities: List<CalendarIdentityRecord>, intents: List<CalendarIntentRecord>, settings: CalendarSettingsRow?, count: CalendarCountRow?, canCommit: () -> Boolean): Boolean {
            if (!canCommit()) return false
            saveIdentities(identities); saveIntents(intents); settings?.let(::saveSettings); count?.let(::saveCount)
            return true
        }
        private val previews = mutableMapOf<Long, List<SeenEvent>>()
        override fun clearPreview(generation: Long) { previews.remove(generation) }
        override fun appendPreview(generation: Long, rows: List<SeenEvent>) {
            previews[generation] = (previews[generation].orEmpty() + rows).distinctBy { Triple(it.event, it.occurrence, it.calendarId) }
        }
        override fun previewCount(generation: Long): Int = previews[generation].orEmpty().map { it.event to it.occurrence }.distinct().size
        override fun previewCounts(generation: Long): Map<Long, Int> = previews[generation].orEmpty().groupingBy { it.calendarId }.eachCount()
        override fun previewPage(generation: Long, offset: Int): List<SeenEvent> =
            previews[generation].orEmpty().groupBy { it.event to it.occurrence }.values.drop(offset).take(64).flatten()
        override fun pendingImports(): List<CalendarCountRow> = counts.values.filter { it.importCursor != null }.sortedBy { it.generation }
        override fun confirmImport(count: CalendarCountRow, settings: CalendarSettingsRow, calendars: List<CalendarCalendarRow>) {
            saveCount(count); saveConfiguration(settings, calendars)
        }
        override fun saveImportProgress(count: CalendarCountRow, settings: CalendarSettingsRow) { saveCount(count); saveSettings(settings) }
        override fun prune() = Unit
        fun countGenerations(): Set<Long> = counts.keys.toSet()
    }

    private class MapCursor(private val rows: List<Map<String, Any?>>) : CalendarCursor {
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

    private fun row(id: Long, selected: Boolean, confirmed: Boolean) = CalendarCalendarRow(
        providerId = id,
        displayName = "Work",
        ownerAccount = null,
        accountType = null,
        selected = selected,
        confirmed = confirmed,
        visible = true,
    )
}
