// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.QueueState
import app.solstone.core.queue.EvictionBudget
import app.solstone.core.queue.EvictionInput
import app.solstone.core.queue.QueueEvent
import app.solstone.core.queue.QueueSegmentDescriptor
import app.solstone.core.queue.evictionPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for the durable Room queue store (F4) on a real Android
 * runtime. Drives [RoomQueueStore] / [SegmentDao] against an actual SQLite DB so
 * the persistence behaviour the spool/sync layers depend on is proven on-device
 * (the JVM tests in :core:queue cover the pure state-machine, not the Room SQL).
 *
 * Run on the headless build box GMD:
 *   ./gradlew -Pandroid.testoptions.manageddevices.emulator.gpu=host \
 *       :platform:persistence-room:pixel5api35DebugAndroidTest
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
        dao.recordUploaded("u", "srv-1")
        dao.recordAttempt("u", 3, 999)

        dao.insertSegmentWithFiles(
            segment("u", QueueState.SEALED, sealedAt = 200),
            listOf(file("u", "audio", "sha-new")),
        )

        val row = dao.segmentById("u")!!
        assertEquals(QueueState.UPLOADED, row.state)
        assertEquals("srv-1", row.serverKey)
        assertEquals(3, row.attemptCount)
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
        assertEquals(QueueState.UPLOADED, stateOf("up"))

        // Illegal transition throws and leaves the persisted state untouched.
        dao.insertSegmentWithFiles(segment("rec", QueueState.RECORDING, sealedAt = 200), emptyList())
        assertThrows(IllegalStateException::class.java) {
            store.advance("rec", QueueEvent.MARK_UPLOADED)
        }
        assertEquals(QueueState.RECORDING, stateOf("rec"))

        // Advancing a missing segment is a hard error, not a silent no-op.
        assertThrows(NoSuchElementException::class.java) {
            store.advance("ghost", QueueEvent.SEAL)
        }
    }

    @Test
    fun applyEvictions_evictsUploadedOldestFirstAndUnsyncedSurvives() {
        // Three UPLOADED (varying seal time) plus one each of SEALED / UPLOADING / FAILED.
        insert("u1", QueueState.UPLOADED, sealedAt = 100)
        insert("u2", QueueState.UPLOADED, sealedAt = 200)
        insert("u3", QueueState.UPLOADED, sealedAt = 300)
        insert("s1", QueueState.SEALED, sealedAt = 150)
        insert("p1", QueueState.UPLOADING, sealedAt = 250)
        insert("f1", QueueState.FAILED, sealedAt = 350)

        // total = 6 * 50 = 300 bytes; cap at 220 forces evicting exactly two UPLOADED.
        val descriptors = listOf("u1", "u2", "u3", "s1", "p1", "f1").map { id ->
            QueueSegmentDescriptor(id, stateOf(id), byteSize = SEGMENT_BYTES, sealedAtEpochMs = sealedOf(id))
        }
        val result = evictionPolicy(
            EvictionInput(
                segments = descriptors,
                budget = EvictionBudget(maxBytes = 220),
                emergency = false,
                decidedAtEpochMs = 999,
            ),
        )

        val applied = store.applyEvictions(result)

        // Oldest-first: u1 (sealedAt 100) then u2 (200); u3 (300) is spared.
        assertEquals(listOf("u1", "u2"), applied.evictedSegmentIds)
        assertEquals(2, applied.deletedFileRows)
        assertEquals(2, applied.eventsInserted)

        assertEquals(QueueState.EVICTED, stateOf("u1"))
        assertEquals(QueueState.EVICTED, stateOf("u2"))
        assertEquals(QueueState.UPLOADED, stateOf("u3"))
        // Unsynced work is never evicted in a non-emergency pass.
        assertEquals(QueueState.SEALED, stateOf("s1"))
        assertEquals(QueueState.UPLOADING, stateOf("p1"))
        assertEquals(QueueState.FAILED, stateOf("f1"))

        // Evicted segments lose their files; everything else keeps them.
        assertTrue(dao.duplicateBySha256("sha-u1").isEmpty())
        assertTrue(dao.duplicateBySha256("sha-u2").isEmpty())
        assertEquals(1, dao.duplicateBySha256("sha-u3").size)
        assertEquals(1, dao.duplicateBySha256("sha-s1").size)
        assertEquals(1, dao.duplicateBySha256("sha-p1").size)
        assertEquals(1, dao.duplicateBySha256("sha-f1").size)
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

    private fun sealedOf(id: String): Long =
        dao.segmentsByDay(DAY).single { it.id == id }.sealedAt

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
