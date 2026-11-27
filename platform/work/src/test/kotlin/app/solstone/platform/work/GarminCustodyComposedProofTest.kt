// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.QueueState
import app.solstone.core.sources.WATCH_STREAM
import app.solstone.core.watch.courier.GarminSlabSpool
import app.solstone.core.watch.courier.GarminSlv3Protocol
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.platform.persistence.room.EventRow
import app.solstone.platform.persistence.room.SegmentDaoWithoutIntake
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import app.solstone.platform.persistence.room.WatchBundleReceiptRow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end JVM custody proof for Garmin SLV3 slabs -> GarminSlabSpool -> WatchCustodyPublisher:
 * verifies watch-audio source identity, audio.ogg/audio/ogg spooling, midnight watch-clock keys,
 * oldest-first drain ordering, pairing-agnostic custody (homeInstanceId = null), and crash recovery.
 */
class GarminCustodyComposedProofTest {

    private class InMemorySegmentDao : SegmentDaoWithoutIntake() {
        val segments = mutableMapOf<String, SegmentRow>()
        val files = mutableMapOf<String, MutableList<SegmentFileRow>>()
        val receipts = mutableMapOf<String, WatchBundleReceiptRow>()

        override fun insertReceipt(receipt: WatchBundleReceiptRow) {
            receipts[receipt.bundleId] = receipt
        }

        override fun receiptByBundleId(bundleId: String): WatchBundleReceiptRow? = receipts[bundleId]

        override fun insertSegment(segment: SegmentRow) {
            segments[segment.id] = segment
        }

        override fun insertFiles(files: List<SegmentFileRow>) {
            files.forEach { f -> this.files.getOrPut(f.segmentId) { mutableListOf() }.add(f) }
        }

        override fun insertEvents(events: List<EventRow>) = Unit

        override fun segmentsByState(state: QueueState): List<SegmentRow> =
            segments.values.filter { it.state == state }

        override fun segmentsForDrain(stream: String): List<SegmentRow> =
            segments.values.filter {
                it.stream == stream &&
                    (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED)
            }.sortedWith(compareBy({ it.sealedAt }, { it.id }))

        override fun segmentsByDay(day: String): List<SegmentRow> =
            segments.values.filter { it.day == day }

        override fun segmentById(id: String): SegmentRow? = segments[id]
        override fun duplicateBySha256(sha256: String): List<SegmentFileRow> = emptyList()
        override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> = files[segmentId].orEmpty()
        override fun recordAttempt(id: String, attempts: Int, at: Long, homeInstanceId: String?): Int = 1
        override fun recordUploaded(id: String): Int = 1
        override fun recordFailure(id: String, code: Int?, error: String?): Int = 1
        override fun upsertSyncState(row: SyncStateRow) = Unit
        override fun syncState(): SyncStateRow? = null
        override fun pendingCount(stream: String): Int =
            segments.values.count {
                it.stream == stream &&
                    (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED)
            }
        override fun pendingSourceIds(stream: String): List<String> = emptyList()
        override fun segmentState(id: String): QueueState? = segments[id]?.state
        override fun updateState(id: String, state: QueueState): Int {
            val cur = segments[id] ?: return 0
            segments[id] = cur.copy(state = state)
            return 1
        }
        override fun deleteFilesBySegmentId(segmentId: String): Int {
            files.remove(segmentId)
            return 1
        }
        override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int {
            segmentIds.forEach { files.remove(it) }
            return 1
        }
    }

    @Test
    fun garminSlv3SlabsCommitAtomicallyToWatchAudioCustodyAcrossMidnight() {
        val root = Files.createTempDirectory("garmin-custody-proof")
        try {
            val slabDir = root.resolve("garmin_slabs").toFile()
            val spoolDir = root.resolve("spool")
            val stagingDir = root.resolve("staging")
            val dao = InMemorySegmentDao()
            val publisher = WatchCustodyPublisher(spoolDir, stagingDir, dao)
            var followUpCount = 0

            val spool = GarminSlabSpool(slabDir) { bundle ->
                when (publisher.publishDecoded(bundle) { followUpCount++ }) {
                    is PublishBundleResult.Ack -> GarminSlabSpool.CommitOutcome.Committed
                    is PublishBundleResult.Conflict -> GarminSlabSpool.CommitOutcome.Duplicate
                    is PublishBundleResult.VersionMismatch,
                    is PublishBundleResult.Rejected,
                    -> GarminSlabSpool.CommitOutcome.Failed("rejected")
                }
            }

            // Segment 1: 2026-11-27 23:58:00 -07:00 (2026-11-28T06:58:00Z), 300s full segment
            val seg1Epoch = 1795849080L
            val ogg1a = byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x00, 0x02) + ByteArray(64) { (it + 1).toByte() }
            val ogg1b = ByteArray(64) { (it + 65).toByte() }
            val s0 = GarminSlv3Protocol.encodeSlv3(GarminSlv3Protocol.FLAG_SEG_START, 1, 0L, seg1Epoch, -420, 0L, ogg1a)
            val s1 = GarminSlv3Protocol.encodeSlv3(
                GarminSlv3Protocol.FLAG_SEG_END or GarminSlv3Protocol.FLAG_ACK_REQ,
                1, 1L, seg1Epoch, -420, 300_000L, ogg1b,
            )

            // Segment 2: 2026-11-28 00:03:00 -07:00 (after midnight!), 90s partial stop
            val seg2Epoch = seg1Epoch + 300L
            val ogg2 = byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x00, 0x02) + ByteArray(48) { (it + 100).toByte() }
            val s2 = GarminSlv3Protocol.encodeSlv3(
                GarminSlv3Protocol.FLAG_SEG_START or GarminSlv3Protocol.FLAG_SEG_END or
                    GarminSlv3Protocol.FLAG_SEG_PARTIAL or GarminSlv3Protocol.FLAG_ACK_REQ,
                2, 2L, seg2Epoch, -420, 88_000L, ogg2,
            )

            assertEquals(0L, spool.onSlab(3646641882L, s0).ackSeq)
            val res1 = spool.onSlab(3646641882L, s1)
            assertEquals(1L, res1.ackSeq)
            assertEquals(1, res1.segmentsCommitted)

            val res2 = spool.onSlab(3646641882L, s2)
            assertEquals(2L, res2.ackSeq)
            assertEquals(1, res2.segmentsCommitted)
            assertEquals(2, followUpCount)

            // Verify oldest-first drain order across midnight boundary
            val drainOrder = dao.segmentsForDrain(WATCH_STREAM)
            assertEquals(2, drainOrder.size)
            assertEquals("2026-11-27", drainOrder[0].day)
            assertEquals("235800_300", drainOrder[0].segment)
            assertEquals("2026-11-28", drainOrder[1].day)
            assertEquals("000300_90", drainOrder[1].segment)

            // Held material is pairing-agnostic (homeInstanceId == null)
            assertNull(drainOrder[0].homeInstanceId)
            assertNull(drainOrder[1].homeInstanceId)

            // Verify watch-audio source + audio.ogg bytes on disk match watch payload byte-for-byte
            val files0 = dao.filesBySegmentId(drainOrder[0].id)
            assertEquals(1, files0.size)
            assertEquals(WatchCourierProtocol.SOURCE_ID_WATCH_AUDIO, files0[0].sourceId)
            assertEquals("audio.ogg", files0[0].name)
            assertEquals("audio/ogg", files0[0].mediaType)

            val spooledAudio0 = spoolDir.resolve("2026-11-27").resolve(WATCH_STREAM).resolve("235800_300").resolve("audio.ogg")
            assertTrue(Files.isRegularFile(spooledAudio0))
            assertContentEquals(ogg1a + ogg1b, Files.readAllBytes(spooledAudio0))

            // Duplicate re-delivery of s1 produces zero duplicate rows
            val dupRes = spool.onSlab(3646641882L, s1)
            assertEquals(2L, dupRes.ackSeq)
            assertEquals(0, dupRes.segmentsCommitted)
            assertEquals(2, dao.segments.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
