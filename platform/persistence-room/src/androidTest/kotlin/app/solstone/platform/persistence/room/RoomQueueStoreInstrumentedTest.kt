// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.queue.QueueEvent
import app.solstone.core.segment.SealedSegment
import app.solstone.core.spool.serializeManifest
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for the durable Room queue store on a real Android runtime.
 */
@RunWith(AndroidJUnit4::class)
class RoomQueueStoreInstrumentedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SolstonePersistenceDatabase
    private lateinit var dao: SegmentDao
    private lateinit var store: RoomQueueStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, SolstonePersistenceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.segmentDao()
        store = RoomQueueStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertSegmentWithFiles_refreshesFilesWithoutReplacingExistingSegmentRow() {
        dao.insertSegmentWithFiles(
            segment("a", QueueState.RECORDING, sealedAt = 100),
            listOf(file("a", "audio", "sha-aud"), file("a", "camera", "sha-cam")),
        )

        assertEquals(1, dao.segmentsByDay(DAY).size)
        assertEquals(1, dao.duplicateBySha256("sha-aud").size)
        assertEquals(1, dao.duplicateBySha256("sha-cam").size)

        // Re-inserting the same segment id preserves the row while refreshing its files.
        dao.insertSegmentWithFiles(
            segment("a", QueueState.SEALED, sealedAt = 100),
            listOf(file("a", "audio", "sha-aud-v2")),
        )

        assertEquals(1, dao.segmentsByDay(DAY).size)
        assertEquals(QueueState.RECORDING, stateOf("a"))
        assertTrue("stale file rows must be cleared", dao.duplicateBySha256("sha-aud").isEmpty())
        assertTrue("stale file rows must be cleared", dao.duplicateBySha256("sha-cam").isEmpty())
        assertEquals(1, dao.duplicateBySha256("sha-aud-v2").size)
    }

    @Test
    fun insertSegmentWithFiles_preservesUploadedRowBookkeepingOnReseal() {
        dao.insertSegmentWithFiles(
            segment("u", QueueState.RECORDING, sealedAt = 100),
            listOf(file("u", "audio", "sha-old")),
        )
        store.advance("u", QueueEvent.SEAL)
        store.advance("u", QueueEvent.START_UPLOAD)
        store.advance("u", QueueEvent.MARK_UPLOADED)
        dao.recordUploaded("u")
        dao.recordAttempt("u", 3, 999)

        dao.insertSegmentWithFiles(
            segment("u", QueueState.SEALED, sealedAt = 200),
            listOf(file("u", "audio", "sha-new")),
        )

        val row = dao.segmentById("u")!!
        assertEquals(QueueState.UPLOADED, row.state)
        assertEquals(3, row.attemptCount)
        assertNull(row.lastError)
        assertTrue("stale file rows must be cleared", dao.duplicateBySha256("sha-old").isEmpty())
        assertEquals(1, dao.duplicateBySha256("sha-new").size)
    }

    @Test
    fun insertSegmentWithFilesPersistsRowsSharingWireKeyWhenDirSegmentDiffers() {
        val wireKey = "011500_300"
        val bare = segment("$DAY/validation.watch/$wireKey", QueueState.SEALED, sealedAt = 100)
            .copy(segment = wireKey, dirSegment = wireKey)
        val suffixedLeaf = "${wireKey}__ws1793519100000"
        val suffixed = segment("$DAY/validation.watch/$suffixedLeaf", QueueState.SEALED, sealedAt = 200)
            .copy(segment = wireKey, dirSegment = suffixedLeaf)

        dao.insertSegmentWithFiles(bare, listOf(file(bare.id, "audio", "sha-bare")))
        dao.insertSegmentWithFiles(suffixed, listOf(file(suffixed.id, "audio", "sha-suffixed")))

        val rows = dao.segmentsByDay(DAY)
        assertEquals(listOf(bare.id, suffixed.id), rows.map { it.id })
        assertEquals(listOf(wireKey, wireKey), rows.map { it.segment })
        assertEquals(listOf(wireKey, suffixedLeaf), rows.map { it.dirSegment })
        assertEquals(1, dao.duplicateBySha256("sha-bare").size)
        assertEquals(1, dao.duplicateBySha256("sha-suffixed").size)
    }

    @Test
    fun segmentsForDrain_returnsMainDrainableRowsOldestFirst() {
        insert("uploaded", QueueState.UPLOADED, sealedAt = 100)
        insert("sealed", QueueState.SEALED, sealedAt = 200)
        insert("uploading", QueueState.UPLOADING, sealedAt = 300)
        insert("failed", QueueState.FAILED, sealedAt = 400)
        insert("recording", QueueState.RECORDING, sealedAt = 500)
        dao.insertSegmentWithFiles(
            segment("other", QueueState.SEALED, sealedAt = 50).copy(stream = "other.stream"),
            listOf(file("other", "audio", "sha-other")),
        )

        assertEquals(
            listOf("sealed", "uploading", "failed"),
            dao.segmentsForDrain("validation.watch").map { it.id },
        )
    }

    @Test
    fun advanceState_followsLegalLifecycleAndRejectsIllegalTransitions() {
        dao.insertSegmentWithFiles(segment("up", QueueState.RECORDING, sealedAt = 100), emptyList())

        assertEquals(QueueState.SEALED, store.advance("up", QueueEvent.SEAL))
        assertEquals(QueueState.UPLOADING, store.advance("up", QueueEvent.START_UPLOAD))
        assertEquals(QueueState.UPLOADED, store.advance("up", QueueEvent.MARK_UPLOADED))
        assertEquals(QueueState.EVICTED, store.advance("up", QueueEvent.FINISH))
        assertEquals(QueueState.EVICTED, store.advance("up", QueueEvent.FINISH))
        assertEquals(QueueState.EVICTED, stateOf("up"))

        // Illegal transition throws and leaves the persisted state untouched.
        dao.insertSegmentWithFiles(segment("rec", QueueState.RECORDING, sealedAt = 200), emptyList())
        assertThrows(IllegalStateException::class.java) {
            store.advance("rec", QueueEvent.MARK_UPLOADED)
        }
        assertThrows(IllegalStateException::class.java) {
            store.advance("rec", QueueEvent.FINISH)
        }
        assertEquals(QueueState.RECORDING, stateOf("rec"))

        // Advancing a missing segment is a hard error, not a silent no-op.
        assertThrows(NoSuchElementException::class.java) {
            store.advance("ghost", QueueEvent.SEAL)
        }
    }

    @Test
    fun deleteSource_removesOnlyTargetSourceFilesAndKeepsSegment() {
        // One 5-minute segment carrying files from two distinct sources.
        dao.insertSegmentWithFiles(
            segment("m", QueueState.SEALED, sealedAt = 100),
            listOf(
                file("m", "audio", "sha-audio", name = "audio.m4a"),
                file("m", "camera", "sha-camera", name = "camera.jpg"),
            ),
        )

        val result = store.deleteSource("camera")

        assertEquals("camera", result.sourceId)
        assertEquals(1, result.deletedFileRows)
        assertTrue("camera files removed", dao.duplicateBySha256("sha-camera").isEmpty())
        assertEquals("audio files survive", 1, dao.duplicateBySha256("sha-audio").size)
        // The segment row itself survives a per-source delete.
        assertEquals(1, dao.segmentsByDay(DAY).filter { it.id == "m" }.size)
    }

    @Test
    fun fileBackedDatabase_survivesCloseAndReopen() {
        val name = "reboot-survival-queue.db"
        deleteDatabaseFiles(name)
        try {
            val first = openFileBacked(name)
            first.segmentDao().insertSegmentWithFiles(
                segment("r", QueueState.RECORDING, sealedAt = 100),
                listOf(file("r", "audio", "sha-reboot")),
            )
            RoomQueueStore(first).advance("r", QueueEvent.SEAL)
            first.close() // simulate process death / device reboot

            val second = openFileBacked(name)
            val rows = second.segmentDao().segmentsByDay(DAY)
            assertEquals(1, rows.size)
            assertEquals(QueueState.SEALED, rows.single().state)
            assertEquals(1, second.segmentDao().duplicateBySha256("sha-reboot").size)
            assertNull("no draft leakage", rows.single().observerHandle)
            second.close()
        } finally {
            deleteDatabaseFiles(name)
        }
    }

    @Test
    fun fileBackedReopenAndFinishPassCleansEvictedAndDoesNotResurrect() {
        val dbName = "finish-pass-reopen.db"
        deleteDatabaseFiles(dbName)
        val spoolDir = File(ctx.filesDir, "test-spool-reopen").toPath()
        if (Files.exists(spoolDir)) {
            spoolDir.toFile().deleteRecursively()
        }
        Files.createDirectories(spoolDir)

        try {
            val db1 = openFileBacked(dbName)
            val dao1 = db1.segmentDao()

            val seg1Dir = spoolDir.resolve("$DAY/validation.watch/e1")
            Files.createDirectories(seg1Dir)
            Files.write(seg1Dir.resolve("payload.bin"), "payload1".toByteArray())
            val sealed1 = SealedSegment("validation.watch", SegmentKey(DAY, "e1"), WireKeys(DAY, "e1", 1000L, 2000L, "UTC", 0), emptyList(), emptyList())
            val manifest1 = serializeManifest(sealed1, BundleManifest(sealed1.key, listOf(BundleFile("s", "payload.bin", "sha1", 8, "application/octet-stream", 1000L, 2000L)), emptyList()))
            Files.write(seg1Dir.resolve("manifest"), manifest1.toByteArray())

            val seg2Dir = spoolDir.resolve("$DAY/validation.watch/e2")
            Files.createDirectories(seg2Dir)
            Files.write(seg2Dir.resolve("payload.bin"), "payload2".toByteArray())
            // No manifest in seg2Dir

            dao1.insertSegmentWithFiles(
                SegmentRow(
                    id = "$DAY/validation.watch/e1",
                    day = DAY,
                    stream = "validation.watch",
                    segment = "e1",
                    dirSegment = "e1",
                    state = QueueState.EVICTED,
                    byteSize = SEGMENT_BYTES,
                    sealedAt = 100,
                    homeInstanceId = null,
                    observerHandle = null,
                ),
                emptyList(),
            )
            dao1.insertSegmentWithFiles(
                SegmentRow(
                    id = "$DAY/validation.watch/e2",
                    day = DAY,
                    stream = "validation.watch",
                    segment = "e2",
                    dirSegment = "e2",
                    state = QueueState.EVICTED,
                    byteSize = SEGMENT_BYTES,
                    sealedAt = 200,
                    homeInstanceId = null,
                    observerHandle = null,
                ),
                emptyList(),
            )

            db1.close()

            val db2 = openFileBacked(dbName)
            val dao2 = db2.segmentDao()

            val finisher = ConfirmedCopyFinisher(spoolDir, dao2)
            finisher.finishPass()

            assertFalse(Files.exists(seg1Dir))
            assertFalse(Files.exists(seg2Dir))

            val reconciler = SpoolRoomReconciler(spoolDir, dao2)
            val reconciled = reconciler.reconcile()
            assertEquals(0, reconciled)

            assertEquals(QueueState.EVICTED, dao2.segmentById("$DAY/validation.watch/e1")!!.state)
            assertEquals(QueueState.EVICTED, dao2.segmentById("$DAY/validation.watch/e2")!!.state)

            db2.close()
        } finally {
            deleteDatabaseFiles(dbName)
            if (Files.exists(spoolDir)) {
                spoolDir.toFile().deleteRecursively()
            }
        }
    }

    // --- helpers ---------------------------------------------------------------

    private fun insert(id: String, state: QueueState, sealedAt: Long) {
        dao.insertSegmentWithFiles(
            segment(id, state, sealedAt),
            listOf(file(id, "audio", "sha-$id")),
        )
    }

    private fun segment(id: String, state: QueueState, sealedAt: Long): SegmentRow =
        SegmentRow(
            id = id,
            day = DAY,
            stream = "validation.watch",
            segment = id,
            dirSegment = id,
            state = state,
            byteSize = SEGMENT_BYTES,
            sealedAt = sealedAt,
            homeInstanceId = null,
            observerHandle = null,
        )

    private fun file(segmentId: String, sourceId: String, sha: String, name: String = "$sourceId.bin"): SegmentFileRow =
        SegmentFileRow(
            segmentId = segmentId,
            sourceId = sourceId,
            name = name,
            sha256 = sha,
            byteSize = SEGMENT_BYTES,
            mediaType = "application/octet-stream",
            captureStartEpochMs = 1_000,
            captureEndEpochMs = 301_000,
        )

    private fun stateOf(id: String): QueueState =
        dao.segmentsByDay(DAY).single { it.id == id }.state

    private fun openFileBacked(name: String): SolstonePersistenceDatabase =
        Room.databaseBuilder(ctx, SolstonePersistenceDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun deleteDatabaseFiles(name: String) {
        listOf(name, "$name-wal", "$name-shm").forEach { ctx.getDatabasePath(it).delete() }
    }

    private companion object {
        const val DAY = "20260617"
        const val SEGMENT_BYTES = 50L
    }
}
