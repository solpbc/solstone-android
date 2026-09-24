// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.queue.QueueEvent
import app.solstone.core.queue.transition
import app.solstone.core.segment.SealedSegment
import app.solstone.core.spool.serializeManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmedCopyFinisherTest {
    @Test
    fun proofRefusalLeavesRowNonTerminalAndDirectoryIntact() {
        val root = Files.createTempDirectory("finisher-test")
        val spool = root.resolve("spool")
        val dao = InMemorySegmentDao()
        val finisher = ConfirmedCopyFinisher(spool, dao)

        val row = createSegmentFixture(spool, "20260716", "audio", "one", QueueState.UPLOADED)
        dao.insertSegment(row)

        // Corrupt manifest so identity mismatches
        val manifestFile = spool.resolve("20260716/audio/one/manifest")
        val corruptSealed = SealedSegment("audio", SegmentKey("20260716", "mismatch"), WireKeys("20260716", "mismatch", 1, 2, "UTC", 0), emptyList(), emptyList())
        Files.write(manifestFile, serializeManifest(corruptSealed, BundleManifest(corruptSealed.key, emptyList(), emptyList())).toByteArray())

        assertFalse(finisher.confirmationReady(row))
        finisher.finishUploaded(row.id)

        val after = dao.segmentById(row.id)!!
        assertEquals(QueueState.UPLOADED, after.state)
        assertNull(after.lastError)
        assertTrue(Files.exists(spool.resolve("20260716/audio/one")))
    }

    @Test
    fun evictedDirectoryWithPayloadAndNoManifestRemovedOnPathProofAlone() {
        val root = Files.createTempDirectory("finisher-test")
        val spool = root.resolve("spool")
        val dao = InMemorySegmentDao()
        val finisher = ConfirmedCopyFinisher(spool, dao)

        val row = createSegmentFixture(spool, "20260716", "audio", "evicted_no_manifest", QueueState.EVICTED)
        dao.insertSegment(row)

        // Delete manifest file
        Files.delete(spool.resolve("20260716/audio/evicted_no_manifest/manifest"))
        assertTrue(Files.exists(spool.resolve("20260716/audio/evicted_no_manifest/payload.bin")))

        finisher.finishUploaded(row.id)

        assertFalse(Files.exists(spool.resolve("20260716/audio/evicted_no_manifest")))
        assertEquals(QueueState.EVICTED, dao.segmentById(row.id)!!.state)
    }

    @Test
    fun finishPassProcessesUploadedAndLegacyRemovedAndSkipsOthers() {
        val root = Files.createTempDirectory("finisher-test")
        val spool = root.resolve("spool")
        val dao = InMemorySegmentDao()
        val finisher = ConfirmedCopyFinisher(spool, dao)

        val uploadedRow = createSegmentFixture(spool, "20260716", "audio", "uploaded", QueueState.UPLOADED)
        val legacyRemovedRow = createSegmentFixture(spool, "20260716", "audio", "legacy_removed", QueueState.FAILED, lastError = "removed_in_journal")
        val sealedRow = createSegmentFixture(spool, "20260716", "audio", "sealed", QueueState.SEALED)
        val uploadingRow = createSegmentFixture(spool, "20260716", "audio", "uploading", QueueState.UPLOADING)
        val otherFailedRow = createSegmentFixture(spool, "20260716", "audio", "other_failed", QueueState.FAILED, lastError = "io_error")

        // Also an uploaded row whose directory is already gone
        val missingDirUploaded = SegmentRow(
            id = "20260716/audio/missing_dir",
            day = "20260716",
            stream = "audio",
            segment = "missing_dir",
            dirSegment = "missing_dir",
            state = QueueState.UPLOADED,
            byteSize = 10,
            sealedAt = 1,
            homeInstanceId = null,
            observerHandle = null,
        )

        listOf(uploadedRow, legacyRemovedRow, sealedRow, uploadingRow, otherFailedRow, missingDirUploaded).forEach {
            dao.insertSegment(it)
        }

        finisher.finishPass()

        assertEquals(QueueState.EVICTED, dao.segmentById(uploadedRow.id)!!.state)
        assertFalse(Files.exists(spool.resolve("20260716/audio/uploaded")))

        assertEquals(QueueState.EVICTED, dao.segmentById(legacyRemovedRow.id)!!.state)
        assertFalse(Files.exists(spool.resolve("20260716/audio/legacy_removed")))

        assertEquals(QueueState.EVICTED, dao.segmentById(missingDirUploaded.id)!!.state)
        assertNull(dao.segmentById(missingDirUploaded.id)!!.lastError)

        assertEquals(QueueState.SEALED, dao.segmentById(sealedRow.id)!!.state)
        assertTrue(Files.exists(spool.resolve("20260716/audio/sealed")))

        assertEquals(QueueState.UPLOADING, dao.segmentById(uploadingRow.id)!!.state)
        assertTrue(Files.exists(spool.resolve("20260716/audio/uploading")))

        assertEquals(QueueState.FAILED, dao.segmentById(otherFailedRow.id)!!.state)
        assertEquals("io_error", dao.segmentById(otherFailedRow.id)!!.lastError)
        assertTrue(Files.exists(spool.resolve("20260716/audio/other_failed")))
    }

    @Test
    fun databaseWriteFailureOnOneRowLeavesItAndFinishesTheRest() {
        val root = Files.createTempDirectory("finisher-test")
        val spool = root.resolve("spool")
        val dao = InMemorySegmentDao()
        val logs = mutableListOf<String>()
        val finisher = ConfirmedCopyFinisher(spool, dao, log = { logs += it })

        val fullUploaded = createSegmentFixture(spool, "20260716", "audio", "full_uploaded", QueueState.UPLOADED)
        val uploaded = createSegmentFixture(spool, "20260716", "audio", "uploaded", QueueState.UPLOADED)
        val fullLegacy = createSegmentFixture(spool, "20260716", "audio", "full_legacy", QueueState.FAILED, lastError = "removed_in_journal")
        val legacy = createSegmentFixture(spool, "20260716", "audio", "legacy", QueueState.FAILED, lastError = "removed_in_journal")
        listOf(fullUploaded, uploaded, fullLegacy, legacy).forEach { dao.insertSegment(it) }
        dao.failUpdateFor += listOf(fullUploaded.id, fullLegacy.id)

        finisher.finishPass()

        assertEquals(QueueState.UPLOADED, dao.segmentById(fullUploaded.id)!!.state)
        assertTrue(Files.exists(spool.resolve("20260716/audio/full_uploaded")))
        assertEquals(QueueState.FAILED, dao.segmentById(fullLegacy.id)!!.state)
        assertTrue(Files.exists(spool.resolve("20260716/audio/full_legacy")))

        assertEquals(QueueState.EVICTED, dao.segmentById(uploaded.id)!!.state)
        assertFalse(Files.exists(spool.resolve("20260716/audio/uploaded")))
        assertEquals(QueueState.EVICTED, dao.segmentById(legacy.id)!!.state)
        assertFalse(Files.exists(spool.resolve("20260716/audio/legacy")))

        assertEquals(
            listOf(
                "confirmed copy not finished ${fullUploaded.id} IllegalStateException",
                "confirmed copy not finished ${fullLegacy.id} IllegalStateException",
            ),
            logs,
        )
    }

    @Test
    fun manifestRefusalIsLoggedWithRowAndReason() {
        val root = Files.createTempDirectory("finisher-test")
        val spool = root.resolve("spool")
        val dao = InMemorySegmentDao()
        val logs = mutableListOf<String>()
        val finisher = ConfirmedCopyFinisher(spool, dao, log = { logs += it })

        val row = createSegmentFixture(spool, "20260716", "audio", "no_manifest", QueueState.UPLOADED)
        dao.insertSegment(row)
        Files.delete(spool.resolve("20260716/audio/no_manifest/manifest"))

        assertEquals(JournalCachePathRefusal.MISSING_MANIFEST, finisher.confirmationRefusal(row))
        finisher.finishPass()

        assertEquals(QueueState.UPLOADED, dao.segmentById(row.id)!!.state)
        assertTrue(Files.exists(spool.resolve("20260716/audio/no_manifest")))
        assertEquals(listOf("confirmed copy refused ${row.id} MISSING_MANIFEST"), logs)
    }

    private fun createSegmentFixture(
        spoolRoot: Path,
        day: String,
        stream: String,
        dirSegment: String,
        state: QueueState,
        lastError: String? = null,
    ): SegmentRow {
        val dir = spoolRoot.resolve(day).resolve(stream).resolve(dirSegment)
        Files.createDirectories(dir)
        val payload = dir.resolve("payload.bin")
        Files.write(payload, "sample bytes".toByteArray())
        val sealed = SealedSegment(
            stream = stream,
            key = SegmentKey(day, dirSegment),
            wireKeys = WireKeys(day, dirSegment, 1000L, 2000L, "UTC", 0),
            payloads = emptyList(),
            gaps = emptyList(),
        )
        val manifest = serializeManifest(
            sealed,
            BundleManifest(
                key = sealed.key,
                files = listOf(
                    BundleFile("s1", "payload.bin", "sha", 12, "application/octet-stream", 1000L, 2000L),
                ),
                gaps = emptyList(),
            ),
        )
        Files.write(dir.resolve("manifest"), manifest.toByteArray())
        return SegmentRow(
            id = "$day/$stream/$dirSegment",
            day = day,
            stream = stream,
            segment = dirSegment,
            dirSegment = dirSegment,
            state = state,
            byteSize = 12,
            sealedAt = 1000L,
            homeInstanceId = null,
            observerHandle = null,
            lastError = lastError,
        )
    }

    private class InMemorySegmentDao : SegmentDao() {
        private val segments = mutableMapOf<String, SegmentRow>()
        val failUpdateFor = mutableSetOf<String>()

        override fun insertSegment(segment: SegmentRow) {
            segments[segment.id] = segment
        }

        override fun insertFiles(files: List<SegmentFileRow>) = Unit
        override fun insertEvents(events: List<EventRow>) = Unit

        override fun segmentsByState(state: QueueState): List<SegmentRow> =
            segments.values.filter { it.state == state }

        override fun segmentsForDrain(stream: String): List<SegmentRow> =
            segments.values.filter { it.stream == stream && it.state in setOf(QueueState.SEALED, QueueState.UPLOADING, QueueState.FAILED) }

        override fun segmentsByDay(day: String): List<SegmentRow> =
            segments.values.filter { it.day == day }

        override fun segmentById(id: String): SegmentRow? = segments[id]

        override fun duplicateBySha256(sha256: String): List<SegmentFileRow> = emptyList()
        override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> = emptyList()

        override fun recordAttempt(id: String, attempts: Int, at: Long): Int {
            val current = segments[id] ?: return 0
            segments[id] = current.copy(attemptCount = attempts, lastAttemptAt = at)
            return 1
        }

        override fun recordUploaded(id: String): Int {
            val current = segments[id] ?: return 0
            segments[id] = current.copy(lastError = null)
            return 1
        }

        override fun recordFailure(id: String, code: Int?, error: String?): Int {
            val current = segments[id] ?: return 0
            segments[id] = current.copy(lastStatusCode = code, lastError = error)
            return 1
        }

        override fun upsertSyncState(row: SyncStateRow) = Unit
        override fun syncState(): SyncStateRow? = null

        override fun pendingCount(stream: String): Int =
            segments.values.count { it.stream == stream && it.state in setOf(QueueState.SEALED, QueueState.UPLOADING, QueueState.FAILED) && it.lastError != "removed_in_journal" }

        override fun pendingSourceIds(stream: String): List<String> = emptyList()

        override fun segmentState(id: String): QueueState? = segments[id]?.state

        override fun updateState(id: String, state: QueueState): Int {
            if (id in failUpdateFor) throw IllegalStateException("database or disk is full")
            val current = segments[id] ?: return 0
            segments[id] = current.copy(state = state)
            return 1
        }

        override fun deleteFilesBySegmentId(segmentId: String): Int = 0
        override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int = 0
        override fun deleteFilesBySource(sourceId: String): Int = 0
    }
}
