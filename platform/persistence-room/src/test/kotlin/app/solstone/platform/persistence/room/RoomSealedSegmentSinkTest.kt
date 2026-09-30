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

    @Test
    fun threeSealsWithSameWireKeyAndOffsetButDifferentZonesAndDescriptorsCreateThreeLeavesAndRows() {
        val baseDir = Files.createTempDirectory("spool-three-zones")
        try {
            val dao = FakeSegmentDao()
            val sink = RoomSealedSegmentSink(dao)
            val writer = FileSpoolWriter(
                baseDir = baseDir,
                occupiedLeaves = { day, stream -> dao.occupiedDirSegments(day, stream) },
            )
            val wireSegment = "090000_300"
            val startEpoch = 1_793_519_100_000L
            val endEpoch = startEpoch + 300_000L
            val offsetSeconds = 9 * 3600 // +09:00

            val zones = listOf("Asia/Tokyo", "Asia/Seoul", "Pacific/Palau")
            val payloadNames = listOf("tokyo.bin", "seoul.bin", "palau.bin")
            val payloadContents = listOf("tokyo-bytes", "seoul-bytes", "palau-bytes")

            for (i in 0 until 3) {
                val seg = SealedSegment(
                    stream = MAIN_STREAM,
                    key = SegmentKey(DAY, wireSegment),
                    wireKeys = WireKeys(DAY, wireSegment, startEpoch, endEpoch, zones[i], offsetSeconds),
                    payloads = listOf(
                        SegmentPayload("audio", PayloadRef(payloadNames[i], "application/octet-stream", payloadContents[i].length.toLong(), null), startEpoch, endEpoch),
                    ),
                    gaps = emptyList(),
                )
                val prov = object : PayloadBytesProvider {
                    override fun open(payload: SegmentPayload) =
                        ByteArrayInputStream(payloadContents[i].toByteArray())
                }
                val sealResult = writer.seal(seg, prov)
                sink.persistSealed(seg, sealResult, sealedAtEpochMs = (i + 1).toLong() * 1000L)
            }

            val expectedLeaves = listOf(wireSegment, "${wireSegment}__ws$startEpoch", "${wireSegment}__2")
            val actualDirs = expectedLeaves.map { baseDir.resolve(DAY).resolve(MAIN_STREAM).resolve(it) }
            actualDirs.forEach { assertTrue(Files.isDirectory(it), "Expected directory $it") }

            assertEquals(3, dao.segments.size)
            assertEquals(expectedLeaves.map { "$DAY/$MAIN_STREAM/$it" }, dao.segments.map { it.id })
            assertEquals(listOf(wireSegment, wireSegment, wireSegment), dao.segments.map { it.segment })
            assertEquals(expectedLeaves, dao.segments.map { it.dirSegment })

            actualDirs.forEach { dir ->
                val manifestText = String(Files.readAllBytes(dir.resolve("manifest")), Charsets.UTF_8)
                assertTrue(manifestText.contains("segment=$wireSegment\n"))
            }
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun reconcileContinuesAcrossCorruptedManifestAndLogsSkip() {
        val baseDir = Files.createTempDirectory("spool-reconcile-corrupt")
        try {
            val dao = FakeSegmentDao()
            val logs = mutableListOf<String>()
            val writer = FileSpoolWriter(baseDir)

            val seg1 = manualSegment(FIRST_START, FIRST_START + WINDOW_MS, "first.bin")
            val seg2 = manualSegment(SECOND_START, SECOND_START + WINDOW_MS, "second.bin")
            writer.seal(seg1, provider())
            writer.seal(seg2, provider())

            val manifestFiles = java.io.File(baseDir.toString()).walkTopDown()
                .filter { it.isFile && it.name == "manifest" && !it.relativeTo(java.io.File(baseDir.toString())).invariantSeparatorsPath.split('/').contains(".draft") }
                .toList()
            assertTrue(manifestFiles.size >= 2)

            val corruptManifestFile = manifestFiles.first()
            val validManifestFile = manifestFiles.last()
            val originalCorruptBytes = "not a valid manifest header\ncorrupt=true\n".toByteArray(Charsets.UTF_8)
            Files.write(corruptManifestFile.toPath(), originalCorruptBytes)

            val reconciler = SpoolRoomReconciler(baseDir, dao) { logs += it }
            val inserted = reconciler.reconcile()

            val validSegmentDir = validManifestFile.parentFile!!
            val corruptSegmentDir = corruptManifestFile.parentFile!!
            val validStreamDir = validSegmentDir.parentFile!!
            val validDayDir = validStreamDir.parentFile!!
            val corruptStreamDir = corruptSegmentDir.parentFile!!
            val corruptDayDir = corruptStreamDir.parentFile!!

            val validRowId = "${validDayDir.name}/${validStreamDir.name}/${validSegmentDir.name}"
            val corruptRowId = "${corruptDayDir.name}/${corruptStreamDir.name}/${corruptSegmentDir.name}"

            assertEquals(1, inserted)
            assertNotNull(dao.segmentById(validRowId))
            assertEquals(null, dao.segmentById(corruptRowId))
            assertEquals(
                originalCorruptBytes.toList(),
                Files.readAllBytes(corruptManifestFile.toPath()).toList(),
            )
            assertEquals(1, logs.size)
            assertEquals(
                "spool reconcile skipped unreadable manifest day=${corruptDayDir.name} stream=${corruptStreamDir.name} leaf=${corruptSegmentDir.name}",
                logs.single(),
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun sameKeyIdentityAndSourceNamesWithDifferentByteSizeAllocatesNewLeafAndRow() {
        val baseDir = Files.createTempDirectory("spool-byte-size-diff")
        try {
            val dao = FakeSegmentDao()
            val sink = RoomSealedSegmentSink(dao)
            val writer = FileSpoolWriter(
                baseDir = baseDir,
                occupiedLeaves = { day, stream -> dao.occupiedDirSegments(day, stream) },
            )
            val wireSegment = "090000_300"
            val startEpoch = 1_793_519_100_000L
            val endEpoch = startEpoch + 300_000L
            val zoneId = "Asia/Tokyo"
            val offsetSeconds = 9 * 3600

            val seg1 = SealedSegment(
                stream = MAIN_STREAM,
                key = SegmentKey(DAY, wireSegment),
                wireKeys = WireKeys(DAY, wireSegment, startEpoch, endEpoch, zoneId, offsetSeconds),
                payloads = listOf(
                    SegmentPayload("audio", PayloadRef("audio.bin", "application/octet-stream", 4L, null), startEpoch, endEpoch),
                ),
                gaps = emptyList(),
            )
            val prov1 = object : PayloadBytesProvider {
                override fun open(payload: SegmentPayload) = ByteArrayInputStream(ByteArray(4) { 1 })
            }
            val res1 = writer.seal(seg1, prov1)
            sink.persistSealed(seg1, res1, sealedAtEpochMs = 1000L)

            val seg2 = SealedSegment(
                stream = MAIN_STREAM,
                key = SegmentKey(DAY, wireSegment),
                wireKeys = WireKeys(DAY, wireSegment, startEpoch, endEpoch, zoneId, offsetSeconds),
                payloads = listOf(
                    SegmentPayload("audio", PayloadRef("audio.bin", "application/octet-stream", 8L, null), startEpoch, endEpoch),
                ),
                gaps = emptyList(),
            )
            val prov2 = object : PayloadBytesProvider {
                override fun open(payload: SegmentPayload) = ByteArrayInputStream(ByteArray(8) { 2 })
            }
            val res2 = writer.seal(seg2, prov2)
            sink.persistSealed(seg2, res2, sealedAtEpochMs = 2000L)

            assertEquals(2, dao.segments.size)
            val firstRow = dao.segmentById("$DAY/$MAIN_STREAM/$wireSegment")!!
            val secondRow = dao.segmentById("$DAY/$MAIN_STREAM/${wireSegment}__ws$startEpoch")!!
            assertEquals(4L, firstRow.byteSize)
            assertEquals(8L, secondRow.byteSize)

            val firstFiles = dao.filesBySegmentId(firstRow.id)
            assertEquals(1, firstFiles.size)
            assertEquals(4L, firstFiles.single().byteSize)

            val secondFiles = dao.filesBySegmentId(secondRow.id)
            assertEquals(1, secondFiles.size)
            assertEquals(8L, secondFiles.single().byteSize)
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

        override fun recordUploaded(id: String): Int = 0

        override fun recordFailure(id: String, code: Int?, error: String?): Int = 0

        override fun upsertSyncState(row: SyncStateRow) = Unit

        override fun syncState(): SyncStateRow? = null

        override fun pendingCount(stream: String): Int = 0

        override fun pendingSourceIds(stream: String): List<String> {
            val pendingIds = segments
                .filter {
                    it.stream == stream &&
                        (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED)
                }
                .map { it.id }
                .toSet()
            return files.filter { it.segmentId in pendingIds }.map { it.sourceId }.distinct()
        }

        override fun advanceState(id: String, event: QueueEvent): QueueState =
            segmentById(id)?.state ?: throw NoSuchElementException(id)

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
