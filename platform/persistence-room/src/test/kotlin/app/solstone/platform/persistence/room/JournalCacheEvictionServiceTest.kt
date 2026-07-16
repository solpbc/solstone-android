// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.queue.QueueEvent
import app.solstone.core.segment.SealedSegment
import app.solstone.core.spool.serializeManifest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalCacheEvictionServiceTest {
    @Test
    fun realApplyEvictionsRunsBeforeRemovalAndReclaimedSpaceRequiresAbsence() {
        val root = Files.createTempDirectory("journal-cache-service")
        val spool = root.resolve("spool")
        val row = row("one", 1)
        writeSegment(spool, row)
        val dao = FakeSegmentDao(mutableListOf(row))
        val service = service(spool, dao, root, SpoolFreeSpaceProvider { 0L })

        val result = service.runPass(9_000)

        assertEquals(listOf(row.id), result.durablyMarkedIds)
        assertEquals(listOf(row.id), result.reclaimedSpace.removals.map { it.segmentId })
        assertTrue(result.reclaimedSpace.totalBytes > 0)
        assertEquals(QueueState.EVICTED, dao.segmentById(row.id)?.state)
        assertFalse(Files.exists(spool.resolve(row.day).resolve(row.stream).resolve(row.dirSegment)))
    }

    @Test
    fun unmeasuredMissingUploadedDirectoryIsRefusedOnceAndTerminates() {
        val root = Files.createTempDirectory("journal-cache-service")
        val spool = root.resolve("spool")
        Files.createDirectories(spool.resolve(".draft/day/stream/draft"))
        Files.write(spool.resolve(".draft/day/stream/draft/partial"), byteArrayOf(1))
        val missing = row("missing", 1)
        val dao = FakeSegmentDao(mutableListOf(missing))

        val result = service(spool, dao, root, SpoolFreeSpaceProvider { 0L }).runPass(9_000)

        assertEquals(listOf(missing.id), result.refusedPathIds)
        assertTrue(result.durablyMarkedIds.isEmpty())
        assertEquals(JournalCacheBlockedReason.NO_SAFE_ELIGIBLE_SEGMENT, result.blockedReason)
        assertEquals(QueueState.UPLOADED, dao.segmentById(missing.id)?.state)
    }

    @Test
    fun partialRemovalIsDurablyMarkedButNeverReportedReclaimed() {
        val root = Files.createTempDirectory("journal-cache-service")
        val spool = root.resolve("spool")
        val row = row("one", 1)
        writeSegment(spool, row)
        val dao = FakeSegmentDao(mutableListOf(row))
        val result = JournalCacheEvictionService(
            spool,
            dao,
            JournalCacheLimitStore(root.resolve("limit").toFile()),
            NioSpoolUsageMeasurer(),
            SpoolFreeSpaceProvider { 0L },
            SpoolDirectoryRemover { DirectoryRemovalResult.Incomplete },
        ).runPass(9_000)

        assertEquals(listOf(row.id), result.durablyMarkedIds)
        assertEquals(0L, result.reclaimedSpace.totalBytes)
        assertEquals(listOf(row.id), result.retryableResidualIds)
        assertTrue(Files.exists(spool.resolve(row.day).resolve(row.stream).resolve(row.dirSegment)))
    }

    @Test
    fun measurementAndFreeSpaceFailuresFailClosed() {
        val root = Files.createTempDirectory("journal-cache-service")
        val spool = root.resolve("spool")
        val row = row("one", 1)
        val dao = FakeSegmentDao(mutableListOf(row))
        val store = JournalCacheLimitStore(root.resolve("limit").toFile())
        val measurementFailure = JournalCacheEvictionService(
            spool, dao, store, SpoolUsageMeasurer { error("stat") }, SpoolFreeSpaceProvider { 0 }, NioSpoolDirectoryRemover(),
        ).runPass(1)
        assertEquals(JournalCacheBlockedReason.MEASUREMENT_FAILED, measurementFailure.blockedReason)
        assertEquals(QueueState.UPLOADED, dao.segmentById(row.id)?.state)

        val freeFailure = JournalCacheEvictionService(
            spool, dao, store, NioSpoolUsageMeasurer(), SpoolFreeSpaceProvider { error("free") }, NioSpoolDirectoryRemover(),
        ).runPass(1)
        assertEquals(JournalCacheBlockedReason.FREE_SPACE_FAILED, freeFailure.blockedReason)
        assertEquals(QueueState.UPLOADED, dao.segmentById(row.id)?.state)
    }

    private fun service(spool: java.nio.file.Path, dao: SegmentDao, root: java.nio.file.Path, free: SpoolFreeSpaceProvider) =
        JournalCacheEvictionService(spool, dao, JournalCacheLimitStore(root.resolve("limit").toFile()), NioSpoolUsageMeasurer(), free, NioSpoolDirectoryRemover())

    private fun writeSegment(spool: java.nio.file.Path, row: SegmentRow) {
        val directory = spool.resolve(row.day).resolve(row.stream).resolve(row.dirSegment)
        Files.createDirectories(directory)
        Files.write(directory.resolve("payload"), ByteArray(10))
        val sealed = SealedSegment(row.stream, SegmentKey(row.day, row.segment), WireKeys(row.day, row.segment, 1, 2, "UTC", 0), emptyList(), emptyList())
        Files.write(directory.resolve("manifest"), serializeManifest(sealed, BundleManifest(sealed.key, emptyList(), emptyList())).toByteArray())
    }

    private fun row(leaf: String, sealedAt: Long) = SegmentRow(
        id = "20260716/audio/$leaf", day = "20260716", stream = "audio", segment = leaf, dirSegment = leaf,
        state = QueueState.UPLOADED, byteSize = 999, sealedAt = sealedAt, homeInstanceId = null, observerHandle = null,
    )

    /** Runs the real open DAO transaction bodies, but cannot prove SQLite rollback atomicity. */
    private class FakeSegmentDao(private val segments: MutableList<SegmentRow>) : SegmentDao() {
        private val files = mutableListOf<SegmentFileRow>()
        override fun insertSegment(segment: SegmentRow) { segments.removeAll { it.id == segment.id }; segments += segment }
        override fun insertFiles(files: List<SegmentFileRow>) { this.files += files }
        override fun insertEvents(events: List<EventRow>) = Unit
        override fun segmentsByState(state: QueueState) = segments.filter { it.state == state }.sortedWith(compareBy<SegmentRow> { it.sealedAt }.thenBy { it.id })
        override fun segmentsForDrain(stream: String) = segments.filter { it.stream == stream && it.state in setOf(QueueState.SEALED, QueueState.UPLOADING, QueueState.FAILED) }
        override fun segmentsByDay(day: String) = segments.filter { it.day == day }
        override fun segmentById(id: String) = segments.firstOrNull { it.id == id }
        override fun duplicateBySha256(sha256: String) = files.filter { it.sha256 == sha256 }
        override fun filesBySegmentId(segmentId: String) = files.filter { it.segmentId == segmentId }
        override fun recordAttempt(id: String, attempts: Int, at: Long) = 0
        override fun recordUploaded(id: String, serverKey: String?) = 0
        override fun recordFailure(id: String, code: Int?, error: String?) = 0
        override fun recordDedupeChecked(id: String, at: Long) = 0
        override fun upsertSyncState(row: SyncStateRow) = Unit
        override fun syncState(): SyncStateRow? = null
        override fun pendingCount(stream: String) = 0
        override fun segmentState(id: String) = segmentById(id)?.state
        override fun updateState(id: String, state: QueueState): Int {
            val index = segments.indexOfFirst { it.id == id }
            if (index < 0) return 0
            segments[index] = segments[index].copy(state = state)
            return 1
        }
        override fun deleteFilesBySegmentId(segmentId: String): Int = removeFiles { it.segmentId == segmentId }
        override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int = removeFiles { it.segmentId in segmentIds }
        override fun deleteFilesBySource(sourceId: String): Int = removeFiles { it.sourceId == sourceId }
        private fun removeFiles(predicate: (SegmentFileRow) -> Boolean): Int { val before = files.size; files.removeAll(predicate); return before - files.size }
    }
}
