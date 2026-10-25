// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapClassifiersTest {
    private val zone = ZoneId.of("America/New_York")

    @Test
    fun aFailedSweepLeavesTheGateShutUnlessTheInstallAlreadyOpenedIt() {
        assertTrue(gapPassCompleted(sweepSucceeded = true, opened = false))
        assertTrue(gapPassCompleted(sweepSucceeded = true, opened = true))
        assertTrue(gapPassCompleted(sweepSucceeded = false, opened = true))
        assertFalse(gapPassCompleted(sweepSucceeded = false, opened = false))
    }

    @Test
    fun adtsHeaderAtZeroExistsWhenTheFileCoversTheFrame() {
        val header = adts(frameLength = 32, protectionAbsent = true)
        assertTrue(adtsExisted(header, fileSize = 32))
        assertTrue(adtsExisted(header, fileSize = 40))
        assertFalse(adtsExisted(header, fileSize = 31))
    }

    @Test
    fun protectionBitSelectsTheNineByteHeader() {
        val header = adts(frameLength = 40, protectionAbsent = false)
        assertEquals(9, header.size)
        assertEquals(40, adtsFrameLength(header))
        assertTrue(adtsExisted(header, fileSize = 40))
        assertFalse(adtsExisted(header.copyOf(7), fileSize = 40))
    }

    @Test
    fun noHeaderExistsOnlyAbove8191AndAShortDeclaredFrameDoesNot() {
        val noise = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        assertFalse(adtsExisted(noise, fileSize = 8191))
        assertTrue(adtsExisted(noise, fileSize = 8192))
        val declared = adts(frameLength = 200, protectionAbsent = true)
        assertFalse(adtsExisted(declared, fileSize = 100))
    }

    @Test
    fun payloadFileExistsWhenLengthIsPositive() {
        assertFalse(payloadBytesExisted(0))
        assertTrue(payloadBytesExisted(1))
    }

    @Test
    fun screenIndexBitTwoIsNotACapture() {
        assertFalse(screenIndexHasRealSample("10,20,4\n"))
        assertTrue(screenIndexHasRealSample("10,20,0\n"))
        assertTrue(screenIndexHasRealSample("10,20,1\n0,0,4\n"))
        assertFalse(screenIndexHasRealSample("10,20,5\n"))
        assertTrue(payloadBytesExisted(12))
    }

    @Test
    fun inWindowUsesLocalDatesAcrossADaylightSavingGap() {
        val now = zoned(2026, 11, 7, 0, 30).toInstant().toEpochMilli()
        val found = zoned(2026, 11, 1, 0, 30).toInstant().toEpochMilli()
        assertTrue(gapFoundInWindow(found, now, zone))
        val hours = (now - found) / 3_600_000
        assertTrue(hours > 6 * 24, "calendar window is wider than 6*24 hours: $hours")
        val outside = zoned(2026, 10, 31, 23, 0).toInstant().toEpochMilli()
        assertFalse(gapFoundInWindow(outside, now, zone))
        val withinHoursButNextLocalDate = zoned(2026, 11, 8, 0, 0).toInstant().toEpochMilli()
        assertFalse(gapFoundInWindow(withinHoursButNextLocalDate, now, zone))
    }

    @Test
    fun windowEndIsLocalMidnightSevenDaysAfterTheFoundDate() {
        val found = zoned(2026, 10, 25, 15, 0).toInstant().toEpochMilli()
        val end = gapWindowEndMillis(found, zone)
        assertEquals(zoned(2026, 11, 1, 0, 0).toInstant().toEpochMilli(), end)
    }

    @Test
    fun dayLineSentences() {
        val now = zoned(2026, 10, 25, 12, 0).toInstant().toEpochMilli()
        assertEquals(GAP_UNDATED, line(now, loss(captured = null, found = now)))
        assertEquals(GAP_TODAY, line(now, loss(captured = now, found = now)))
        val yesterday = zoned(2026, 10, 24, 9, 0).toInstant().toEpochMilli()
        assertEquals(GAP_YESTERDAY, line(now, loss(captured = yesterday, found = now)))
        val monday = zoned(2026, 10, 19, 9, 0).toInstant().toEpochMilli()
        assertEquals("some of what you shared on monday didn't reach it.", line(now, loss(captured = monday, found = now)))
        val nineDays = zoned(2026, 10, 16, 9, 0).toInstant().toEpochMilli()
        assertEquals(GAP_UNDATED, line(now, loss(captured = nineDays, found = now)))
        assertEquals(
            "some of what you shared since yesterday didn't reach it.",
            line(now, loss(captured = yesterday, found = now), loss(captured = now, found = now)),
        )
        assertEquals(
            "some of what you shared since monday didn't reach it.",
            line(now, loss(captured = monday, found = now), loss(captured = yesterday, found = now)),
        )
        val outside = zoned(2026, 10, 18, 9, 0).toInstant().toEpochMilli()
        assertEquals(
            GAP_TODAY,
            line(now, loss(captured = now, found = now), loss(captured = outside, found = outside)),
        )
        assertNull(line(now, loss(captured = outside, found = outside)))
        assertNull(line(now, loss(captured = now, found = now, seen = true)))
    }

    private fun line(now: Long, vararg losses: GapDayLoss): String? = gapDayLine(losses.toList(), now, zone)

    private fun loss(captured: Long?, found: Long, seen: Boolean = false) =
        GapDayLoss(capturedEpochMs = captured, foundEpochMs = found, seen = seen)

    private fun zoned(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    private fun adts(frameLength: Int, protectionAbsent: Boolean): ByteArray {
        val header = if (protectionAbsent) 7 else 9
        val bytes = ByteArray(header)
        bytes[0] = 0xFF.toByte()
        bytes[1] = (0xF0 or if (protectionAbsent) 1 else 0).toByte()
        bytes[3] = ((frameLength shr 11) and 0x03).toByte()
        bytes[4] = ((frameLength shr 3) and 0xFF).toByte()
        bytes[5] = ((frameLength and 0x07) shl 5).toByte()
        return bytes
    }
}

class GapTechnicalDetailsTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-25T15:04:00Z").toEpochMilli()

    @Test
    fun formatterIncludesEveryFieldNewestFirstAndTheConversionState() {
        val older = row(id = "a", last = Instant.parse("2026-10-24T08:00:00Z").toEpochMilli(), captured = Instant.parse("2026-10-23T07:30:00Z").toEpochMilli(), count = 1)
        val newer = row(
            id = "b",
            last = Instant.parse("2026-10-25T09:04:00Z").toEpochMilli(),
            captured = null,
            count = 4,
            seen = true,
            kind = "diagnostic",
            source = "screen",
            reason = "remux failed",
            material = "rec-bbbb",
        )
        val stale = row(id = "c", last = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(), captured = now, count = 1)
        val notRun = formatGapTechnicalDetails(listOf(older, newer, stale), GapConversionState.NotRun, now, zone)
        assertTrue(notRun.startsWith("not run\n"))
        val lines = notRun.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("2026-10-25 09:04 diagnostic screen remux failed rec-bbbb "))
        assertTrue(lines[1].contains("captured unknown"))
        assertTrue(lines[1].contains("seen"))
        assertTrue(lines[1].contains("count 4"))
        assertTrue(lines[2].contains("loss"))
        assertTrue(lines[2].contains("audio"))
        assertTrue(lines[2].contains("undeliverable"))
        assertTrue(lines[2].contains("rec-aaaa"))
        assertTrue(lines[2].contains("captured 2026-10-23 07:30"))
        assertTrue(lines[2].contains("unseen"))
        assertTrue(lines[2].contains("count 1"))
        assertFalse(notRun.contains("rec-cccc"))
        assertEquals(
            "complete 2",
            formatGapTechnicalDetails(emptyList(), GapConversionState.Complete(2), now, zone),
        )
        assertEquals(
            "failed disk full",
            formatGapTechnicalDetails(emptyList(), GapConversionState.Failed("disk full"), now, zone),
        )
    }

    private fun row(
        id: String,
        last: Long,
        captured: Long?,
        count: Int,
        seen: Boolean = false,
        kind: String = "loss",
        source: String = "audio",
        reason: String = "undeliverable",
        material: String = "rec-aaaa",
    ) = GapNoticeRow(
        id = id.padEnd(32, '0'),
        kind = kind,
        source = source,
        reason = reason,
        material = material,
        capturedEpochMs = captured,
        foundEpochMs = last,
        seen = seen,
        count = count,
        firstEpochMs = last,
        lastEpochMs = last,
    )
}
