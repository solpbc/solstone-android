// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import app.solstone.core.model.QueueState
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import org.junit.Assert.assertTrue
import java.io.File

fun seedWatchSegments(
    db: SolstonePersistenceDatabase,
    spoolDir: File,
    stream: String,
    homeInstanceId: String,
) {
    val day1 = "2026-03-01"
    val segmentId1 = "$day1/$stream/seg-1"
    val segDir1 = File(File(File(spoolDir, day1), stream), "seg-1").apply { mkdirs() }
    File(segDir1, "a.bin").writeBytes(byteArrayOf(1, 2, 3))
    val manifest1 = """
        solstone-bundle-manifest-v1
        day=$day1
        segment=seg-1
        startEpochMs=1
        endEpochMs=2
        zoneId=UTC
        utcOffsetSeconds=0
        [files]
        source1	a.bin	sha-a	3	application/octet-stream	1	2
        [gaps]
    """.trimIndent()
    File(segDir1, "manifest").writeText(manifest1)

    val day2 = "2026-03-02"
    val segmentId2 = "$day2/$stream/seg-2"
    val segDir2 = File(File(File(spoolDir, day2), stream), "seg-2").apply { mkdirs() }
    File(segDir2, "b.bin").writeBytes(byteArrayOf(4, 5, 6))
    val manifest2 = """
        solstone-bundle-manifest-v1
        day=$day2
        segment=seg-2
        startEpochMs=10
        endEpochMs=20
        zoneId=UTC
        utcOffsetSeconds=0
        [files]
        source2	b.bin	sha-b	3	application/octet-stream	10	20
        [gaps]
    """.trimIndent()
    File(segDir2, "manifest").writeText(manifest2)

    val segRow1 = SegmentRow(
        id = segmentId1,
        day = day1,
        stream = stream,
        segment = "seg-1",
        dirSegment = "seg-1",
        state = QueueState.SEALED,
        byteSize = 3L,
        sealedAt = 2L,
        homeInstanceId = homeInstanceId,
        observerHandle = "obs",
        attemptCount = 0,
        lastStatusCode = null,
        lastAttemptAt = null,
        lastError = null,
    )
    val fileRow1 = SegmentFileRow(
        rowId = 0,
        segmentId = segmentId1,
        sourceId = "source1",
        name = "a.bin",
        sha256 = "sha-a",
        byteSize = 3L,
        mediaType = "application/octet-stream",
        captureStartEpochMs = 1L,
        captureEndEpochMs = 2L,
    )
    val segRow2 = SegmentRow(
        id = segmentId2,
        day = day2,
        stream = stream,
        segment = "seg-2",
        dirSegment = "seg-2",
        state = QueueState.SEALED,
        byteSize = 3L,
        sealedAt = 20L,
        homeInstanceId = homeInstanceId,
        observerHandle = "obs",
        attemptCount = 0,
        lastStatusCode = null,
        lastAttemptAt = null,
        lastError = null,
    )
    val fileRow2 = SegmentFileRow(
        rowId = 0,
        segmentId = segmentId2,
        sourceId = "source2",
        name = "b.bin",
        sha256 = "sha-b",
        byteSize = 3L,
        mediaType = "application/octet-stream",
        captureStartEpochMs = 10L,
        captureEndEpochMs = 20L,
    )

    db.segmentDao().insertSegmentWithFiles(segRow1, listOf(fileRow1))
    db.segmentDao().insertSegmentWithFiles(segRow2, listOf(fileRow2))
}

fun waitForWatchSegmentsEvicted(db: SolstonePersistenceDatabase, timeoutMs: Long = 10_000L) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val evicted = db.segmentDao().segmentsByState(QueueState.EVICTED)
        if (evicted.size >= 2) {
            return
        }
        Thread.sleep(50)
    }
    val evicted = db.segmentDao().segmentsByState(QueueState.EVICTED)
    val all = db.segmentDao().segmentsForDrain("watch")
    assertTrue("Segments not evicted within timeout. Evicted: ${evicted.size}, remaining: ${all.map { it.state }}", evicted.size >= 2)
}
