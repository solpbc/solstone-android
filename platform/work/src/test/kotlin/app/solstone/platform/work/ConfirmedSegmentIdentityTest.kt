// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.observer.IngestDescriptors
import app.solstone.core.observer.IngestFileDescriptor
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.EventRow
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SpoolRoomReconciler
import app.solstone.platform.persistence.room.SyncStateRow
import app.solstone.platform.persistence.room.isLeafOccupied
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmedSegmentIdentityTest {

    private class TestEnv {
        val root: Path = Files.createTempDirectory("identity-test")
        val spool: Path = root.resolve("spool")
        val rows = mutableListOf<SegmentRow>()
        val files = mutableMapOf<String, MutableList<SegmentFileRow>>()
        val postedManifests = mutableListOf<BundleManifest>()
        var syncStateRow: SyncStateRow? = null

        val dao = object : SegmentDao() {
            override fun insertSegment(segment: SegmentRow) {
                val idx = rows.indexOfFirst { it.id == segment.id }
                if (idx >= 0) rows[idx] = segment else rows.add(segment)
            }
            override fun insertFiles(newFiles: List<SegmentFileRow>) {
                newFiles.forEach { f ->
                    files.getOrPut(f.segmentId) { mutableListOf() }.add(f)
                }
            }
            override fun insertEvents(events: List<EventRow>) = Unit
            override fun segmentsByState(state: QueueState): List<SegmentRow> =
                rows.filter { it.state == state }
            override fun segmentsForDrain(stream: String): List<SegmentRow> =
                rows.filter { it.stream == stream && (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED) }
                    .sortedWith(compareBy<SegmentRow> { it.sealedAt }.thenBy { it.id })
            override fun segmentsByDay(day: String): List<SegmentRow> =
                rows.filter { it.day == day }
            override fun segmentById(id: String): SegmentRow? =
                rows.firstOrNull { it.id == id }
            override fun duplicateBySha256(sha256: String): List<SegmentFileRow> = emptyList()
            override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> =
                files[segmentId].orEmpty()
            override fun recordAttempt(id: String, attempts: Int, at: Long): Int {
                val idx = rows.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    rows[idx] = rows[idx].copy(attemptCount = attempts, lastAttemptAt = at)
                    return 1
                }
                return 0
            }
            override fun recordUploaded(id: String): Int {
                val idx = rows.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    rows[idx] = rows[idx].copy(lastError = null)
                    return 1
                }
                return 0
            }
            override fun recordFailure(id: String, code: Int?, error: String?): Int {
                val idx = rows.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    rows[idx] = rows[idx].copy(lastStatusCode = code, lastError = error)
                    return 1
                }
                return 0
            }
            override fun upsertSyncState(row: SyncStateRow) {
                syncStateRow = row
            }
            override fun syncState(): SyncStateRow? = syncStateRow
            override fun pendingCount(stream: String): Int =
                rows.count {
                    it.stream == stream &&
                        (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED) &&
                        it.lastError != "removed_in_journal"
                }
            override fun pendingSourceIds(stream: String): List<String> = emptyList()
            override fun segmentState(id: String): QueueState? =
                rows.firstOrNull { it.id == id }?.state
            override fun updateState(id: String, state: QueueState): Int {
                val idx = rows.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    rows[idx] = rows[idx].copy(state = state)
                    return 1
                }
                return 0
            }
            override fun deleteFilesBySegmentId(segmentId: String): Int {
                files.remove(segmentId)
                return 1
            }
            override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int {
                segmentIds.forEach { files.remove(it) }
                return segmentIds.size
            }
            override fun deleteFilesBySource(sourceId: String): Int = 0
        }

        val store = RoomDrainStore(dao)
        val finisher = ConfirmedCopyFinisher(spoolRoot = spool, dao = dao)

        // The spool writer and sink the app containers build, with the same leaf-occupied check.
        val spoolWriter = FileSpoolWriter(
            baseDir = spool,
            isLeafOccupied = { day, stream, leaf -> dao.isLeafOccupied(day, stream, leaf) },
        )
        val sealedSink = RoomSealedSegmentSink(dao)

        /** Seals through the real writer; persists the row unless [persistRow] is false. Returns the final directory. */
        fun seal(
            day: String,
            stream: String,
            wireKey: String,
            startEpochMs: Long,
            endEpochMs: Long,
            payloadName: String,
            sealedAt: Long,
            persistRow: Boolean = true,
        ): Path {
            val segment = SealedSegment(
                stream = stream,
                key = SegmentKey(day, wireKey),
                wireKeys = WireKeys(day, wireKey, startEpochMs, endEpochMs, "America/New_York", -14400),
                payloads = listOf(
                    SegmentPayload("audio", PayloadRef(payloadName, "application/octet-stream", 3, null), startEpochMs, endEpochMs),
                ),
                gaps = emptyList(),
            )
            val result = spoolWriter.seal(
                segment,
                object : PayloadBytesProvider {
                    override fun open(payload: SegmentPayload): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
                },
            )
            if (persistRow) sealedSink.persistSealed(segment, result, sealedAt)
            return requireNotNull(result.directory)
        }

        fun writeSegmentToDisk(
            day: String,
            stream: String,
            wireKey: String,
            dirLeaf: String,
            startEpochMs: Long,
            endEpochMs: Long,
            filesList: List<BundleFile>,
        ): Path {
            val segmentDir = spool.resolve(day).resolve(stream).resolve(dirLeaf)
            Files.createDirectories(segmentDir)
            filesList.forEach { f ->
                Files.write(segmentDir.resolve(f.name), byteArrayOf(1, 2, 3))
            }
            val manifestText = buildString {
                appendLine("solstone-bundle-manifest-v1")
                appendLine("day=$day")
                appendLine("segment=$wireKey")
                appendLine("startEpochMs=$startEpochMs")
                appendLine("endEpochMs=$endEpochMs")
                appendLine("zoneId=America/New_York")
                appendLine("utcOffsetSeconds=-14400")
                appendLine("[files]")
                filesList.forEach { f ->
                    appendLine("${f.sourceId}\t${f.name}\t${f.sha256}\t${f.byteSize}\t${f.mediaType}\t${f.captureStartEpochMs}\t${f.captureEndEpochMs}")
                }
                appendLine("[gaps]")
            }
            Files.write(segmentDir.resolve("manifest"), manifestText.toByteArray())
            return segmentDir
        }

        fun insertRow(
            day: String,
            stream: String,
            wireKey: String,
            dirLeaf: String,
            state: QueueState,
            sealedAt: Long,
            filesList: List<BundleFile>,
        ): SegmentRow {
            val segmentId = "$day/$stream/$dirLeaf"
            val row = SegmentRow(
                id = segmentId,
                day = day,
                stream = stream,
                segment = wireKey,
                dirSegment = dirLeaf,
                state = state,
                byteSize = filesList.sumOf { it.byteSize },
                sealedAt = sealedAt,
                homeInstanceId = null,
                observerHandle = null,
            )
            val fileRows = filesList.map { f ->
                SegmentFileRow(
                    segmentId = segmentId,
                    sourceId = f.sourceId,
                    name = f.name,
                    sha256 = f.sha256,
                    byteSize = f.byteSize,
                    mediaType = f.mediaType,
                    captureStartEpochMs = f.captureStartEpochMs,
                    captureEndEpochMs = f.captureEndEpochMs,
                )
            }
            dao.insertSegmentWithFiles(row, fileRows)
            return row
        }
    }

    @Test
    fun evictedWireKeyTwinDoesNotPreventOrEvictLaterWindowTwin() {
        val env = TestEnv()
        val day = "20261101"
        val stream = MAIN_STREAM
        val wireKey = "20261101_010000_300"
        val firstStart = 1730437200000L
        val secondStart = 1730440800000L

        // Segment A: wire key K, sealed at the bare leaf K
        val dirA = env.seal(day, stream, wireKey, firstStart, firstStart + 300_000, "a.bin", sealedAt = 100)
        assertEquals(wireKey, dirA.fileName.toString())
        val rowA = env.dao.segmentById("$day/$stream/$wireKey")!!

        // Drain A -> confirmed and removed
        val reportA = drainSegments(
            store = env.store,
            reconcile = { manifests, _ -> manifests.map { ReconcileVerdict(it.key, needsUpload = true) } },
            ingest = { manifest, _ ->
                env.postedManifests.add(manifest)
                listOf(IngestOutcome.Accepted("srv-a", IngestDescriptors.Listed(receipt(manifest))))
            },
            readPayload = { _, _ -> byteArrayOf(1, 2, 3) },
            now = { 1_000_000L },
            log = { _, _ -> },
            finisher = env.finisher,
        )

        assertEquals(SyncOutcome.SUCCESS, reportA.workOutcome)
        assertEquals(QueueState.EVICTED, env.dao.segmentById(rowA.id)?.state)
        assertFalse(Files.exists(dirA))
        assertEquals(1, env.postedManifests.size)

        // Segment B: same wire key K, later window. A's directory is gone but its EVICTED row still
        // holds the bare leaf, so the writer seals B at the collision leaf and B gets its own row.
        val secondLeaf = "${wireKey}__ws$secondStart"
        val dirB = env.seal(day, stream, wireKey, secondStart, secondStart + 300_000, "b.bin", sealedAt = 200)
        assertEquals(secondLeaf, dirB.fileName.toString())
        val rowB = env.dao.segmentById("$day/$stream/$secondLeaf")!!
        assertEquals(QueueState.SEALED, rowB.state)
        assertEquals(wireKey, rowB.segment)

        // B stays SEALED across finishPass
        env.finisher.finishPass()
        assertEquals(QueueState.SEALED, env.dao.segmentById(rowB.id)?.state)
        assertTrue(Files.exists(dirB))
        assertEquals(QueueState.EVICTED, env.dao.segmentById(rowA.id)?.state)

        // B is POSTed and drained
        val reportB = drainSegments(
            store = env.store,
            reconcile = { manifests, _ -> manifests.map { ReconcileVerdict(it.key, needsUpload = true) } },
            ingest = { manifest, _ ->
                env.postedManifests.add(manifest)
                listOf(IngestOutcome.Accepted("srv-b", IngestDescriptors.Listed(receipt(manifest))))
            },
            readPayload = { _, _ -> byteArrayOf(1, 2, 3) },
            now = { 2_000_000L },
            log = { _, _ -> },
            finisher = env.finisher,
        )

        assertEquals(SyncOutcome.SUCCESS, reportB.workOutcome)
        assertEquals(2, env.postedManifests.size)
        assertEquals(wireKey, env.postedManifests[1].key.segment)
        assertEquals(listOf("b.bin"), env.postedManifests[1].files.map { it.name })
        assertEquals(QueueState.EVICTED, env.dao.segmentById(rowB.id)?.state)
        assertFalse(Files.exists(dirB))

        // Row A is still EVICTED
        assertEquals(QueueState.EVICTED, env.dao.segmentById(rowA.id)?.state)
    }

    @Test
    fun bothTwinDirectoriesExistConfirmingOneRemovesOnlyItsDirectory() {
        val env = TestEnv()
        val day = "20261101"
        val stream = MAIN_STREAM
        val wireKey = "20261101_010000_300"
        val firstStart = 1730437200000L
        val secondStart = 1730440800000L
        val secondLeaf = "${wireKey}__ws$secondStart"
        val fileA = BundleFile("audio", "a.bin", "sha-a", 3, "application/octet-stream", 1, 2)
        val fileB = BundleFile("audio", "b.bin", "sha-b", 3, "application/octet-stream", 3, 4)

        val dirA = env.writeSegmentToDisk(day, stream, wireKey, wireKey, firstStart, firstStart + 300_000, listOf(fileA))
        val dirB = env.writeSegmentToDisk(day, stream, wireKey, secondLeaf, secondStart, secondStart + 300_000, listOf(fileB))
        val rowA = env.insertRow(day, stream, wireKey, wireKey, QueueState.UPLOADED, 100, listOf(fileA))
        val rowB = env.insertRow(day, stream, wireKey, secondLeaf, QueueState.UPLOADED, 200, listOf(fileB))

        assertTrue(Files.exists(dirA))
        assertTrue(Files.exists(dirB))

        // Confirming only A removes A's directory; B remains
        env.finisher.finishUploaded(rowA.id)
        assertEquals(QueueState.EVICTED, env.dao.segmentById(rowA.id)?.state)
        assertFalse(Files.exists(dirA))
        assertTrue(Files.exists(dirB))
        assertEquals(QueueState.UPLOADED, env.dao.segmentById(rowB.id)?.state)

        // Confirming B removes B's directory
        env.finisher.finishUploaded(rowB.id)
        assertEquals(QueueState.EVICTED, env.dao.segmentById(rowB.id)?.state)
        assertFalse(Files.exists(dirB))
    }

    @Test
    fun reconciliationOfDiskTwinWhenRowMissingStaysSealedAcrossFinishPass() {
        val env = TestEnv()
        val day = "20261101"
        val stream = MAIN_STREAM
        val wireKey = "20261101_010000_300"
        val secondStart = 1730440800000L
        val secondLeaf = "${wireKey}__ws$secondStart"
        val fileA = BundleFile("audio", "a.bin", "sha-a", 3, "application/octet-stream", 1, 2)

        // A's EVICTED row exists in DB, its directory is already gone
        val rowA = env.insertRow(day, stream, wireKey, wireKey, QueueState.EVICTED, 100, listOf(fileA))

        // Gap: B seals through the writer (A's row holds the bare leaf, so B takes the collision
        // leaf), but B's row was not written in the database
        val dirB = env.seal(day, stream, wireKey, secondStart, secondStart + 300_000, "b.bin", sealedAt = 200, persistRow = false)
        assertEquals(secondLeaf, dirB.fileName.toString())
        assertTrue(Files.exists(dirB))
        assertNull(env.dao.segmentById("$day/$stream/$secondLeaf"))

        // SpoolRoomReconciler reconciles B from disk
        val reconciler = SpoolRoomReconciler(env.spool, env.dao)
        val inserted = reconciler.reconcile()
        assertEquals(1, inserted)

        val rowB = env.dao.segmentById("$day/$stream/$secondLeaf")
        assertEquals(QueueState.SEALED, rowB?.state)
        assertEquals(wireKey, rowB?.segment)
        assertEquals(secondLeaf, rowB?.dirSegment)

        // One finishPass: B stays SEALED, directory remains
        env.finisher.finishPass()
        assertEquals(QueueState.SEALED, env.dao.segmentById("$day/$stream/$secondLeaf")?.state)
        assertTrue(Files.exists(dirB))
        assertEquals(QueueState.EVICTED, env.dao.segmentById(rowA.id)?.state)
    }

    private fun receipt(manifest: BundleManifest): List<IngestFileDescriptor> =
        manifest.files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, "written") }
}
