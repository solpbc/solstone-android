// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GarminSlv3AndSpoolTest {

    @Test
    fun slv3WireParityAndListByteExtraction() {
        val payload = byteArrayOf(0x4F, 0x67, 0x67, 0x53, 0x00, 0x02, 0xAB.toByte(), 0xCD.toByte())
        assertEquals(13358, GarminSlv3Protocol.fastTag16(payload, 0, payload.size))

        val wire = GarminSlv3Protocol.encodeSlv3(
            flags = GarminSlv3Protocol.FLAG_SEG_START or GarminSlv3Protocol.FLAG_ACK_REQ or GarminSlv3Protocol.FLAG_SEG_PARTIAL,
            segId = 42,
            seq = 1001L,
            segEpochSec = 1795651200L,
            tzOffsetMin = -420,
            relMs = 12_500L,
            payload = payload,
        )
        assertEquals(GarminSlv3Protocol.HDR_SIZE + payload.size, wire.size)
        assertTrue(GarminSlv3Protocol.isSlv3(wire))

        // Garmin Android SDK 2.4.0 deserializes MonkeyByteArray as ArrayList<Byte>
        val asSdkList: List<Any?> = listOf(wire.map { it })
        val extracted = GarminSlv3Protocol.extractBinaryPayload(asSdkList)
        assertNotNull(extracted)
        assertContentEquals(wire, extracted)

        val parsed = GarminSlv3Protocol.parseSlv3(extracted)
        assertNotNull(parsed)
        assertTrue(parsed.tagOk)
        assertEquals(1, parsed.protoVer)
        assertEquals(42, parsed.segId)
        assertEquals(1001L, parsed.seq)
        assertEquals(1795651200L, parsed.segEpochSec)
        assertEquals(-420, parsed.tzOffsetMin)
        assertEquals(12_500L, parsed.relMs)
        assertTrue(parsed.isSegStart)
        assertTrue(parsed.isAckReq)
        assertTrue(parsed.isSegPartial)
        assertFalse(parsed.isBgOrigin)
        assertContentEquals(payload, parsed.payload)

        // Corrupt a payload byte -> tag mismatch
        val corrupt = wire.copyOf()
        corrupt[GarminSlv3Protocol.HDR_SIZE] = 0x00
        val parsedCorrupt = GarminSlv3Protocol.parseSlv3(corrupt)
        assertNotNull(parsedCorrupt)
        assertFalse(parsedCorrupt.tagOk)
    }

    @Test
    fun watchLocalClockStampsDayAndMidnightFromWatchOffset() {
        // 2026-11-28T06:58:00Z at UTC-07:00 (-420 min) = 2026-11-27 23:58:00 local (before midnight!)
        val epochBeforeMidnight = 1795849080L
        val clockMdt = GarminSlv3Protocol.watchLocalSegmentClock(
            epochBeforeMidnight,
            -420,
            300,
            referenceZoneId = java.time.ZoneId.of("America/Denver"),
        )
        assertEquals("2026-11-27", clockMdt.day)
        assertEquals("235800_300", clockMdt.segment)
        assertEquals("America/Denver", clockMdt.zoneId)
        assertTrue(clockMdt.zoneId in java.time.ZoneId.getAvailableZoneIds())
        assertEquals(-25_200, clockMdt.utcOffsetSeconds)

        // When phone zone differs from watch offset, falls back to IANA Etc/GMT+7 (exact -07:00)
        val clockMismatch = GarminSlv3Protocol.watchLocalSegmentClock(
            epochBeforeMidnight,
            -420,
            300,
            referenceZoneId = java.time.ZoneId.of("America/New_York"),
        )
        assertEquals("2026-11-27", clockMismatch.day)
        assertEquals("235800_300", clockMismatch.segment)
        assertEquals("Etc/GMT+7", clockMismatch.zoneId)
        assertTrue(clockMismatch.zoneId in java.time.ZoneId.getAvailableZoneIds())
        assertEquals(-25_200, java.time.ZoneId.of(clockMismatch.zoneId).rules.getOffset(java.time.Instant.ofEpochSecond(epochBeforeMidnight)).totalSeconds)

        // Same instant at UTC+05:45 (+345 min, Nepal) = 2026-11-28 12:43:00 local
        val clockNepal = GarminSlv3Protocol.watchLocalSegmentClock(
            epochBeforeMidnight,
            345,
            125,
            referenceZoneId = java.time.ZoneId.of("America/Denver"),
        )
        assertEquals("2026-11-28", clockNepal.day)
        assertEquals("124300_125", clockNepal.segment)
        assertEquals("+05:45", clockNepal.zoneId)
        assertEquals(20_700, clockNepal.utcOffsetSeconds)
    }

    @Test
    fun spoolCommitsCompleteSegmentAndHandlesLostAckRetriesAndProcessRestart() {
        val dir = Files.createTempDirectory("garmin-spool-test").toFile()
        try {
            val committed = mutableListOf<DecodedBundle>()
            val devId = 3646641882L
            val segEpoch = 1795849080L // 2026-11-27 23:58:00 -07:00
            val p0 = "OggS-part0-".repeat(20).toByteArray()
            val p1 = "OggS-part1-".repeat(20).toByteArray()
            val p2 = "OggS-part2-".repeat(10).toByteArray()

            // Process 1 receives slab 0 and slab 2 (out of order -> slab 1 missing -> ACK stops at 0)
            val spool1 = GarminSlabSpool(dir) { bundle ->
                committed += bundle
                GarminSlabSpool.CommitOutcome.Committed
            }
            val s0 = GarminSlv3Protocol.encodeSlv3(GarminSlv3Protocol.FLAG_SEG_START, 1, 0L, segEpoch, -420, 0L, p0)
            val s1 = GarminSlv3Protocol.encodeSlv3(0, 1, 1L, segEpoch, -420, 150_000L, p1)
            val s2 = GarminSlv3Protocol.encodeSlv3(
                GarminSlv3Protocol.FLAG_SEG_END or GarminSlv3Protocol.FLAG_ACK_REQ,
                1, 2L, segEpoch, -420, 300_000L, p2,
            )

            val r0 = spool1.onSlab(devId, s0)
            assertEquals(0L, r0.ackSeq)
            assertEquals(0, r0.segmentsCommitted)

            // Out-of-order slab 2 arrives before slab 1: ACK must stay at 0 and nothing commits yet
            val r2Early = spool1.onSlab(devId, s2)
            assertEquals(0L, r2Early.ackSeq)
            assertEquals(0, r2Early.segmentsCommitted)
            assertTrue(committed.isEmpty())

            // Simulate phone process kill & cold restart -> fresh GarminSlabSpool on same disk dir!
            val spool2 = GarminSlabSpool(dir) { bundle ->
                committed += bundle
                GarminSlabSpool.CommitOutcome.Committed
            }
            // Missing slab 1 arrives -> bridges 0..2, commits full segment, advances ACK to 2!
            val r1 = spool2.onSlab(devId, s1)
            assertEquals(2L, r1.ackSeq)
            assertEquals(1, r1.segmentsCommitted)
            assertEquals(1, committed.size)

            val bundle = committed.single()
            assertEquals("garmin-$devId-$segEpoch-1", bundle.header.bundleId)
            assertEquals("2026-11-27", bundle.header.day)
            assertEquals("235800_300", bundle.header.segment)
            assertEquals(1, bundle.files.size)
            assertEquals("audio.ogg", bundle.files[0].name)
            assertEquals("audio/ogg", bundle.files[0].mediaType)
            assertContentEquals(p0 + p1 + p2, bundle.files[0].bytes)

            // Lost-ACK retransmission of slab 2: idempotent ACK=2, zero duplicate commits!
            val r2Dup = spool2.onSlab(devId, s2)
            assertEquals(2L, r2Dup.ackSeq)
            assertTrue(r2Dup.shouldAck)
            assertEquals(0, r2Dup.segmentsCommitted)
            assertEquals(1, committed.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun spoolNeverAcksClosingSlabWhenCustodyCommitFailsAndRecoversCrashedPartialSegment() {
        val dir = Files.createTempDirectory("garmin-spool-crash").toFile()
        try {
            val committed = mutableListOf<DecodedBundle>()
            var failCommit = true
            val spool = GarminSlabSpool(dir) { bundle ->
                if (failCommit) {
                    GarminSlabSpool.CommitOutcome.Failed("simulated_disk_error")
                } else {
                    committed += bundle
                    GarminSlabSpool.CommitOutcome.Committed
                }
            }
            val devId = 99L
            val segEpoch1 = 1795850000L
            val segEpoch2 = 1795850500L

            // Watch starts seg 1, sends 2 slabs, then crashes (no FLAG_SEG_END!)
            val a0 = GarminSlv3Protocol.encodeSlv3(GarminSlv3Protocol.FLAG_SEG_START, 1, 0L, segEpoch1, -420, 0L, byteArrayOf(1, 2, 3))
            val a1 = GarminSlv3Protocol.encodeSlv3(GarminSlv3Protocol.FLAG_ACK_REQ, 1, 1L, segEpoch1, -420, 42_000L, byteArrayOf(4, 5, 6))
            assertEquals(0L, spool.onSlab(devId, a0).ackSeq)
            assertEquals(1L, spool.onSlab(devId, a1).ackSeq)
            assertTrue(committed.isEmpty())

            // Watch reboots and starts seg 2 -> spool recovers seg 1 as partial (44s, NOT 300s!)
            failCommit = false
            val b0 = GarminSlv3Protocol.encodeSlv3(
                GarminSlv3Protocol.FLAG_SEG_START or GarminSlv3Protocol.FLAG_SEG_END or GarminSlv3Protocol.FLAG_ACK_REQ,
                2, 2L, segEpoch2, -420, 15_000L, byteArrayOf(7, 8, 9),
            )
            // First try with commit failure on seg 2 closing slab
            failCommit = true
            val failRes = spool.onSlab(devId, b0)
            assertEquals(1L, failRes.ackSeq) // Must NOT ack seq 2 while commit failed!
            assertTrue(committed.isEmpty())

            // Retry once storage recovers -> both crashed partial seg 1 AND seg 2 commit cleanly!
            failCommit = false
            val okRes = spool.onSlab(devId, b0)
            assertEquals(2L, okRes.ackSeq)
            assertEquals(2, committed.size)
            // Seg 1 recovered with actual elapsed duration ~44s (42s + 2.5s), never falsely 300s
            assertTrue(committed[0].header.segment.endsWith("_44"))
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), committed[0].files[0].bytes)
            assertContentEquals(byteArrayOf(7, 8, 9), committed[1].files[0].bytes)

            // App ID matcher checks both hyphenated and hex forms
            assertTrue(GarminSlv3Protocol.matchesSolstoneAppId("c7b3e841-9d2a-4f6b-a108-6e4c2b9f3d12"))
            assertTrue(GarminSlv3Protocol.matchesSolstoneAppId("C7B3E8419D2A4F6BA1086E4C2B9F3D12"))
            assertFalse(GarminSlv3Protocol.matchesSolstoneAppId("00000000000000000000000000000000"))
            assertNull(GarminSlv3Protocol.parseSlv3(byteArrayOf(1, 2, 3)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun outOfOrderNextSegmentDoesNotPrematurelySealPriorSegmentWithMissingSlab() {
        val dir = Files.createTempDirectory("garmin-spool-ooo-seg").toFile()
        try {
            val committed = mutableListOf<DecodedBundle>()
            val spool = GarminSlabSpool(dir) { bundle ->
                committed += bundle
                GarminSlabSpool.CommitOutcome.Committed
            }
            val devId = 77L
            val segEpoch1 = 1795850000L
            val segEpoch2 = 1795850300L

            val s0 = GarminSlv3Protocol.encodeSlv3(GarminSlv3Protocol.FLAG_SEG_START, 1, 0L, segEpoch1, -420, 0L, byteArrayOf(10, 11))
            val s1 = GarminSlv3Protocol.encodeSlv3(GarminSlv3Protocol.FLAG_SEG_END, 1, 1L, segEpoch1, -420, 300_000L, byteArrayOf(12, 13))
            val s2 = GarminSlv3Protocol.encodeSlv3(
                GarminSlv3Protocol.FLAG_SEG_START or GarminSlv3Protocol.FLAG_SEG_END or GarminSlv3Protocol.FLAG_ACK_REQ,
                2, 2L, segEpoch2, -420, 10_000L, byteArrayOf(20, 21),
            )

            assertEquals(0L, spool.onSlab(devId, s0).ackSeq)
            // Seg 2 slab arrives BEFORE seg 1 closing slab (seq 1 missing): seg 1 MUST stay open!
            val early = spool.onSlab(devId, s2)
            assertEquals(0L, early.ackSeq)
            assertTrue(committed.isEmpty())

            // Missing seg 1 closing slab arrives -> both seg 1 (full 300s) and seg 2 commit oldest-first!
            val bridged = spool.onSlab(devId, s1)
            assertEquals(2L, bridged.ackSeq)
            assertEquals(2, committed.size)
            assertTrue(committed[0].header.segment.endsWith("_300"))
            assertContentEquals(byteArrayOf(10, 11, 12, 13), committed[0].files[0].bytes)
            assertContentEquals(byteArrayOf(20, 21), committed[1].files[0].bytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun sunZonePointLookupAndAckPayloadIncludeRepresentativeCoordinates() {
        val denver = SunZonePointLookup.lookupLatLon100("America/Denver")
        assertNotNull(denver)
        assertContentEquals(intArrayOf(3974, -10498), denver)

        val tokyo = SunZonePointLookup.lookupLatLon100("Asia/Tokyo")
        assertNotNull(tokyo)
        assertContentEquals(intArrayOf(3565, 13974), tokyo)

        assertNull(SunZonePointLookup.lookupLatLon100("Etc/Unknown"))

        val ackWithZone = GarminSlv3Protocol.buildAckPayload(42L, denver)
        assertEquals(42, ackWithZone["a"])
        assertEquals(1, ackWithZone["v"])
        assertEquals(listOf(3974, -10498), ackWithZone["z"])

        val ackNoZone = GarminSlv3Protocol.buildAckPayload(42L, null)
        assertEquals(42, ackNoZone["a"])
        assertEquals(1, ackNoZone["v"])
        assertFalse(ackNoZone.containsKey("z"))
    }
}
