// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.QueueState
import app.solstone.core.model.SourceKind
import app.solstone.core.queue.QueueEvent
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.spool.CountingSpoolWriter
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.sources.LOCATION_STREAM
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RoomSealedSegmentSinkTest {
    @Test
    fun persistsMultipleSourceFilesInOneStreamSegmentAndSeparatesStreams() {
        val dao = FakeSegmentDao()
        val sink = RoomSealedSegmentSink(dao)
        val writer = CountingSpoolWriter()
        val main = Segmenter(ZoneId.of("UTC")).run {
            feed(emission(sourceId = "audio-a", stream = MAIN_STREAM, name = "a.m4a"))
            feed(emission(sourceId = "audio-b", stream = MAIN_STREAM, name = "b.m4a"))
            flush().single()
        }
        val location = Segmenter(ZoneId.of("UTC")).run {
            feed(emission(sourceId = "location", stream = LOCATION_STREAM, name = "location.jsonl"))
            flush().single()
        }

        sink.persistSealed(main, writer.seal(main, provider()), sealedAtEpochMs = 1L)
        sink.persistSealed(location, writer.seal(location, provider()), sealedAtEpochMs = 2L)

        assertEquals(2, dao.segments.size)
        assertNotNull(dao.segmentById("${main.key.day}/$MAIN_STREAM/${main.key.segment}"))
        assertNotNull(dao.segmentById("${location.key.day}/$LOCATION_STREAM/${location.key.segment}"))
        val mainFiles = dao.files.filter { it.segmentId == main.id() }
        assertEquals(listOf("audio-a", "audio-b"), mainFiles.map { it.sourceId }.sorted())
        assertEquals(2, mainFiles.size)
    }

    @Test
    fun reconcilerReconstructsRowsAndSkipsExistingId() {
        val baseDir = Files.createTempDirectory("spool-reconcile")
        try {
            val dao = FakeSegmentDao()
            val segment = Segmenter(ZoneId.of("UTC")).run {
                feed(emission(sourceId = "audio", stream = MAIN_STREAM, name = "audio.m4a"))
                flush().single()
            }
            FileSpoolWriter(baseDir).seal(segment, provider())

            val first = SpoolRoomReconciler(baseDir, dao).reconcile()
            val second = SpoolRoomReconciler(baseDir, dao).reconcile()

            assertEquals(1, first)
            assertEquals(0, second)
            assertEquals(1, dao.segments.size)
            assertEquals(1, dao.files.size)
            assertEquals(segment.id(), dao.segments.single().id)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    private fun emission(sourceId: String, stream: String, name: String): SourceEmission =
        SourceEmission(
            sourceId = sourceId,
            stream = stream,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = BASE_EPOCH_MS,
            captureEndEpochMs = BASE_EPOCH_MS + 300_000L,
            payloadRefs = listOf(PayloadRef(name, "application/octet-stream", 4, null)),
            metadata = emptyMap(),
            gaps = emptyList(),
        )

    private fun provider(): PayloadBytesProvider =
        PayloadBytesProvider { payload: SegmentPayload -> ByteArrayInputStream(ByteArray(payload.ref.byteSize.toInt()) { 7 }) }

    private class FakeSegmentDao : SegmentDao() {
        val segments = mutableListOf<SegmentRow>()
        val files = mutableListOf<SegmentFileRow>()

        override fun insertSegment(segment: SegmentRow) {
            segments.removeAll { it.id == segment.id }
            segments += segment
        }

        override fun insertFiles(files: List<SegmentFileRow>) {
            this.files += files
        }

        override fun insertEvents(events: List<EventRow>) = Unit

        override fun segmentsByState(state: QueueState): List<SegmentRow> =
            segments.filter { it.state == state }

        override fun segmentsByDay(day: String): List<SegmentRow> =
            segments.filter { it.day == day }

        override fun segmentById(id: String): SegmentRow? =
            segments.firstOrNull { it.id == id }

        override fun duplicateBySha256(sha256: String): List<SegmentFileRow> =
            files.filter { it.sha256 == sha256 }

        override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> =
            files.filter { it.segmentId == segmentId }

        override fun recordAttempt(id: String, attempts: Int, at: Long): Int = 0

        override fun recordUploaded(id: String, serverKey: String?): Int = 0

        override fun recordFailure(id: String, code: Int?, error: String?): Int = 0

        override fun recordDedupeChecked(id: String, at: Long): Int = 0

        override fun upsertSyncState(row: SyncStateRow) = Unit

        override fun syncState(): SyncStateRow? = null

        override fun pendingCount(stream: String): Int = 0

        override fun advanceState(id: String, event: QueueEvent): QueueState =
            segmentById(id)?.state ?: throw NoSuchElementException(id)

        override fun applyEvictions(result: app.solstone.core.queue.EvictionResult): app.solstone.core.queue.EvictionApplyResult =
            app.solstone.core.queue.EvictionApplyResult(emptyList(), 0, 0)

        override fun deleteSource(sourceId: String): app.solstone.core.queue.SourceDeleteResult =
            app.solstone.core.queue.SourceDeleteResult(sourceId, 0)

        override fun segmentState(id: String): QueueState? =
            segmentById(id)?.state

        override fun updateState(id: String, state: QueueState): Int = 0

        override fun deleteFilesBySegmentId(segmentId: String): Int {
            val before = files.size
            files.removeAll { it.segmentId == segmentId }
            return before - files.size
        }

        override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int {
            val before = files.size
            files.removeAll { it.segmentId in segmentIds }
            return before - files.size
        }

        override fun deleteFilesBySource(sourceId: String): Int {
            val before = files.size
            files.removeAll { it.sourceId == sourceId }
            return before - files.size
        }
    }

    private fun Path.deleteRecursively() {
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
    }
}
