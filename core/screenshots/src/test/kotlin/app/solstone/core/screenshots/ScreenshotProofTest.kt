// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

import app.solstone.core.pl.toJson
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenshotProofTest {
    @Test
    fun anAndroidPngWithoutMakeIsAScreenshotAndACameraPhotoIsNot() {
        val shot = classifyScreenshot(png(software = "Android 15", date = "2026:11:03 14:32:07"))
        val hit = assertIs<ShotRead.Shot>(shot)
        assertEquals(ShotKind.PNG, hit.kind)
        assertEquals("2026:11:03 14:32:07", hit.localDateTime)
        val camera = classifyScreenshot(png(software = "Android 15", make = "Google"))
        assertEquals(ShotRead.NotScreenshot, camera)
        val namedOnly = classifyScreenshot(png(software = "Screenshot"))
        assertEquals(ShotRead.NotScreenshot, namedOnly)
    }

    @Test
    fun aJpegCarriesTheSameRuleAndTheFileNameIsNotConsulted() {
        val shot = classifyScreenshot(jpeg(software = "Android CQ1A.240205.002", date = "2026:11:04 09:15:02"))
        assertIs<ShotRead.Shot>(shot)
        val camera = classifyScreenshot(jpeg(software = "HDR+ 1.0.123", make = "Google", date = "2026:11:04 09:15:02"))
        assertEquals(ShotRead.NotScreenshot, camera)
    }

    @Test
    fun aPngTextSoftwareTagIsEnoughWhenMakeIsAbsent() {
        val shot = classifyScreenshot(pngText(software = "Android 14"))
        assertIs<ShotRead.Shot>(shot)
    }

    @Test
    fun oversizeAndUnreadableReachAnEnd() {
        val wide = classifyScreenshot(png(software = "Android 15", width = 20_000, height = 6_000))
        assertEquals(ShotRead.Ended(ShotKind.PNG, ShotEnd.REFUSED_SIZE), wide)
        val broken = classifyScreenshot(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        assertEquals(ShotRead.NotScreenshot, broken)
    }

    @Test
    fun theGoldenMomentsLandOnTheirOwnClock() {
        val denver = screenshotKeys("2026-11-03T14:32:07-07:00")
        assertEquals("20261103", denver.day)
        assertEquals("143207_1", denver.segment)
        val paris = screenshotKeys("2026-11-04T09:15:02+01:00")
        assertEquals("20261104", paris.day)
        assertEquals("091502_1", paris.segment)
        val utc = screenshotKeys("2026-11-05T00:00:00Z")
        assertEquals("20261105", utc.day)
        assertEquals("000000_1", utc.segment)
        val meta = screenshotMeta("2026-11-03T14:32:07-07:00", TakenFrom.PLATFORM)
        assertEquals(
            """{"captured_at":"2026-11-03T14:32:07-07:00","taken_from":"platform","v":1}""",
            toJson(meta),
        )
    }

    @Test
    fun platformTimeUsesTheZoneAndAMissingDateFallsBackToTheFile() {
        val zone = ZoneId.of("America/Denver")
        val fromTag = screenshotWhen("2026:11:03 14:32:07", null, zone)
        assertEquals(TakenFrom.PLATFORM, fromTag?.takenFrom)
        assertEquals("2026-11-03T14:32:07-07:00", fromTag?.capturedAt)
        val fromFile = screenshotWhen(null, fromTag!!.epochMs, ZoneOffset.UTC)
        assertEquals(TakenFrom.FILE, fromFile?.takenFrom)
        assertTrue(fromFile!!.capturedAt.endsWith("Z"))
        val storedOffset = screenshotWhen("2026:09:08 18:07:41", null, zone, "+00:00")
        assertEquals("2026-09-08T18:07:41Z", storedOffset?.capturedAt)
        assertEquals(TakenFrom.PLATFORM, storedOffset?.takenFrom)
    }

    @Test
    fun aPauseAndTheLowerBoundLeaveTheRest() {
        val zone = ZoneId.of("America/Denver")
        val today = LocalDate.of(2026, 11, 10)
        val lower = historyLowerBound(HistoryChoice.NOTHING_BEFORE_TODAY, today, zone)
        val earlierToday = today.atStartOfDay(zone).plusHours(1).toInstant().toEpochMilli()
        val yesterday = today.minusDays(1).atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
        assertTrue(eligibleCapture(earlierToday, lower, emptyList()))
        assertFalse(eligibleCapture(yesterday, lower, emptyList()))
        val pause = listOf(PauseSpan(earlierToday, earlierToday + 3_600_000))
        assertFalse(eligibleCapture(earlierToday + 1_000, lower, pause))
        assertTrue(eligibleCapture(earlierToday + 3_600_000, lower, pause))
        val open = listOf(PauseSpan(earlierToday, null))
        assertFalse(eligibleCapture(earlierToday + 86_400_000, lower, open))
    }

    @Test
    fun theReceivedRecordIsPerJournalAndTheConfirmWordsAreFixed() {
        val rows = listOf(ReceivedHash("journal-a", "abc", ReceivedEnd.DELIVERED))
        assertTrue(alreadyWithJournal(rows, "journal-a", "abc"))
        assertFalse(alreadyWithJournal(rows, "journal-b", "abc"))
        assertEquals("start from today", confirmLabel(0))
        assertEquals("bring in 1 screenshot", confirmLabel(1))
        assertEquals("bring in 4 screenshots", confirmLabel(4))
        assertTrue(backfillOpen(1, 4))
        assertFalse(backfillOpen(4, 4))
    }

    @Test
    fun capabilityIsFalseOnlyWhenTheStatusOmitsIt() {
        assertEquals(true, parseScreenshotsCapability(200, """{"capabilities":["segments","screenshots"]}"""))
        assertEquals(false, parseScreenshotsCapability(200, """{"capabilities":["segments"]}"""))
        assertEquals(null, parseScreenshotsCapability(503, """{"capabilities":["screenshots"]}"""))
        assertEquals(null, parseScreenshotsCapability(200, """{"capabilities":[1]}"""))
    }
}

private fun png(
    software: String,
    make: String? = null,
    date: String? = null,
    width: Int = 2,
    height: Int = 2,
): ByteArray {
    val ihdr = ByteBuffer.allocate(13)
        .putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
    return PNG_SIG + chunk("IHDR", ihdr) + chunk("eXIf", tiff(software, make, date)) + chunk("IEND", byteArrayOf())
}

private fun pngText(software: String): ByteArray {
    val ihdr = ByteBuffer.allocate(13).putInt(2).putInt(2).put(8).put(2).put(0).put(0).put(0).array()
    val text = "Software".toByteArray(Charsets.US_ASCII) + 0 + software.toByteArray(Charsets.UTF_8)
    return PNG_SIG + chunk("IHDR", ihdr) + chunk("tEXt", text) + chunk("IEND", byteArrayOf())
}

private fun jpeg(software: String, make: String? = null, date: String? = null): ByteArray {
    val exif = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff(software, make, date)
    val app1 = marker(0xE1, exif)
    val sof = marker(0xC0, byteArrayOf(8, 0, 2, 0, 2, 1, 0x11, 0))
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + sof + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
}

private fun tiff(software: String, make: String?, date: String?): ByteArray {
    data class Field(val tag: Int, val text: String)
    val fields = buildList {
        if (make != null) add(Field(0x010F, make))
        add(Field(0x0131, software))
        if (date != null) add(Field(0x9003, date))
    }.sortedBy { it.tag }
    val header = 8
    val ifdBytes = 2 + fields.size * 12 + 4
    val data = ArrayList<ByteArray>()
    var cursor = header + ifdBytes
    val entries = fields.map { field ->
        val raw = (field.text + "\u0000").toByteArray(Charsets.US_ASCII)
        val value = if (raw.size <= 4) {
            raw + ByteArray(4 - raw.size)
        } else {
            val at = cursor
            data += raw
            cursor += raw.size
            ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(at).array()
        }
        ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(field.tag.toShort())
            .putShort(2)
            .putInt(raw.size)
            .put(value)
            .array()
    }
    val out = ByteBuffer.allocate(cursor)
    out.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    out.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
    out.putShort(fields.size.toShort())
    entries.forEach { out.put(it) }
    out.putInt(0)
    data.forEach { out.put(it) }
    return out.array()
}

private fun chunk(type: String, data: ByteArray): ByteArray {
    val crc = CRC32()
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    crc.update(typeBytes)
    crc.update(data)
    val head = ByteBuffer.allocate(8).putInt(data.size).put(typeBytes).array()
    val tail = ByteBuffer.allocate(4).putInt(crc.value.toInt()).array()
    return head + data + tail
}

private fun marker(marker: Int, payload: ByteArray): ByteArray {
    val length = payload.size + 2
    return byteArrayOf(0xFF.toByte(), marker.toByte()) +
        ByteBuffer.allocate(2).putShort(length.toShort()).array() + payload
}

private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
