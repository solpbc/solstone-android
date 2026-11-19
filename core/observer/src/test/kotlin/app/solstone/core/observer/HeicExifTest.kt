// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeicExifTest {

    @Test
    fun carryToUprightJpegPreservesCaptureDateOffsetAndCameraAndDropsOrientation() {
        val tiff = buildTestExifTiff(
            make = "Google",
            orientation = 6,
            dateTimeOriginal = "2026:11:12 08:30:00",
            offsetTimeOriginal = "+09:00",
        )
        val heic = buildSyntheticHeicWithExif(tiff)
        // Minimal JPEG with SOI + JFIF APP0 + EOI.
        val bareJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x08, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00, 0x01,
            0xFF.toByte(), 0xD9.toByte(),
        )

        val out = HeicExif.carryToUprightJpeg(heic, bareJpeg)
        val app1Tiff = extractJpegApp1Tiff(out)
        assertNotNull("APP1 Exif segment must be injected", app1Tiff)
        val parsed = readExifTags(app1Tiff!!)
        assertEquals("Google", parsed.make)
        assertEquals("2026:11:12 08:30:00", parsed.dateTimeOriginal)
        assertEquals("+09:00", parsed.offsetTimeOriginal)
        assertNull("Orientation must be stripped after upright decode", parsed.orientation)
        // Trailing EOI must survive intact.
        assertEquals(0xFF.toByte(), out[out.size - 2])
        assertEquals(0xD9.toByte(), out[out.size - 1])
    }

    @Test
    fun carryToUprightJpegLeavesJpegUnchangedWhenHeicHasNoExif() {
        val heicWithoutExif = buildBox("ftyp", "heic\u0000\u0000\u0000\u0000mif1".toByteArray(Charsets.US_ASCII))
        val bareJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val out = HeicExif.carryToUprightJpeg(heicWithoutExif, bareJpeg)
        assertArrayEquals(bareJpeg, out)
    }

    @Test
    fun carryToUprightJpegRejectsNonJpegTargetWithoutThrowing() {
        val tiff = buildTestExifTiff("Apple", 1, "2026:11:12 08:30:00", "+09:00")
        val heic = buildSyntheticHeicWithExif(tiff)
        val notJpeg = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val out = HeicExif.carryToUprightJpeg(heic, notJpeg)
        assertArrayEquals(notJpeg, out)
        assertFalse(out.isEmpty())
    }

    // ---- test builders ----

    private fun buildBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.putInt(8 + payload.size)
        out.put(type.toByteArray(Charsets.US_ASCII))
        out.put(payload)
        return out.array()
    }

    private fun buildSyntheticHeicWithExif(tiff: ByteArray): ByteArray {
        val ftyp = buildBox("ftyp", "heic\u0000\u0000\u0000\u0000mif1heic".toByteArray(Charsets.US_ASCII))
        // Exif item payload = 4-byte offset (0) + TIFF bytes.
        val exifPayload = ByteBuffer.allocate(4 + tiff.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .put(tiff)
            .array()

        // infe v2 box: version=2, flags=0, item_id=42 (2 bytes), protection=0 (2 bytes), type="Exif", name=""
        val infePayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(2, 0, 0, 0))
            write(byteArrayOf(0, 42))
            write(byteArrayOf(0, 0))
            write("Exif".toByteArray(Charsets.US_ASCII))
            write(0)
        }.toByteArray()
        val infe = buildBox("infe", infePayload)
        val iinfPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0)) // version 0, flags 0
            write(byteArrayOf(0, 1)) // entry_count = 1
            write(infe)
        }.toByteArray()
        val iinf = buildBox("iinf", iinfPayload)

        // Precompute exact file offset of mdat payload so iloc points straight at it.
        // iloc v0: 4 (ver/flags) + 2 (sizes: offset=4, len=4, base=0, res=0) + 2 (count=1)
        //        + 2 (id) + 2 (dref) + 2 (extents=1) + 4 (offset) + 4 (length) = 22 bytes payload -> 30 byte box
        val ilocBoxSize = 8 + 22
        val metaBoxSize = 8 + 4 + iinf.size + ilocBoxSize
        val mdatPayloadOffset = ftyp.size + metaBoxSize + 8

        val ilocPayload = ByteBuffer.allocate(22).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version 0, flags 0
            put(0x44.toByte()) // offset_size=4, length_size=4
            put(0x00.toByte()) // base_offset_size=0
            putShort(1) // item_count = 1
            putShort(42) // item_id = 42
            putShort(0) // data_reference_index = 0
            putShort(1) // extent_count = 1
            putInt(mdatPayloadOffset)
            putInt(exifPayload.size)
        }.array()
        val iloc = buildBox("iloc", ilocPayload)
        assertEquals(ilocBoxSize, iloc.size)

        val metaPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0)) // full box version/flags
            write(iinf)
            write(iloc)
        }.toByteArray()
        val meta = buildBox("meta", metaPayload)
        assertEquals(metaBoxSize, meta.size)

        val mdat = buildBox("mdat", exifPayload)
        return ByteArrayOutputStream().apply {
            write(ftyp)
            write(meta)
            write(mdat)
        }.toByteArray()
    }

    private fun buildTestExifTiff(
        make: String,
        orientation: Int,
        dateTimeOriginal: String,
        offsetTimeOriginal: String,
    ): ByteArray {
        val makeBytes = "$make\u0000".toByteArray(Charsets.US_ASCII)
        val dtBytes = "$dateTimeOriginal\u0000".toByteArray(Charsets.US_ASCII)
        val offBytes = "$offsetTimeOriginal\u0000".toByteArray(Charsets.US_ASCII)

        // Layout: 8 header + IFD0 (2 + 4*12 + 4 = 54) -> make at 62 -> pad -> ExifIFD
        val ifd0Start = 8
        val makeOffset = ifd0Start + 2 + 4 * 12 + 4 // 62
        val exifIfdOffset = makeOffset + makeBytes.size + ((makeOffset + makeBytes.size) % 2)
        val dtOffset = exifIfdOffset + 2 + 2 * 12 + 4
        val offOffset = dtOffset + dtBytes.size

        val buf = ByteBuffer.allocate(offOffset + offBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()); buf.put('I'.code.toByte())
        buf.putShort(42)
        buf.putInt(ifd0Start)

        // IFD0: 4 entries (ImageWidth=256 stripped, Make=271 kept, Orientation=274 stripped, ExifIFD=34665 kept)
        buf.putShort(4)
        buf.putShort(256); buf.putShort(4); buf.putInt(1); buf.putInt(4032)
        buf.putShort(271); buf.putShort(2); buf.putInt(makeBytes.size); buf.putInt(makeOffset)
        buf.putShort(274); buf.putShort(3); buf.putInt(1); buf.putShort(orientation.toShort()); buf.putShort(0)
        buf.putShort(0x8769.toShort()); buf.putShort(4); buf.putInt(1); buf.putInt(exifIfdOffset)
        buf.putInt(0)

        assertEquals(makeOffset, buf.position())
        buf.put(makeBytes)
        while (buf.position() < exifIfdOffset) buf.put(0)

        // Exif IFD: DateTimeOriginal (0x9003) + OffsetTimeOriginal (0x9011)
        buf.putShort(2)
        buf.putShort(0x9003.toShort()); buf.putShort(2); buf.putInt(dtBytes.size); buf.putInt(dtOffset)
        buf.putShort(0x9011.toShort()); buf.putShort(2); buf.putInt(offBytes.size); buf.putInt(offOffset)
        buf.putInt(0)

        assertEquals(dtOffset, buf.position())
        buf.put(dtBytes)
        buf.put(offBytes)
        return buf.array()
    }

    private fun extractJpegApp1Tiff(jpeg: ByteArray): ByteArray? {
        assertTrue(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) break
            val marker = jpeg[pos + 1].toInt() and 0xFF
            pos += 2
            if (marker == 0xD9 || marker == 0xDA) break
            val len = ((jpeg[pos].toInt() and 0xFF) shl 8) or (jpeg[pos + 1].toInt() and 0xFF)
            val payloadStart = pos + 2
            val payloadEnd = pos + len
            if (marker == 0xE1 && payloadEnd <= jpeg.size && len >= 8) {
                val hdr = String(jpeg, payloadStart, 6, Charsets.US_ASCII)
                if (hdr == "Exif\u0000\u0000") {
                    return jpeg.copyOfRange(payloadStart + 6, payloadEnd)
                }
            }
            pos = payloadEnd
        }
        return null
    }

    private data class ParsedTags(
        val make: String?,
        val orientation: Int?,
        val dateTimeOriginal: String?,
        val offsetTimeOriginal: String?,
    )

    private fun readExifTags(tiff: ByteArray): ParsedTags {
        val bb = ByteBuffer.wrap(tiff).order(
            if (tiff[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN,
        )
        var make: String? = null
        var orientation: Int? = null
        var dateOrig: String? = null
        var offsetOrig: String? = null
        var exifPtr: Int? = null

        fun scan(offset: Int) {
            val count = bb.getShort(offset).toInt() and 0xFFFF
            for (i in 0 until count) {
                val at = offset + 2 + i * 12
                val tag = bb.getShort(at).toInt() and 0xFFFF
                val kind = bb.getShort(at + 2).toInt() and 0xFFFF
                val n = bb.getInt(at + 4)
                val str = if (kind == 2) {
                    val ptr = if (n <= 4) at + 8 else bb.getInt(at + 8)
                    String(tiff, ptr, n, Charsets.US_ASCII).trimEnd('\u0000')
                } else {
                    null
                }
                when (tag) {
                    271 -> make = str
                    274 -> orientation = bb.getShort(at + 8).toInt() and 0xFFFF
                    0x8769 -> exifPtr = bb.getInt(at + 8)
                    0x9003 -> dateOrig = str
                    0x9011 -> offsetOrig = str
                }
            }
        }
        scan(bb.getInt(4))
        exifPtr?.let(::scan)
        return ParsedTags(make, orientation, dateOrig, offsetOrig)
    }
}
