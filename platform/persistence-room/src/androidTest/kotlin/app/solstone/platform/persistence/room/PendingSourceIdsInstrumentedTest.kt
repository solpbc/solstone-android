// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.QueueState
import app.solstone.core.sources.MAIN_STREAM
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingSourceIdsInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: SolstonePersistenceDatabase
    private lateinit var dao: SegmentDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, SolstonePersistenceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.segmentDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pendingSourceIds_returnsDistinctMainPendingSourcesAndLeavesZeroFileSegmentUngrouped() {
        insert("pending-multi", MAIN_STREAM, QueueState.SEALED, listOf("audio", "location"))
        insert("pending-audio", MAIN_STREAM, QueueState.UPLOADING, listOf("audio"))
        insert("pending-camera", MAIN_STREAM, QueueState.FAILED, listOf("camera"))
        insert("pending-empty", MAIN_STREAM, QueueState.SEALED, emptyList())
        insert("other-stream", "other.stream", QueueState.SEALED, listOf("other-only"))
        insert("uploaded", MAIN_STREAM, QueueState.UPLOADED, listOf("uploaded-only"))

        assertEquals(4, dao.pendingCount(MAIN_STREAM))
        assertEquals(setOf("audio", "location", "camera"), dao.pendingSourceIds(MAIN_STREAM).toSet())
    }

    private fun insert(id: String, stream: String, state: QueueState, sourceIds: List<String>) {
        val segment = SegmentRow(
            id = id,
            day = "20260617",
            stream = stream,
            segment = id,
            dirSegment = id,
            state = state,
            byteSize = 5,
            sealedAt = 1,
            homeInstanceId = null,
            observerHandle = null,
        )
        dao.insertSegmentWithFiles(
            segment,
            sourceIds.map { sourceId ->
                SegmentFileRow(
                    segmentId = id,
                    sourceId = sourceId,
                    name = "$sourceId.bin",
                    sha256 = "sha-$id-$sourceId",
                    byteSize = 5,
                    mediaType = "application/octet-stream",
                    captureStartEpochMs = 1,
                    captureEndEpochMs = 2,
                )
            },
        )
    }
}
