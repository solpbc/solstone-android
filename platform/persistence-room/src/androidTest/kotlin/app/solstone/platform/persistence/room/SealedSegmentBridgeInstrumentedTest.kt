// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.SourceKind
import app.solstone.core.queue.QueueEvent
import app.solstone.core.model.QueueState
import app.solstone.core.segment.MonotonicClock
import app.solstone.core.segment.Segmenter
import app.solstone.core.segment.SegmenterAnchor
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SealedSegmentBridgeInstrumentedTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SolstonePersistenceDatabase
    private lateinit var dao: SegmentDao
    private lateinit var baseDir: Path

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, SolstonePersistenceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.segmentDao()
        baseDir = Files.createTempDirectory(ctx.cacheDir.toPath(), "spool-room")
    }

    @After
    fun tearDown() {
        db.close()
        baseDir.deleteRecursively()
    }

    @Test
    fun spoolToRoomRoundTripPersistsSealedSegmentAndFile() {
        val sealed = sealOneFullAudioWindow()
        val sink = RoomSealedSegmentSink(dao)

        sink.persistSealed(sealed.segment, sealed.result, sealedAtEpochMs = 2000L)

        val id = sealed.segment.id()
        val row = dao.segmentById(id)
        assertNotNull(row)
        assertEquals(QueueState.SEALED, row!!.state)
        assertEquals(SEALED_BYTES.size.toLong(), row.byteSize)
        assertEquals(1, dao.duplicateBySha256(sealed.result.manifest.files.single().sha256).size)
    }

    @Test
    fun reconcilerInsertsSealedDirectoryMissingFromRoom() {
        val sealed = sealOneFullAudioWindow()
        val id = sealed.segment.id()
        assertNull(dao.segmentById(id))

        val inserted = SpoolRoomReconciler(baseDir, dao).reconcile()

        assertEquals(1, inserted)
        assertEquals(QueueState.SEALED, dao.segmentById(id)!!.state)
        assertEquals(1, dao.duplicateBySha256(sealed.result.manifest.files.single().sha256).size)
    }

    private fun sealOneFullAudioWindow(): SealedResult {
        val clock = MutableClock(0)
        val segmenter = Segmenter(clock, SegmenterAnchor(BASE_EPOCH_MS, 0, ZoneId.of("UTC")))
        val first = audioEmission(BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000)
        segmenter.feed(first)
        clock.nanos = 300_000_000_000L
        val sealed = segmenter.feed(audioEmission(BASE_EPOCH_MS + 300_000, BASE_EPOCH_MS + 600_000)).single()
        val writer = FileSpoolWriter(baseDir)
        val result = writer.seal(
            sealed,
            PayloadBytesProvider { payload: SegmentPayload ->
                require(payload.ref.name == "audio.m4a")
                ByteArrayInputStream(SEALED_BYTES)
            },
        )
        return SealedResult(sealed, result)
    }

    private fun audioEmission(startEpochMs: Long, endEpochMs: Long): SourceEmission =
        SourceEmission(
            sourceId = "audio",
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = startEpochMs,
            captureEndEpochMs = endEpochMs,
            payloadRefs = listOf(PayloadRef("audio.m4a", "audio/mp4", SEALED_BYTES.size.toLong(), null)),
            metadata = emptyMap(),
            gaps = emptyList(),
        )

    private data class SealedResult(
        val segment: app.solstone.core.segment.SealedSegment,
        val result: app.solstone.core.spool.SealResult,
    )

    private class MutableClock(var nanos: Long) : MonotonicClock {
        override fun nanos(): Long = nanos
    }

    private fun Path.deleteRecursively() {
        if (!Files.exists(this)) return
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
        val SEALED_BYTES = byteArrayOf(1, 2, 3, 4)
    }
}
