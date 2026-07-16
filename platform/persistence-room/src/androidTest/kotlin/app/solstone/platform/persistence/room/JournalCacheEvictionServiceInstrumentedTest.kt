// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.spool.serializeManifest
import app.solstone.core.queue.QueueEvent
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalCacheEvictionServiceInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: SolstonePersistenceDatabase
    private lateinit var spool: Path

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, SolstonePersistenceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        spool = Files.createTempDirectory(context.cacheDir.toPath(), "journal-cache-eviction")
    }

    @After
    fun tearDown() {
        database.close()
        spool.toFile().deleteRecursively()
    }

    @Test
    fun mixedStatesRemoveOnlyUploadedAndLeaveIneligibleDirectories() {
        val uploadedOld = insert("uploaded-old", QueueState.UPLOADED, 1)
        val sealed = insert("sealed", QueueState.SEALED, 2)
        val failed = insert("failed", QueueState.FAILED, 3)
        writeSegment(uploadedOld)
        writeSegment(sealed)
        writeSegment(failed)

        val result = service(SpoolFreeSpaceProvider { 0L }).runPass(10)

        assertEquals(listOf(uploadedOld.id), result.durablyMarkedIds)
        assertFalse(Files.exists(path(uploadedOld)))
        assertTrue(Files.exists(path(sealed)))
        assertTrue(Files.exists(path(failed)))
        assertEquals(QueueState.EVICTED, database.segmentDao().segmentById(uploadedOld.id)!!.state)
        assertEquals(QueueState.SEALED, database.segmentDao().segmentById(sealed.id)!!.state)
        assertTrue(result.pressureRemains)
    }

    @Test
    fun lowFreeSpacePressureUsesRoutineOldestFirstOrdering() {
        val older = insert("older", QueueState.UPLOADED, 1)
        val newer = insert("newer", QueueState.UPLOADED, 2)
        writeSegment(older)
        writeSegment(newer)

        val result = service(SpoolFreeSpaceProvider { 0L }).runPass(10)

        assertEquals(listOf(older.id, newer.id), result.durablyMarkedIds)
        assertFalse(Files.exists(path(older)))
        assertFalse(Files.exists(path(newer)))
    }

    @Test
    fun scaledLimitPressureRemovesOldestUploadedUntilRemeasurementIsWithinBudget() {
        val oldest = insert("oldest", QueueState.UPLOADED, 1)
        val middle = insert("middle", QueueState.UPLOADED, 2)
        val newest = insert("newest", QueueState.UPLOADED, 3)
        listOf(oldest, middle, newest).forEach { writeSegment(it, payloadBytes = 400) }
        val limits = limitStore()
        assertTrue(limits.save(1_000_000_000L) is JournalCacheLimitSaveResult.Saved)

        val result = JournalCacheEvictionService(
            spool,
            database.segmentDao(),
            limits,
            UniformScalingMeasurer(1_000_000L),
            SpoolFreeSpaceProvider { Long.MAX_VALUE / 2 },
            NioSpoolDirectoryRemover(),
        ).runPass(10)

        assertEquals(listOf(oldest.id, middle.id), result.durablyMarkedIds)
        assertFalse(Files.exists(path(oldest)))
        assertFalse(Files.exists(path(middle)))
        assertTrue("newest directory proves measured stop", Files.exists(path(newest)))
        assertFalse(result.pressureRemains)
        assertTrue(requireNotNull(result.measuredUsageBytes) <= 1_000_000_000L)
    }

    @Test
    fun noCandidatePressureNeverEscalatesBeyondUploaded() {
        val sealed = insert("sealed-only", QueueState.SEALED, 1)
        val failed = insert("failed-only", QueueState.FAILED, 2)
        writeSegment(sealed)
        writeSegment(failed)

        val result = service(SpoolFreeSpaceProvider { 0L }).runPass(10)

        assertTrue(result.durablyMarkedIds.isEmpty())
        assertEquals(JournalCacheBlockedReason.NO_SAFE_ELIGIBLE_SEGMENT, result.blockedReason)
        assertTrue(Files.exists(path(sealed)))
        assertTrue(Files.exists(path(failed)))
        assertEquals(QueueState.SEALED, database.segmentDao().segmentById(sealed.id)!!.state)
        assertEquals(QueueState.FAILED, database.segmentDao().segmentById(failed.id)!!.state)
    }

    @Test
    fun processedPromotedSegmentUsesOrdinaryUploadedPath() {
        val processed = insert("processed", QueueState.SEALED, 1)
        writeSegment(processed)
        database.segmentDao().advanceState(processed.id, QueueEvent.START_UPLOAD)
        database.segmentDao().advanceState(processed.id, QueueEvent.MARK_UPLOADED)

        val result = service(SpoolFreeSpaceProvider { 0L }).runPass(10)

        assertEquals(listOf(processed.id), result.durablyMarkedIds)
        assertFalse(Files.exists(path(processed)))
        assertEquals(QueueState.EVICTED, database.segmentDao().segmentById(processed.id)!!.state)
    }

    @Test
    fun eventInsertFailureRollsBackBatchBeforePhysicalDeletion() {
        val uploaded = insert("uploaded", QueueState.UPLOADED, 1)
        writeSegment(uploaded)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_eviction_event BEFORE INSERT ON event BEGIN SELECT RAISE(ABORT, 'injected'); END",
        )

        val result = service(SpoolFreeSpaceProvider { 0L }).runPass(10)

        assertEquals(JournalCacheBlockedReason.TRANSITION_FAILED, result.blockedReason)
        assertEquals(QueueState.UPLOADED, database.segmentDao().segmentById(uploaded.id)!!.state)
        assertTrue(Files.exists(path(uploaded)))
        assertTrue(result.durablyMarkedIds.isEmpty())
    }

    @Test
    fun newServiceInstanceCompletesDurableResidualWithoutManifest() {
        val uploaded = insert("uploaded", QueueState.UPLOADED, 1)
        writeSegment(uploaded)
        val first = JournalCacheEvictionService(
            spool,
            database.segmentDao(),
            limitStore(),
            NioSpoolUsageMeasurer(),
            SpoolFreeSpaceProvider { 0L },
            SpoolDirectoryRemover { directory ->
                Files.deleteIfExists(directory.resolve("manifest"))
                DirectoryRemovalResult.Incomplete
            },
        ).runPass(10)
        assertEquals(listOf(uploaded.id), first.durablyMarkedIds)
        assertEquals(0L, first.reclaimedSpace.totalBytes)
        assertTrue(Files.exists(path(uploaded)))

        val second = service(SpoolFreeSpaceProvider { Long.MAX_VALUE }).runPass(11)
        assertFalse(Files.exists(path(uploaded)))
        assertTrue(second.reclaimedSpace.removals.any { it.segmentId == uploaded.id })
        assertEquals(QueueState.EVICTED, database.segmentDao().segmentById(uploaded.id)!!.state)
        assertTrue(database.segmentDao().segmentsForDrain(STREAM).none { it.id == uploaded.id })
        assertTrue(database.segmentDao().segmentsByState(QueueState.SEALED).none { it.id == uploaded.id })
    }

    @Test
    fun fileBackedReopenAndReconcileCannotResurrectSuccessfulEviction() {
        database.close()
        val name = "journal-cache-eviction-reopen.db"
        deleteDatabaseFiles(name)
        database = Room.databaseBuilder(context, SolstonePersistenceDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        val uploaded = insert("reopen", QueueState.UPLOADED, 1)
        writeSegment(uploaded)

        service(SpoolFreeSpaceProvider { 0L }).runPass(10)
        database.close()
        database = Room.databaseBuilder(context, SolstonePersistenceDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

        assertEquals(0, SpoolRoomReconciler(spool, database.segmentDao()).reconcile())
        assertEquals(QueueState.EVICTED, database.segmentDao().segmentById(uploaded.id)!!.state)
        assertTrue(database.segmentDao().filesBySegmentId(uploaded.id).isEmpty())
        assertFalse(Files.exists(path(uploaded)))
        assertTrue(database.segmentDao().segmentsForDrain(STREAM).none { it.id == uploaded.id })
        assertTrue(database.segmentDao().segmentsByState(QueueState.SEALED).none { it.id == uploaded.id })
        database.close()
        deleteDatabaseFiles(name)
        database = Room.inMemoryDatabaseBuilder(context, SolstonePersistenceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun service(free: SpoolFreeSpaceProvider) = JournalCacheEvictionService(
        spool, database.segmentDao(), limitStore(), NioSpoolUsageMeasurer(), free, NioSpoolDirectoryRemover(),
    )

    private fun limitStore() = JournalCacheLimitStore(spool.parent.resolve("journal-cache-limit-${System.nanoTime()}").toFile())

    private fun insert(leaf: String, state: QueueState, sealedAt: Long): SegmentRow {
        val row = SegmentRow(
            id = "$DAY/$STREAM/$leaf", day = DAY, stream = STREAM, segment = leaf, dirSegment = leaf,
            state = state, byteSize = 999, sealedAt = sealedAt, homeInstanceId = null, observerHandle = null,
        )
        database.segmentDao().insertSegmentWithFiles(
            row,
            listOf(
                SegmentFileRow(
                    segmentId = row.id, sourceId = "audio", name = "payload", sha256 = "sha-$leaf", byteSize = 10,
                    mediaType = "application/octet-stream", captureStartEpochMs = 1, captureEndEpochMs = 2,
                ),
            ),
        )
        return row
    }

    private fun writeSegment(row: SegmentRow, payloadBytes: Int = 10) {
        Files.createDirectories(path(row))
        Files.write(path(row).resolve("payload"), ByteArray(payloadBytes))
        val sealed = SealedSegment(row.stream, SegmentKey(row.day, row.segment), WireKeys(row.day, row.segment, 1, 2, "UTC", 0), emptyList(), emptyList())
        Files.write(path(row).resolve("manifest"), serializeManifest(sealed, BundleManifest(sealed.key, emptyList(), emptyList())).toByteArray())
    }

    private fun path(row: SegmentRow) = spool.resolve(row.day).resolve(row.stream).resolve(row.dirSegment)

    private fun deleteDatabaseFiles(name: String) {
        listOf(name, "$name-wal", "$name-shm").forEach { context.getDatabasePath(it).delete() }
    }

    private class UniformScalingMeasurer(private val scale: Long) : SpoolUsageMeasurer {
        private val delegate = NioSpoolUsageMeasurer()

        override fun measure(spoolRoot: Path): SpoolUsageMeasurement {
            // D2 is linear: scaling M and every d_i equally preserves trigger and projected-stop behavior.
            val measured = delegate.measure(spoolRoot)
            return SpoolUsageMeasurement(
                totalBytes = Math.multiplyExact(measured.totalBytes, scale),
                sealedDirectoryBytes = measured.sealedDirectoryBytes.mapValues { (_, bytes) -> Math.multiplyExact(bytes, scale) },
            )
        }
    }

    private companion object {
        const val DAY = "20260716"
        const val STREAM = "audio"
    }
}
