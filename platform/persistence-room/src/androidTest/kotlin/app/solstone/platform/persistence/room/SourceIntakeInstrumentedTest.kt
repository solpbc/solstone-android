// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.QueueState
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceIntakeInstrumentedTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SolstonePersistenceDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, SolstonePersistenceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun firstInsertCountsAndAResealDoesNot() {
        val dao = db.segmentDao()
        val now = 5_000_000_000_000L
        dao.insertSegmentWithFiles(segment("a"), listOf(audio("a", end = 90_000L)), emptyList(), now)
        dao.insertSegmentWithFiles(segment("a"), listOf(audio("a", end = 50_000L)), emptyList(), now + 10_000L)

        val row = dao.intakeBySource(INTAKE_AUDIO)
        assertEquals(90L, row?.total)
        assertEquals(now, row?.startedAt)
        assertEquals(90_000L, row?.lastAt)
    }

    @Test
    fun aLaterOlderSegmentDoesNotMoveStartedOrLast() {
        val dao = db.segmentDao()
        dao.insertSegmentWithFiles(segment("new"), listOf(audio("new", end = 10_000L)), emptyList(), now = 9_000L)
        dao.insertSegmentWithFiles(segment("old"), listOf(audio("old", end = 4_000L)), emptyList(), now = 20_000L)

        val row = dao.intakeBySource(INTAKE_AUDIO)
        assertEquals(14L, row?.total)
        assertEquals(9_000L, row?.startedAt)
        assertEquals(10_000L, row?.lastAt)
    }

    @Test
    fun aMixedSegmentCountsAudioCameraAndLocationSeparately() {
        val dao = db.segmentDao()
        val north = latitudeDegrees(1_000.0)
        val files = listOf(
            audio("mix", end = 5_000L),
            photo("mix", "camera-1.jpg"),
            photo("mix", "camera-2.jpg"),
            photo("mix", "camera-3.jpg"),
            photo("mix", "camera-empty.jpg", bytes = 0L),
            file("mix", "calendar", "calendar_1.jsonl", end = 60_000L, bytes = 30L),
        )
        val fixes = listOf(fix(at = 1_000L, lat = 0.0, accuracy = 5.0), fix(at = 2_000L, lat = north, accuracy = 5.0))
        dao.insertSegmentWithFiles(segment("mix"), files, fixes, nowEpochMs = 8_000L)

        assertEquals(5L, dao.intakeBySource(INTAKE_AUDIO)?.total)
        assertEquals(3L, dao.intakeBySource(INTAKE_CAMERA)?.total)
        val location = dao.intakeBySource(INTAKE_LOCATION)
        assertNear(1_000.0, location?.total)
        assertEquals(8_000L, location?.startedAt)
        assertEquals(2_000L, location?.lastAt)
        assertNull(dao.intakeBySource(INTAKE_CALENDAR))
        assertEquals(2_000L, dao.intakeFix(INTAKE_LOCATION)?.fixAt)
    }

    @Test
    fun aFixInsideItsAccuracyOpensLocationWithoutAddingDistance() {
        val dao = db.segmentDao()
        val fixes = listOf(fix(at = 100L, lat = 10.0, accuracy = 50.0))
        dao.insertSegmentWithFiles(segment("still"), emptyList(), fixes, nowEpochMs = 4_000L)
        val row = dao.intakeBySource(INTAKE_LOCATION)
        assertEquals(0L, row?.total)
        assertEquals(4_000L, row?.startedAt)
        assertEquals(100L, row?.lastAt)
    }

    @Test
    fun aStepEqualToEitherAccuracyAddsNothing() {
        val dao = db.segmentDao()
        val north = latitudeDegrees(1_000.0)
        val metres = haversineMetres(0.0, 0.0, north, 0.0)
        val fixes = listOf(fix(at = 1L, lat = 0.0, accuracy = metres), fix(at = 2L, lat = north, accuracy = 1.0))
        dao.insertSegmentWithFiles(segment("eq"), emptyList(), fixes, nowEpochMs = 3_000L)
        assertEquals(0L, dao.intakeBySource(INTAKE_LOCATION)?.total)
        assertEquals(2L, dao.intakeFix(INTAKE_LOCATION)?.fixAt)
    }

    @Test
    fun calendarCountsAnAddedEventOnceAcrossARewrite() {
        val dao = db.calendarDao()
        val first = intakeSeenKey("event-1", null)
        val second = intakeSeenKey("event-2", "occ")
        assertTrue(dao.stage(emptyList(), emptyList(), null, null, { true }, listOf(first), 1_700_000_000_000L))
        assertTrue(dao.stage(emptyList(), emptyList(), null, null, { true }, listOf(first, second), 1_700_000_100_000L))
        assertTrue(dao.stage(emptyList(), emptyList(), null, null, { true }, listOf(first, second), 1_700_000_200_000L))
        assertTrue(dao.stage(emptyList(), emptyList(), null, null, { false }, listOf(intakeSeenKey("event-3", null)), 1_700_000_300_000L).not())

        val row = db.segmentDao().intakeBySource(INTAKE_CALENDAR)
        assertEquals(2L, row?.total)
        assertEquals(1_700_000_000_000L, row?.startedAt)
        assertEquals(1_700_000_100_000L, row?.lastAt)
    }

    @Test
    fun importStaysCountedAfterTheDeliveredRowIsDeleted() {
        val dao = db.importDao()
        val row = importRow("one", createdAt = 50L)
        assertTrue(dao.insertIfNew(row, nowEpochMs = 80L))
        assertTrue(dao.insertIfNew(row, nowEpochMs = 90L).not())
        assertEquals(1, dao.delete("one"))
        assertNull(dao.byId("one"))
        val kept = db.segmentDao().intakeBySource(INTAKE_IMPORT)
        assertEquals(1L, kept?.total)
        assertEquals(80L, kept?.startedAt)
        assertEquals(50L, kept?.lastAt)
        assertTrue(dao.insertIfNew(importRow("two", createdAt = 60L), nowEpochMs = 100L))
        assertEquals(2L, db.segmentDao().intakeBySource(INTAKE_IMPORT)?.total)
        assertEquals(80L, db.segmentDao().intakeBySource(INTAKE_IMPORT)?.startedAt)
    }

    private fun segment(id: String) = SegmentRow(
        id = id,
        day = "20261109",
        stream = "main",
        segment = id,
        dirSegment = id,
        state = QueueState.SEALED,
        byteSize = 10L,
        sealedAt = 1L,
        homeInstanceId = null,
        observerHandle = null,
    )

    private fun audio(id: String, end: Long) = file(id, "audio", "audio.m4a", end, 10L)

    private fun photo(id: String, name: String, bytes: Long = 10L) = file(id, "camera", name, end = 1_000L, bytes = bytes)

    private fun file(id: String, sourceId: String, name: String, end: Long, bytes: Long) = SegmentFileRow(
        segmentId = id,
        sourceId = sourceId,
        name = name,
        sha256 = name,
        byteSize = bytes,
        mediaType = "application/octet-stream",
        captureStartEpochMs = 0L,
        captureEndEpochMs = end,
    )

    private fun fix(at: Long, lat: Double, accuracy: Double) =
        IntakeFix(atEpochMs = at, lat = lat, lon = 0.0, accuracyMeters = accuracy)

    private fun importRow(id: String, createdAt: Long) = ImportItemRow(
        id = id,
        payloadKind = "file",
        displayName = id,
        mime = "text/plain",
        byteSize = 4L,
        state = "pending",
        createdAt = createdAt,
        progressPath = null,
        progressTimestamp = null,
        progressInstanceId = null,
        backoffInstanceId = null,
        attemptCount = 0,
        nextAttemptAt = null,
        attentionError = null,
        attentionReasonCode = null,
        attentionKind = null,
        attentionInstanceId = null,
        attentionJournalVersion = null,
    )

    private fun latitudeDegrees(metres: Double): Double = metres / (6_371_000.0 * Math.PI / 180.0)

    private fun assertNear(metres: Double, millimetres: Long?) {
        assertTrue(millimetres != null)
        assertTrue(abs(millimetres - metres * 1_000.0) <= 1_000.0)
    }
}
