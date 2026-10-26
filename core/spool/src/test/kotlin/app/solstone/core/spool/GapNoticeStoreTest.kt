// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GapNoticeStoreTest {
    private val zone = ZoneId.of("UTC")
    private var now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
    private var nextId = 0

    @Test
    fun parentSyncFailureReturnsFalseAndTheNextCallResyncsTheExistingRow() {
        val root = temp()
        var fail = true
        val store = store(root, sync = { if (fail) throw IOException("sync failed") })
        assertFalse(store.recordLoss("audio", "undeliverable", "rec-1", 5L))
        assertTrue(Files.isRegularFile(root.resolve("events.v1")))
        fail = false
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", 5L))
        val rows = ready(store)
        assertEquals(1, rows.size)
        assertEquals("rec-1", rows[0].material)
        assertEquals(0, Files.list(root).use { stream -> stream.filter { it.fileName.toString().endsWith(".tmp") }.count() })
    }

    @Test
    fun readThrowLeavesTheFileAndShowsNothing() {
        val root = temp()
        val store = store(root)
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", 5L))
        Files.delete(root.resolve("events.v1"))
        Files.createDirectory(root.resolve("events.v1"))
        assertIs<GapRead.Unavailable>(store.read(zone))
        assertTrue(Files.isDirectory(root.resolve("events.v1")))
        assertNullSnapshot(store)
    }

    @Test
    fun undecodableBytesAreSetAsideAndANewLossStillRecords() {
        val root = temp()
        val heard = mutableListOf<String>()
        val store = store(root, listener = { kind, source, reason, material ->
            heard += "$kind $source $reason $material"
        })
        Files.write(root.resolve("events.v1"), byteArrayOf(0xC3.toByte(), 0x28))
        val kept = root.resolve("segment.bin")
        Files.write(kept, byteArrayOf(1))
        val read = store.read(zone)
        val ready = assertIs<GapRead.Ready>(read)
        assertEquals("unreadable record", ready.rows.single().reason)
        assertEquals("events", ready.rows.single().material)
        assertTrue(Files.list(root).use { it.anyMatch { path -> path.fileName.toString().startsWith("events.v1.bad-") } })
        assertTrue(heard.any { it == "diagnostic spool unreadable record events" })
        assertTrue(Files.isRegularFile(kept))
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-new", 5L))
        val rows = ready(store)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.material == "rec-new" && it.kind == "loss" })
    }

    @Test
    fun aLineThatDoesNotParseIsUndecodable() {
        val root = temp()
        val store = store(root)
        Files.writeString(root.resolve("events.v1"), "not a row\n")
        val ready = assertIs<GapRead.Ready>(store.read(zone))
        assertEquals("unreadable record", ready.rows.single().reason)
    }

    @Test
    fun lossCollapseKeepsADiagnosticAndDiagnosticsCollapseAcrossMaterial() {
        val root = temp()
        val store = store(root)
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", 5L))
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", 9L))
        assertTrue(store.recordDiagnostic("audio", "remux failed", "rec-1", 5L))
        now += 1_000
        assertTrue(store.recordDiagnostic("audio", "remux failed", "rec-2", 6L))
        now += 1_000
        assertTrue(store.recordDiagnostic("screen", "no bytes", "rec-3", null))
        now += 1_000
        assertTrue(store.recordDiagnostic("audio", "remux failed", "rec-4", 7L))
        val rows = ready(store)
        assertEquals(1, rows.count { it.kind == "loss" })
        val remux = rows.single { it.reason == "remux failed" }
        assertEquals(3, remux.count)
        assertEquals("rec-4", remux.material)
        assertEquals(rows.first { it.reason == "remux failed" }.id, remux.id)
        assertTrue(remux.lastEpochMs > remux.firstEpochMs)
        assertEquals(1, rows.single { it.reason == "no bytes" }.count)
    }

    @Test
    fun legacyRowsDoNotCollapseAndConversionWritesOneRowPerMarker() {
        val root = temp()
        val store = store(root)
        val audio = root.resolve("audio-interruption")
        val screen = root.resolve("screen-interruption")
        Files.writeString(audio, "unresolved")
        Files.writeString(screen, "unresolved")
        val first = store.conversion(listOf("audio" to audio, "screen" to screen))
        assertEquals("complete 2", first)
        assertFalse(Files.exists(audio))
        assertFalse(Files.exists(screen))
        val legacy = ready(store).filter { it.kind == "legacy" }
        assertEquals(2, legacy.size)
        assertTrue(legacy.all { it.reason == "legacy marker" && it.material == "marker" && it.capturedEpochMs == null })
        val again = root.resolve("audio-interruption")
        Files.writeString(again, "unresolved")
        assertEquals("complete 2", store.conversion(listOf("audio" to again, "screen" to screen)))
        assertTrue(Files.exists(again))
        assertEquals(2, ready(store).count { it.kind == "legacy" })
        val seededDir = temp()
        Files.write(
            seededDir.resolve("events.v1"),
            encodeGapRows(listOf(legacyRow("aa", "audio"), legacyRow("bb", "audio"))),
        )
        val other = store(seededDir)
        assertTrue(other.recordDiagnostic("spool", "delivered", "leaf", null))
        assertEquals(2, ready(other).count { it.kind == "legacy" && it.source == "audio" })
    }

    @Test
    fun noMarkersWritesCompleteZeroAndFailedIsRetried() {
        val root = temp()
        var fail = true
        val store = store(root, beforeWrite = { path ->
            if (fail && path.fileName.toString() == "events.v1") throw IOException("events")
        })
        val marker = root.resolve("audio-interruption")
        Files.writeString(marker, "unresolved")
        assertTrue(store.conversion(listOf("audio" to marker)).startsWith("failed "))
        assertTrue(Files.exists(marker))
        fail = false
        assertEquals("complete 1", store.conversion(listOf("audio" to marker)))
        assertFalse(Files.exists(marker))
        Files.writeString(marker, "unresolved")
        assertEquals("complete 1", store.conversion(listOf("audio" to marker)))
        assertTrue(Files.exists(marker))
        assertEquals(1, ready(store).count { it.kind == "legacy" })
        val empty = store(temp())
        assertEquals("complete 0", empty.conversion(emptyList()))
        assertEquals("complete 0", empty.conversion(emptyList()))
        val leftoverDir = temp()
        val leftover = leftoverDir.resolve("screen-interruption")
        Files.writeString(leftover, "unresolved")
        Files.write(leftoverDir.resolve("events.v1"), encodeGapRows(listOf(legacyRow("cc", "screen"))))
        val retry = store(leftoverDir)
        assertEquals("complete 0", retry.conversion(listOf("screen" to leftover)))
        assertFalse(Files.exists(leftover))
        assertEquals(1, ready(retry).count { it.kind == "legacy" })
    }

    @Test
    fun pruneKeepsThirtyDaysAndCapsAndAnUnseenInWindowLoss() {
        val root = temp()
        val store = store(root)
        now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertTrue(store.recordDiagnostic("spool", "delivered", "old", null))
        now = Instant.parse("2026-02-15T00:00:00Z").toEpochMilli()
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-kept", 1L))
        val attempt = root.resolve("attempt")
        Files.createDirectories(attempt)
        Files.write(attempt.resolve("gap-loss"), byteArrayOf())
        Files.write(root.resolve("segment.bin"), byteArrayOf(1))
        val rows = ready(store)
        assertTrue(rows.none { it.material == "old" })
        assertTrue(rows.any { it.material == "rec-kept" })
        assertTrue(Files.isRegularFile(attempt.resolve("gap-loss")))
        assertTrue(Files.isRegularFile(root.resolve("segment.bin")))

        val capped = store(temp())
        now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
        val unseen = material(0)
        assertTrue(capped.recordLoss("audio", "undeliverable", unseen, now))
        val seenIds = mutableListOf<String>()
        repeat(500) { index ->
            now += 1
            val name = material(index + 1)
            assertTrue(capped.recordLoss("audio", "undeliverable", name, now))
            seenIds += ready(capped).single { it.material == name }.id
        }
        assertTrue(capped.markSeen(seenIds.toSet(), emptySet()))
        val after = ready(capped)
        val losses = after.filter { it.kind == "loss" }
        assertTrue(losses.any { it.material == unseen && !it.seen })
        assertTrue(losses.size <= 500)

        val floodedDir = temp()
        val legacyBase = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
        val legacyFlood = (1..1001).map { index ->
            legacyRow(index.toString(16), "spool").copy(
                id = (0x100000 + index).toString(16).padStart(32, '0'),
                firstEpochMs = legacyBase + index,
                lastEpochMs = legacyBase + index,
            )
        }
        Files.write(floodedDir.resolve("events.v1"), encodeGapRows(legacyFlood))
        val flooded = store(floodedDir)
        now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
        assertTrue(flooded.recordLoss("audio", "undeliverable", "rec-gap", now))
        repeat(30) { index ->
            now += 1
            assertTrue(flooded.recordDiagnostic("spool", "delivered", material(index), null))
        }
        val floodRows = ready(flooded)
        assertTrue(floodRows.any { it.material == "rec-gap" && !it.seen })
        assertEquals(1, floodRows.count { it.reason == "delivered" })
        assertTrue(floodRows.count { it.kind == "legacy" } <= 1000)
    }

    @Test
    fun beforeOpenedALossIsUnseenAndSweepMarksItThenStops() {
        val root = temp()
        val store = store(root)
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", 5L))
        assertFalse(ready(store).single().seen)
        assertFalse(store.isOpened())
        val path = "spool/.draft/2026-10-25/main/leaf"
        assertTrue(store.sweepMilestone(listOf(path)))
        assertTrue(store.isOpened())
        assertTrue(ready(store).single().seen)
        val snap = store.snapshot(zone)
        assertEquals(listOf(path), snap?.unknownItems)
        assertTrue(snap?.unknownSeen?.contains(path) == true)
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-2", 6L))
        assertFalse(ready(store).single { it.material == "rec-2" }.seen)
        assertTrue(store.sweepMilestone(emptyList()))
        assertFalse(ready(store).single { it.material == "rec-2" }.seen)
    }

    @Test
    fun failedSweepDoesNotCreateOpened() {
        val root = temp()
        var fail = false
        val store = store(root, sync = { if (fail) throw IOException("sync failed") })
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", 5L))
        fail = true
        assertFalse(store.sweepMilestone(listOf("spool/.draft/2026-10-25/main/leaf")))
        assertFalse(store.isOpened())
    }

    @Test
    fun journalIdRules() {
        val root = temp()
        var rejectJournal = false
        var rejectSeen = false
        var rejectJournalSync = false
        var writingJournal = false
        val store = store(root, sync = { directory ->
            forceGapDirectory(directory)
            if (rejectJournalSync && writingJournal) {
                rejectJournalSync = false
                throw IOException("journal directory sync")
            }
        }, beforeWrite = { path ->
            writingJournal = path.fileName.toString() == "journal-id"
            if (rejectJournal && path.fileName.toString() == "journal-id") throw IOException("journal")
            if (rejectSeen && path.fileName.toString() == "unknown-seen") throw IOException("seen")
        })
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", 5L))
        assertTrue(store.journalId("journal-a"))
        assertEquals("journal-a", Files.readString(root.resolve("journal-id")).trim())
        assertFalse(ready(store).single().seen)
        assertTrue(store.journalId("journal-a"))
        assertFalse(ready(store).single().seen)
        assertTrue(store.journalId(null))
        assertEquals("journal-a", Files.readString(root.resolve("journal-id")).trim())
        assertFalse(ready(store).single().seen)
        val path = "spool/.draft/2026-10-25/main/leaf"
        assertTrue(store.rewriteUnknown(listOf(path, "spool/.draft/2026-10-25/main/stale")))
        assertTrue(store.markSeen(emptySet(), setOf(path, "spool/.draft/2026-10-25/main/stale")))
        val priorSeen = store.snapshot(zone)?.unknownSeen
        rejectSeen = true
        assertFalse(store.journalId("journal-b"))
        assertEquals("journal-a", Files.readString(root.resolve("journal-id")).trim())
        assertFalse(ready(store).single().seen)
        assertEquals(priorSeen, store.snapshot(zone)?.unknownSeen)
        rejectSeen = false
        rejectJournal = true
        assertFalse(store.journalId("journal-b"))
        assertEquals("journal-a", Files.readString(root.resolve("journal-id")).trim())
        assertFalse(ready(store).single().seen)
        assertEquals(priorSeen, store.snapshot(zone)?.unknownSeen)
        rejectJournal = false
        rejectJournalSync = true
        assertFalse(store.journalId("journal-b"))
        assertEquals("journal-a", Files.readString(root.resolve("journal-id")).trim())
        assertFalse(ready(store).single().seen)
        assertEquals(priorSeen, store.snapshot(zone)?.unknownSeen)
        assertTrue(store.journalId("journal-b"))
        assertTrue(ready(store).single().seen)
        assertEquals("journal-b", Files.readString(root.resolve("journal-id")).trim())
        assertTrue(store.snapshot(zone)?.unknownSeen?.contains(path) == true)
    }

    @Test
    fun unknownRewriteReplacesTheSetAndDropsStaleSeenKeys() {
        val root = temp()
        val store = store(root)
        assertTrue(store.snapshot(zone)?.unknownItems?.isEmpty() == true)
        val kept = "spool/.draft/2026-10-25/main/kept"
        val stale = "spool/.draft/2026-10-25/main/stale"
        assertTrue(store.rewriteUnknown(listOf(kept, stale)))
        assertTrue(store.markSeen(emptySet(), setOf(kept, stale)))
        assertTrue(store.rewriteUnknown(listOf(kept)))
        val snap = store.snapshot(zone)
        assertEquals(listOf(kept), snap?.unknownItems)
        assertEquals(setOf(kept), snap?.unknownSeen)
    }

    @Test
    fun anOmittedButPresentItemKeepsAcknowledgementAcrossStoreRecreation() {
        val root = temp().resolve("gap")
        val held = "spool/.draft/2026-10-25/main/kept"
        val item = root.parent.resolve(held)
        Files.createDirectories(item)
        try {
            val store = store(root)
            assertTrue(store.journalId("journal-a"))
            assertTrue(store.sweepMilestone(emptyList()))
            assertTrue(store.rewriteUnknown(listOf(held)))
            assertTrue(store.markSeen(emptySet(), setOf(held)))
            val pass = RecoveryPass()
            pass.run(listOf({ throw IOException("scan failed") }), { store.sweepMilestone(emptyList()) })
            assertTrue(pass.noticesMayShow)
            assertEquals(listOf(held), store.snapshot(zone)?.unknownItems)
            val reopened = store(root)
            assertTrue(reopened.rewriteUnknown(emptyList()))
            assertEquals(listOf(held), reopened.snapshot(zone)?.unknownItems)
            assertTrue(reopened.journalId("journal-b"))
            assertTrue(reopened.sweepMilestone(listOf(held)))
            assertEquals(setOf(held), reopened.snapshot(zone)?.unknownSeen)
            Files.delete(item)
            assertTrue(reopened.sweepMilestone(emptyList()))
            assertTrue(reopened.snapshot(zone)?.unknownItems?.isEmpty() == true)
            assertTrue(reopened.snapshot(zone)?.unknownSeen?.isEmpty() == true)
        } finally {
            Files.deleteIfExists(item)
        }
    }

    @Test
    fun anOlderSeenOnlyRecordStillNamesRetainedMaterialAfterPairing() {
        val root = temp().resolve("gap")
        val held = "spool/.draft/2026-10-25/main/kept"
        val item = root.parent.resolve(held)
        Files.createDirectories(item)
        try {
            val store = store(root)
            assertTrue(store.journalId("journal-a"))
            assertTrue(store.sweepMilestone(emptyList()))
            // A seen key can outlive an inventory omitted by an older recovery pass.
            assertTrue(store.markSeen(emptySet(), setOf(held)))
            val reopened = store(root)
            assertTrue(reopened.journalId("journal-b"))
            assertTrue(reopened.sweepMilestone(emptyList()))
            assertEquals(listOf(held), reopened.snapshot(zone)?.unknownItems)
            assertEquals(setOf(held), reopened.snapshot(zone)?.unknownSeen)
            Files.delete(item)
            assertTrue(reopened.rewriteUnknown(emptyList()))
            assertTrue(reopened.snapshot(zone)?.unknownItems?.isEmpty() == true)
            assertTrue(reopened.snapshot(zone)?.unknownSeen?.isEmpty() == true)
        } finally {
            Files.deleteIfExists(item)
        }
    }

    @Test
    fun anInterruptedFirstOpeningRetainsItsInventoryOnRetry() {
        val root = temp().resolve("gap")
        val held = "spool/.draft/2026-10-25/main/kept"
        val item = root.parent.resolve(held)
        Files.createDirectories(item)
        try {
            val store = store(root, beforeWrite = { target ->
                if (target.fileName.toString() == "opened") throw IOException("interrupted opening")
            })
            assertFalse(store.sweepMilestone(listOf(held)))
            assertFalse(store.isOpened())
            val reopened = store(root)
            assertTrue(reopened.sweepMilestone(emptyList()))
            assertTrue(reopened.isOpened())
            assertEquals(listOf(held), reopened.snapshot(zone)?.unknownItems)
            assertEquals(setOf(held), reopened.snapshot(zone)?.unknownSeen)
        } finally {
            Files.deleteIfExists(item)
        }
    }

    @Test
    fun markSeenFlipsOnlyTheGivenIdsWhileAnotherLossIsRecorded() {
        val root = temp()
        val store = store(root)
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-a", 1L))
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-b", 2L))
        val rows = ready(store)
        val idA = rows.single { it.material == "rec-a" }.id
        val idB = rows.single { it.material == "rec-b" }.id
        val entered = CountDownLatch(1)
        val writer = Thread {
            entered.await()
            assertTrue(store.recordLoss("screen", "undeliverable", "rec-c", 3L))
        }
        writer.start()
        store.duringLock = {
            entered.countDown()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
        }
        assertTrue(store.markSeen(setOf(idA), emptySet()))
        writer.join(2_000)
        assertFalse(writer.isAlive)
        val after = ready(store)
        assertTrue(after.single { it.id == idA }.seen)
        assertFalse(after.single { it.id == idB }.seen)
        assertFalse(after.single { it.material == "rec-c" }.seen)
    }

    @Test
    fun failedFactWriteLeavesTheFolderWithoutAFact() {
        val dir = temp()
        var fail = true
        assertFalse(writeGapLossFact(dir) { if (fail) throw IOException("sync") })
        assertFalse(Files.exists(dir.resolve("gap-loss")))
        fail = false
        assertTrue(writeGapLossFact(dir) { if (fail) throw IOException("sync") })
        assertTrue(Files.isRegularFile(dir.resolve("gap-loss")))
        assertEquals(0, Files.size(dir.resolve("gap-loss")))
    }

    @Test
    fun twoInstancesOnOneDirectoryWaitAndLoseNothing() {
        val root = temp()
        val ids = AtomicInteger()
        fun open() = GapNoticeStore(
            directory = root,
            clock = { now },
            ids = { ids.incrementAndGet().toString(16).padStart(32, '0') },
            zone = { zone },
        )
        val writer = open()
        val marker = open()
        assertTrue(writer.recordLoss("audio", "undeliverable", "rec-a", 1L))
        val id = ready(writer).single().id
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val oks = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val writing = Thread {
            start.await()
            repeat(30) { index -> oks += writer.recordLoss("audio", "undeliverable", "rec-w-$index", 1L) }
            done.countDown()
        }
        val marking = Thread {
            start.await()
            repeat(30) { index ->
                oks += marker.recordLoss("screen", "undeliverable", "rec-m-$index", 1L)
                oks += marker.markSeen(setOf(id), emptySet())
            }
            done.countDown()
        }
        writing.start()
        marking.start()
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        writing.join(2_000)
        marking.join(2_000)
        assertTrue(oks.all { it })
        val rows = ready(writer)
        assertEquals(61, rows.count { it.kind == "loss" })
        assertTrue(rows.single { it.id == id }.seen)
    }

    @Test
    fun aFailedLossWriteIsLoggedAndADuplicateIsNotLoggedAgain() {
        val failed = mutableListOf<String>()
        val broken = store(temp(), beforeWrite = { path ->
            if (path.fileName.toString() == "events.v1") throw IOException("events")
        }, listener = { kind, source, reason, material ->
            failed += "$kind $source $reason $material"
        })
        assertFalse(broken.recordLoss("audio", "undeliverable", "rec-1", 1L))
        assertEquals(
            listOf("kept audio loss could not be recorded (undeliverable); material kept for a later pass rec-1"),
            failed,
        )
        assertFalse(broken.recordLoss("audio", "undeliverable", "bad id", 1L))
        assertEquals(1, failed.size)

        val logged = mutableListOf<String>()
        val store = store(temp(), listener = { kind, _, _, material -> logged += "$kind $material" })
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", 1L))
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", 1L))
        assertEquals(listOf("loss rec-1"), logged)
    }

    @Test
    fun aSeenLossStaysSeenAfterItsEventIsPruned() {
        val store = store(temp())
        now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-kept", 1L))
        val id = ready(store).single().id
        assertTrue(store.markSeen(setOf(id), emptySet()))
        now = Instant.parse("2026-02-15T00:00:00Z").toEpochMilli()
        assertTrue(ready(store).none { it.material == "rec-kept" })
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-kept", 1L))
        val again = ready(store).single { it.material == "rec-kept" }
        assertTrue(again.seen)
        assertTrue(again.id != id)
    }

    @Test
    fun aSeenMaterialKeyDropsOnceTheMaterialIsGone() {
        var present = true
        val store = store(temp(), materialExists = { _, material -> present && material == "rec-kept" })
        now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-kept", 1L))
        val id = ready(store).single().id
        assertTrue(store.markSeen(setOf(id), emptySet()))
        now = Instant.parse("2026-02-15T00:00:00Z").toEpochMilli()
        assertTrue(ready(store).none { it.material == "rec-kept" })
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-kept", 1L))
        assertTrue(ready(store).single { it.material == "rec-kept" }.seen)
        val again = ready(store).single { it.material == "rec-kept" }.id
        assertTrue(store.markSeen(setOf(again), emptySet()))
        present = false
        now = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli()
        assertTrue(ready(store).none { it.material == "rec-kept" })
        store.dropGoneSeenMaterials()
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-kept", 1L))
        assertFalse(ready(store).single { it.material == "rec-kept" }.seen)
    }

    @Test
    fun aReadDoesNotAskWhetherMaterialExists() {
        val root = temp()
        Files.createDirectories(root)
        Files.writeString(root.resolve(GapNoticeStore.SEEN_MATERIALS), "audio\trec-kept\n")
        var asked = false
        val store = store(root, materialExists = { _, _ ->
            asked = true
            true
        })
        assertIs<GapRead.Ready>(store.read(zone))
        assertFalse(asked)
        assertTrue(store.recordLoss("audio", "undeliverable", "other-rec", 1L))
        assertFalse(asked)
        assertTrue(store.recordDiagnostic("spool", "empty draft", "other-rec", null))
        assertFalse(asked)
    }

    @Test
    fun anUnknownSeenMarkSurvivesEventPruning() {
        val store = store(temp())
        val path = "spool/.draft/2026-10-25/main/kept"
        assertTrue(store.rewriteUnknown(listOf(path)))
        assertTrue(store.markSeen(emptySet(), setOf(path)))
        now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertTrue(store.recordLoss("audio", "undeliverable", "rec-old", 1L))
        val id = ready(store).single { it.material == "rec-old" }.id
        assertTrue(store.markSeen(setOf(id), emptySet()))
        now = Instant.parse("2026-02-15T00:00:00Z").toEpochMilli()
        assertTrue(ready(store).none { it.material == "rec-old" })
        assertEquals(setOf(path), store.snapshot(zone)?.unknownSeen)
    }

    @Test
    fun ownerMaterialExistsChecksOnlyTheOwnerSourceTrees() {
        val files = temp()
        val gap = files.resolve("gap")
        Files.createDirectories(gap)
        val audio = files.resolve("audio-source").resolve("rec-1")
        Files.createDirectories(audio)
        Files.write(audio.resolve("clip.m4a"), byteArrayOf(1))
        val other = files.resolve("other")
        Files.createDirectories(other)
        Files.write(other.resolve("clip.m4a"), byteArrayOf(1))
        val exists = ownerMaterialExists(gap)
        assertTrue(exists("audio", "clip.m4a"))
        assertFalse(exists("audio", "missing.m4a"))
        Files.delete(audio.resolve("clip.m4a"))
        assertFalse(exists("audio", "clip.m4a"))
    }

    @Test
    fun anImportedMarkerIsKeptFromTheImportTimeAndShowsTheMarkerTime() {
        val root = temp()
        val marker = root.resolve("audio-interruption")
        Files.writeString(marker, "unresolved")
        val old = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        Files.setLastModifiedTime(marker, FileTime.fromMillis(old))
        val markerTime = Files.getLastModifiedTime(marker).toMillis()
        now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
        assertTrue(markerTime < now - 40L * 24 * 60 * 60 * 1000)
        val store = store(root)
        assertEquals("complete 1", store.conversion(listOf("audio" to marker)))
        assertFalse(Files.exists(marker))
        val row = ready(store).single { it.kind == "legacy" }
        assertEquals(now, row.foundEpochMs)
        assertEquals(now, row.lastEpochMs)
        assertTrue(row.seen)
        assertEquals(markerTime, row.markerEpochMs)
        val details = formatGapTechnicalDetails(listOf(row), GapConversionState.Complete(1), now, zone)
        assertTrue(details.contains("marked"))
    }

    private fun ready(store: GapNoticeStore): List<GapNoticeRow> =
        assertIs<GapRead.Ready>(store.read(zone)).rows

    private fun store(
        root: Path,
        sync: (Path) -> Unit = ::forceGapDirectory,
        beforeWrite: (Path) -> Unit = {},
        listener: (String, String, String, String) -> Unit = { _, _, _, _ -> },
        materialExists: (String, String) -> Boolean = { _, _ -> true },
    ) = GapNoticeStore(
        directory = root,
        clock = { now },
        ids = {
            nextId += 1
            nextId.toString(16).padStart(32, '0')
        },
        zone = { zone },
        syncDirectory = sync,
        beforeWrite = beforeWrite,
        listener = listener,
        materialExists = materialExists,
    )

    private fun temp(): Path = Files.createTempDirectory("gap-notice")

    private fun material(index: Int): String = "rec-" + index.toString().padStart(4, '0')

    private fun legacyRow(idTail: String, source: String) = GapNoticeRow(
        id = idTail.padStart(32, 'a'),
        kind = "legacy",
        source = source,
        reason = "legacy marker",
        material = "marker",
        capturedEpochMs = null,
        foundEpochMs = now,
        seen = false,
        count = 1,
        firstEpochMs = now,
        lastEpochMs = now,
    )

    private fun assertNullSnapshot(store: GapNoticeStore) {
        assertEquals(null, store.snapshot(zone))
    }
}
