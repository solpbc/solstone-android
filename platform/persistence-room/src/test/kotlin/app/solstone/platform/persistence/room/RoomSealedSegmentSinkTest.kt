// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.SourceKind
import app.solstone.core.model.WireKeys
import app.solstone.core.queue.QueueEvent
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.spool.CountingSpoolWriter
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
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
import kotlin.test.assertTrue

class RoomSealedSegmentSinkTest {
    @Test
    fun persistsAudioAndLocationFilesInOneObserverSegment() {
        val dao = FakeSegmentDao()
        val sink = RoomSealedSegmentSink(dao)
        val writer = CountingSpoolWriter()
        val segment = Segmenter(ZoneId.of("UTC")).run {
            feed(emission(sourceId = "audio", stream = MAIN_STREAM, name = "audio.m4a", captureStartEpochMs = BASE_EPOCH_MS + 12_000L))
            feed(emission(sourceId = "location", stream = MAIN_STREAM, name = "location.jsonl", captureStartEpochMs = BASE_EPOCH_MS + 47_000L))
            flush().sealed.single()
        }

        sink.persistSealed(segment, writer.seal(segment, provider()), sealedAtEpochMs = 1L)

        assertEquals(1, dao.segments.size)
        assertNotNull(dao.segmentById("${segment.key.day}/$MAIN_STREAM/${segment.key.segment}"))
        val files = dao.files.filter { it.segmentId == segment.id(segment.key.segment) }
        assertEquals(listOf("audio", "location"), files.map { it.sourceId }.sorted())
        assertEquals(listOf("audio.m4a", "location.jsonl"), files.map { it.name }.sorted())
    }

    @Test
    fun reconcilerReconstructsRowsAndSkipsExistingId() {
        val baseDir = Files.createTempDirectory("spool-reconcile")
        try {
            val dao = FakeSegmentDao()
            val segment = Segmenter(ZoneId.of("UTC")).run {
                feed(emission(sourceId = "audio", stream = MAIN_STREAM, name = "audio.m4a"))
                flush().sealed.single()
            }
            FileSpoolWriter(baseDir).seal(segment, provider())

            val first = SpoolRoomReconciler(baseDir, dao).reconcile()
            val second = SpoolRoomReconciler(baseDir, dao).reconcile()

            assertEquals(1, first)
            assertEquals(0, second)
            assertEquals(1, dao.segments.size)
            assertEquals(1, dao.files.size)
            assertEquals(segment.id(segment.key.segment), dao.segments.single().id)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun insertSegmentWithFilesAllowsRowsSharingWireKeyWhenIdsDiffer() {
        val dao = FakeSegmentDao()
        val wireKey = "011500_300"
        val bare = segmentRow(
            id = "$DAY/$MAIN_STREAM/$wireKey",
            segment = wireKey,
            dirSegment = wireKey,
        )
        val suffixed = segmentRow(
            id = "$DAY/$MAIN_STREAM/${wireKey}__ws1793519100000",
            segment = wireKey,
            dirSegment = "${wireKey}__ws1793519100000",
        )

        dao.insertSegmentWithFiles(bare, listOf(fileRow(bare.id, "sha-bare")))
        dao.insertSegmentWithFiles(suffixed, listOf(fileRow(suffixed.id, "sha-suffixed")))

        assertEquals(2, dao.segments.size)
        assertEquals(listOf(wireKey, wireKey), dao.segments.map { it.segment })
        assertEquals(listOf(wireKey, "${wireKey}__ws1793519100000"), dao.segments.map { it.dirSegment })
        assertEquals(listOf("sha-bare", "sha-suffixed"), dao.files.map { it.sha256 })
    }

    @Test
    fun reconcilerInsertsCollisionDirsUsingDirLeafIdsAndBareWireSegment() {
        val baseDir = Files.createTempDirectory("spool-reconcile-collision")
        try {
            val dao = FakeSegmentDao()
            val first = manualSegment(
                startEpochMs = FIRST_START,
                endEpochMs = FIRST_START + WINDOW_MS,
                payloadName = "first.bin",
            )
            val second = manualSegment(
                startEpochMs = SECOND_START,
                endEpochMs = SECOND_START + WINDOW_MS,
                payloadName = "second.bin",
            )
            FileSpoolWriter(baseDir).seal(first, provider())
            FileSpoolWriter(baseDir).seal(second, provider())

            val inserted = SpoolRoomReconciler(baseDir, dao).reconcile()

            assertEquals(2, inserted)
            assertEquals(
                listOf(
                    "${first.key.day}/$MAIN_STREAM/${first.key.segment}",
                    "${second.key.day}/$MAIN_STREAM/${second.key.segment}__ws${second.wireKeys.startEpochMs}",
                ),
                dao.segments.map { it.id }.sorted(),
            )
            assertEquals(listOf(first.key.segment, first.key.segment), dao.segments.map { it.segment })
            assertEquals(
                listOf(first.key.segment, "${second.key.segment}__ws${second.wireKeys.startEpochMs}"),
                dao.segments.map { it.dirSegment }.sorted(),
            )
            assertTrue(Files.exists(baseDir.resolve(first.key.day).resolve(MAIN_STREAM).resolve(first.key.segment)))
            assertTrue(
                Files.exists(
                    baseDir.resolve(second.key.day)
                        .resolve(MAIN_STREAM)
                        .resolve("${second.key.segment}__ws${second.wireKeys.startEpochMs}"),
                ),
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    private fun emission(
        sourceId: String,
        stream: String,
        name: String,
        captureStartEpochMs: Long = BASE_EPOCH_MS,
    ): SourceEmission =
        SourceEmission(
            sourceId = sourceId,
            stream = stream,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = captureStartEpochMs,
            captureEndEpochMs = captureStartEpochMs + 300_000L,
            payloadRefs = listOf(PayloadRef(name, "application/octet-stream", 4, null)),
            metadata = emptyMap(),
            gaps = emptyList(),
        )

    private fun provider(): PayloadBytesProvider =
        object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) =
                ByteArrayInputStream(ByteArray(payload.ref.byteSize.toInt()) { 7 })
        }

    private fun manualSegment(
        startEpochMs: Long,
        endEpochMs: Long,
        payloadName: String,
    ): SealedSegment =
        SealedSegment(
            stream = MAIN_STREAM,
            key = SegmentKey(day = DAY, segment = "011500_300"),
            wireKeys = WireKeys(
                day = DAY,
                segment = "011500_300",
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs,
                zoneId = "America/New_York",
                utcOffsetSeconds = -4 * 60 * 60,
            ),
            payloads = listOf(
                SegmentPayload(
                    sourceId = "audio",
                    ref = PayloadRef(payloadName, "application/octet-stream", 4, null),
                    captureStartEpochMs = startEpochMs,
                    captureEndEpochMs = endEpochMs,
                ),
            ),
            gaps = emptyList(),
        )

    private fun segmentRow(id: String, segment: String, dirSegment: String): SegmentRow =
        SegmentRow(
            id = id,
            day = DAY,
            stream = MAIN_STREAM,
            segment = segment,
            dirSegment = dirSegment,
            state = QueueState.SEALED,
            byteSize = 4,
            sealedAt = 1,
            homeInstanceId = null,
            observerHandle = null,
        )

    private fun fileRow(segmentId: String, sha256: String): SegmentFileRow =
        SegmentFileRow(
            segmentId = segmentId,
            sourceId = "audio",
            name = "audio.bin",
            sha256 = sha256,
            byteSize = 4,
            mediaType = "application/octet-stream",
            captureStartEpochMs = BASE_EPOCH_MS,
            captureEndEpochMs = BASE_EPOCH_MS + 1,
        )

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

        override fun segmentsForDrain(stream: String): List<SegmentRow> =
            segments.filter {
                it.stream == stream &&
                    (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED)
            }

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
        const val DAY = "20261101"
        const val FIRST_START = 1_793_519_100_000L
        const val SECOND_START = 1_793_522_700_000L
        const val WINDOW_MS = 300_000L
    }
}
