// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.calendar

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.calendar.*
import app.solstone.core.pl.toJson
import app.solstone.core.sources.CALENDAR_STREAM
import app.solstone.core.spool.GapNoticeStore
import app.solstone.core.spool.GapRead
import app.solstone.platform.persistence.room.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses only private synthetic storage and a synthetic provider surface, never device calendars. */
@RunWith(AndroidJUnit4::class)
class CalendarDurabilityAndroidTest {
    @Test fun multiMegabyteIntentAndAtomicGuardSurviveRoomReopenAndSealOnce() = Rig().use { rig ->
        val seen = (1..7).map { n ->
            val note = note("large-$n", "n".repeat(600_000))
            SeenEvent(note.event, null, 41, "Synthetic", rig.now, note, setOf(n.toLong()))
        }
        val reconciled = reconcileScan(emptyList(), seen, setOf(41), ScanOutcome.COMPLETE,
            rig.now, rig.zone, 1, 1, Long.MIN_VALUE)
        val staged = stageNotes(reconciled.identities, emptyList(), reconciled.notes)
        val bytes = staged.intents.single().bytes.copyOf()
        assertTrue(bytes.size > 3 * 1024 * 1024)
        assertFalse(rig.ledger().stage(staged.identities, staged.intents, canCommit = { false }))
        assertTrue(rig.ledger().identities().isEmpty())
        assertTrue(rig.ledger().intents().isEmpty())
        assertTrue(rig.ledger().stage(staged.identities, staged.intents))
        assertEquals(64, rig.db.calendarDao().intents().single().body.length)
        assertTrue(rig.db.calendarDao().identities().all { it.pendingCanonical?.length == 64 })
        rig.reopen()
        assertArrayEquals(bytes, rig.ledger().intents().single().bytes)
        rig.runtime().onProcessStart()
        assertTrue(rig.ledger().intents().isEmpty())
        assertTrue(rig.ledger().identities().all { it.pendingFingerprint == null && it.publishedFingerprint != null })
        val day = staged.intents.single().day
        assertEquals(1, rig.db.segmentDao().segmentsByDay(day).count { it.stream == CALENDAR_STREAM })
        assertNull(rig.ledger().settings().lastSuccessAt)
        rig.reopen()
        rig.runtime().onProcessStart()
        assertEquals(1, rig.db.segmentDao().segmentsByDay(day).count { it.stream == CALENDAR_STREAM })
        val payload = rig.payloadFiles().single()
        assertArrayEquals(bytes, Files.readAllBytes(payload))
    }

    @Test fun countedAllDaySnapshotSurvivesMidnightProviderEditAndReopenBeforeConfirmation() = Rig().use { rig ->
        rig.rows = listOf(mapOf(CALENDAR_COL_EVENT_ID to 9L, CALENDAR_COL_CALENDAR_ID to 41L,
            CALENDAR_COL_UID to "frozen-day", CALENDAR_COL_TITLE to "counted title", CALENDAR_COL_ALL_DAY to 1,
            CALENDAR_COL_BEGIN to Instant.parse("2026-10-22T00:00:00Z").toEpochMilli(),
            CALENDAR_COL_END to Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()))
        val runtime = rig.runtime()
        runtime.refreshPreview()
        runtime.setSelected(41, true)
        runtime.setHistory(HistoryKind.NONE_BEFORE_TODAY)
        runtime.count()
        val settings = rig.ledger().settings()
        val counted = rig.ledger().countFor(settings.countGeneration)!!
        assertEquals(1, counted.count)
        assertEquals("ready", counted.status)
        assertTrue(rig.payloadFiles().isEmpty())
        val observed = rig.now
        rig.now += 86_400_000L
        rig.rows = rig.rows.map { it + (CALENDAR_COL_TITLE to "changed after counting") }
        rig.reopen()
        assertTrue(rig.runtime().confirm())
        val delivered = decodeCalendarLine(Files.readAllLines(rig.payloadFiles().single()).single())
        assertEquals("counted title", delivered.title)
        assertEquals("2026-10-22", delivered.start)
        assertEquals("2026-10-23", delivered.end)
        assertEquals(observed, changedAtEpoch(delivered.changedAt))
        assertEquals(counted.span, rig.ledger().countFor(settings.countGeneration)!!.span)
        assertEquals(observed, rig.ledger().settings().lastSuccessAt)
    }

    @Test fun aNewConfirmationKeepsEveryEarlierApprovedRecordThroughPauseAndReopen() = Rig().use { rig ->
        fun row(id: String) = mapOf<String, Any?>(CALENDAR_COL_EVENT_ID to id.hashCode().toLong(),
            CALENDAR_COL_CALENDAR_ID to 41L, CALENDAR_COL_UID to id, CALENDAR_COL_TITLE to id,
            CALENDAR_COL_BEGIN to Instant.parse("2026-10-23T16:00:00Z").toEpochMilli(),
            CALENDAR_COL_END to Instant.parse("2026-10-23T17:00:00Z").toEpochMilli())
        rig.rows = (0..128).map { row("approved-$it") }
        val first = rig.runtime()
        first.refreshPreview(); first.setSelected(41, true); first.count()
        rig.refuseSeals = true
        assertFalse(first.confirm())
        assertEquals(64, rig.ledger().settings().importCompleted)
        assertEquals(1, rig.ledger().pendingImports().size)
        first.turnOff()
        first.setHistory(HistoryKind.MONTHS_6)
        rig.rows = listOf(row("new-selection"))
        rig.now += 1000
        first.count()
        rig.reopen()
        rig.refuseSeals = false
        assertTrue(rig.runtime().confirm())
        val notes = rig.payloadFiles().flatMap { Files.readAllLines(it) }.map(::decodeCalendarLine)
        assertEquals((0..128).map { "approved-$it" }.toSet() + "new-selection", notes.map { it.event }.toSet())
        assertEquals(130, notes.size)
        assertTrue(rig.ledger().pendingImports().isEmpty())
        assertEquals(0, rig.db.calendarDao().pendingImportCount())
        assertTrue(rig.ledger().settings().importDone)
    }

    @Test fun blockingCalendarCheckKeepsTheExecutionLeaseUntilTheReadCompletes() = Rig().use { rig ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ledger = rig.ledger()
        val s = ledger.settings()
        ledger.saveConfiguration(s.copy(enabled = true, confirmedGeneration = 1,
            confirmedHistoryKind = HistoryKind.DAYS_30.name), listOf(CalendarCalendarRow(41, "Synthetic", null, null, true, true, false)))
        ledger.saveCount(CalendarCountRow(1, calendarSelectionFingerprint(setOf(41), HistoryKind.DAYS_30.name),
            toJson(linkedMapOf("kind" to HistoryKind.DAYS_30.name, "lower" to rig.now - 30L * 86_400_000,
                "upper" to rig.now + 365L * 86_400_000, "observed_at" to rig.now, "zone" to rig.zone.id)), 0, "ready", ""))
        rig.wish = true
        rig.onInstances = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val session = CalendarSession(rig.context, rig.db, rig.base.resolve("spool"), { true }, { rig.wish },
            { rig.wish = it; true }, { false }, {}, {}, rig.reader())
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { session.runCheckBlocking() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            assertNull(ledger.settings().lastSuccessAt)
            release.countDown()
            assertTrue(result.get(10, TimeUnit.SECONDS))
            assertNotNull(ledger.settings().lastSuccessAt)
        } finally { release.countDown(); worker.shutdownNow(); session.close() }
    }

    @Test fun aPartialDraftFromADeadSealIsReplacedByTheNextSeal() = Rig().use { rig ->
        val changedAt = formatChangedAt(Instant.ofEpochMilli(rig.now), rig.zone)
        val line = """{"changed_at":"$changedAt"}"""
        val file = CalendarFileBytes(
            name = "calendar_dead.jsonl",
            bytes = line.toByteArray(),
            mediaType = "application/x-ndjson",
            day = "2026-10-22",
            segment = "seg-dead",
            sourceId = CALENDAR_STREAM,
        )
        val spool = rig.base.resolve("spool")
        val draft = spool.resolve(".draft").resolve(file.day).resolve(CALENDAR_STREAM).resolve(file.segment)
        Files.createDirectories(draft)
        Files.write(draft.resolve(file.name), "partial".toByteArray())
        val gaps = GapNoticeStore(rig.base.resolve("gap"))
        assertTrue(sealCalendarFile(rig.db, spool, file, gaps))
        assertFalse(Files.exists(draft))
        val read = gaps.read(rig.zone)
        assertTrue(read is GapRead.Ready)
        assertTrue((read as GapRead.Ready).rows.none { it.kind == "loss" })
        val sealed = spool.resolve(file.day).resolve(CALENDAR_STREAM).resolve(file.segment).resolve(file.name)
        assertEquals(line, String(Files.readAllBytes(sealed), Charsets.UTF_8))
    }

    private class Rig : AutoCloseable {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "calendar-durability-${UUID.randomUUID()}.db"
        val base = context.cacheDir.toPath().resolve("calendar-durability-${UUID.randomUUID()}")
        val zone = ZoneId.of("America/Denver")
        var now = Instant.parse("2026-10-22T16:00:00Z").toEpochMilli()
        var wish = false
        var refuseSeals = false
        var rows = emptyList<Map<String, Any?>>()
        var onInstances: () -> Unit = {}
        var db = openSolstonePersistenceDatabase(context, name)
        fun ledger() = RoomCalendarLedger(db.calendarDao(), CalendarPayloads(base.resolve("pending")))
        fun reopen() { db.close(); db = openSolstonePersistenceDatabase(context, name) }
        fun reader() = CalendarReader(object : CalendarQuerySurface {
            override fun query(query: CalendarQuery): CalendarCursor = when (query.target) {
                CalendarQueryTarget.CALENDARS -> Cursor(listOf(mapOf(CALENDAR_COL_ID to 41L,
                    CALENDAR_COL_DISPLAY_NAME to "Synthetic", CALENDAR_COL_VISIBLE to 0)))
                CalendarQueryTarget.INSTANCES -> { onInstances(); Cursor(rows) }
                else -> Cursor(emptyList())
            }
        })
        fun runtime() = CalendarRuntime(ledger(), reader(), { !refuseSeals && sealCalendarFile(db, base.resolve("spool"), it) },
            { calendarSegmentExists(db, it) }, { true }, { wish }, { wish = it; true }, {}, clock = { now }, zone = { zone })
        fun payloadFiles(): List<java.nio.file.Path> {
            val spool = base.resolve("spool")
            if (!Files.exists(spool)) return emptyList()
            return Files.walk(spool).use { paths -> paths.filter { it.fileName.toString().endsWith(".jsonl") }.toArray().map { it as java.nio.file.Path } }
        }
        override fun close() {
            db.close(); context.deleteDatabase(name)
            if (Files.exists(base)) Files.walk(base).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private class Cursor(private val rows: List<Map<String, Any?>>) : CalendarCursor {
        val columns = rows.flatMap { it.keys }.distinct(); var at = -1
        override fun moveToNext() = (++at < rows.size)
        override fun column(name: String) = columns.indexOf(name)
        override fun string(index: Int) = rows[at][columns[index]] as? String
        override fun long(index: Int) = (rows[at][columns[index]] as Number).toLong()
        override fun int(index: Int) = (rows[at][columns[index]] as Number).toInt()
        override fun isNull(index: Int) = rows[at][columns[index]] == null
        override fun close() = Unit
    }

    private fun note(id: String, notes: String) = CalendarNote(allDay = false, attendees = emptyList(),
        calendar = "Synthetic", change = CalendarChange.ADDED, changedAt = "2026-10-22T10:00:00-06:00",
        end = null, event = id, location = null, notes = notes, occurrence = null, organizer = null,
        repeats = null, start = "2026-10-23T10:00:00-06:00", timeZone = "America/Denver", title = id)
}
