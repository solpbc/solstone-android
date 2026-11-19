// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import java.io.ByteArrayOutputStream

/**
 * Carries EXIF metadata (capture time, timezone offset, camera, GPS) from a HEIC/HEIF photo
 * into a freshly encoded upright JPEG.
 *
 * Android's [android.graphics.ImageDecoder] applies the HEIF's rotation (`irot` / EXIF
 * Orientation) to the decoded pixels, and [android.graphics.Bitmap.compress] writes a bare JPEG
 * with no EXIF segment. Extracting the ISO BMFF `Exif` item, rebuilding a standalone TIFF block
 * without IFD0 storage tags or Orientation (tag 0x0112, which would double-rotate upright pixels),
 * and injecting it as a JPEG APP1 segment preserves capture time and zone across conversion.
 */
object HeicExif {
    private const val MAX_IFD_ENTRIES = 1_000
    private const val MAX_IFD_DEPTH = 3
    private const val MAX_ILOC_ITEMS = 10_000
    private const val MAX_APP1_PAYLOAD = 65_533 - 6 // 16-bit segment length minus 2-byte length and "Exif\0\0"

    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_GPS_IFD = 0x8825
    private const val TAG_INTEROP_IFD = 0xA005

    /**
     * IFD0 tags describing pixel storage, thumbnail strips, or container-carried metadata
     * (XMP, ICC) plus Orientation (already baked into the decoded upright bitmap).
     */
    private val STRIPPED_IFD0_TAGS = setOf(
        254, 255, 256, 257, 258, 259, 262, 263, 266, 273, TAG_ORIENTATION, 277, 278, 279,
        280, 281, 284, 288, 289, 290, 291, 292, 293, 317, 318, 319, 320, 321, 322, 323, 324,
        325, 330, 332, 338, 339, 340, 341, 512, 513, 514, 515, 517, 518, 519, 520, 521, 529,
        530, 531, 532, 700, 34_675,
    )

    /**
     * Returns [jpegBytes] with the HEIF's EXIF injected as an APP1 segment, or [jpegBytes]
     * unchanged if the HEIF carries no readable EXIF or the JPEG header is unexpected.
     */
    fun carryToUprightJpeg(heifBytes: ByteArray, jpegBytes: ByteArray): ByteArray {
        val rawTiff = extractHeifExifTiff(heifBytes) ?: return jpegBytes
        val cleanTiff = rebuildUprightExifTiff(rawTiff) ?: return jpegBytes
        return injectExifApp1(jpegBytes, cleanTiff) ?: jpegBytes
    }

    /** Extract the raw TIFF bytes from a HEIF/AVIF container's `Exif` item. */
    fun extractHeifExifTiff(bytes: ByteArray): ByteArray? {
        if (bytes.size < 12 || !matchesTag(bytes, 4, "ftyp")) return null
        val meta = boxes(bytes, 0, bytes.size).firstOrNull { it.type == "meta" } ?: return null
        if (meta.start + 4 > meta.end) return null
        val children = boxes(bytes, meta.start + 4, meta.end)
        val iinf = children.firstOrNull { it.type == "iinf" } ?: return null
        val iloc = children.firstOrNull { it.type == "iloc" } ?: return null
        val idat = children.firstOrNull { it.type == "idat" }

        val exifItemId = findExifItemId(bytes, iinf) ?: return null
        val payload = readIlocItem(bytes, iloc, idat, exifItemId) ?: return null
        if (payload.size < 4) return null
        val tiffOffset = readBe(payload, 0, 4)?.toInt() ?: return null
        if (tiffOffset < 0 || 4 + tiffOffset >= payload.size) return null
        return payload.copyOfRange(4 + tiffOffset, payload.size)
    }

    /** Rebuild a standalone EXIF TIFF block with IFD0 storage tags and Orientation removed. */
    fun rebuildUprightExifTiff(source: ByteArray): ByteArray? {
        if (source.size < 8) return null
        val little = when {
            source[0] == 'I'.code.toByte() && source[1] == 'I'.code.toByte() -> true
            source[0] == 'M'.code.toByte() && source[1] == 'M'.code.toByte() -> false
            else -> return null
        }
        val reader = TiffReader(source, little)
        if (reader.u16(2) != 42) return null
        val firstIfd = reader.u32(4)?.toInt() ?: return null
        val ifd0 = readIfd(reader, firstIfd, 0, mutableSetOf()) ?: return null
        if (ifd0.entries.isEmpty()) return null

        val out = ByteArrayOutputStream(source.size)
        if (little) {
            out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0))
        } else {
            out.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
        }
        out.write(ByteArray(4)) // IFD0 offset placeholder
        val bytes = mutableListOf<Byte>()
        for (b in out.toByteArray()) bytes.add(b)
        val ifd0Start = writeIfd(bytes, ifd0, little) ?: return null
        putU32(bytes, 4, ifd0Start, little)
        return bytes.toByteArray()
    }

    /** Inject a JPEG APP1 `Exif\0\0` segment immediately after SOI (or after a leading JFIF APP0). */
    fun injectExifApp1(jpeg: ByteArray, tiff: ByteArray): ByteArray? {
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        if (tiff.isEmpty() || tiff.size > MAX_APP1_PAYLOAD) return null

        var insertAt = 2
        if (jpeg.size >= 6 && jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            val app0Len = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            if (app0Len >= 2 && 4 + app0Len <= jpeg.size) {
                insertAt = 4 + app0Len
            }
        }

        val segmentLen = 2 + 6 + tiff.size
        val result = ByteArray(jpeg.size + 2 + segmentLen)
        System.arraycopy(jpeg, 0, result, 0, insertAt)
        var pos = insertAt
        result[pos++] = 0xFF.toByte()
        result[pos++] = 0xE1.toByte()
        result[pos++] = ((segmentLen ushr 8) and 0xFF).toByte()
        result[pos++] = (segmentLen and 0xFF).toByte()
        result[pos++] = 'E'.code.toByte()
        result[pos++] = 'x'.code.toByte()
        result[pos++] = 'i'.code.toByte()
        result[pos++] = 'f'.code.toByte()
        result[pos++] = 0
        result[pos++] = 0
        System.arraycopy(tiff, 0, result, pos, tiff.size)
        pos += tiff.size
        System.arraycopy(jpeg, insertAt, result, pos, jpeg.size - insertAt)
        return result
    }

    // ---- ISO BMFF box parsing ----

    private data class Box(val type: String, val start: Int, val end: Int)

    private fun boxes(bytes: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to) {
            val size32 = readBe(bytes, at, 4) ?: break
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            val header: Int
            val size: Long
            when (size32) {
                0L -> {
                    header = 8
                    size = (to - at).toLong()
                }
                1L -> {
                    if (at + 16 > to) break
                    header = 16
                    size = readBe(bytes, at + 8, 8) ?: break
                }
                else -> {
                    header = 8
                    size = size32
                }
            }
            if (size < header) break
            val endLong = at.toLong() + size
            if (endLong > to.toLong()) break
            val end = endLong.toInt()
            out.add(Box(type, at + header, end))
            at = end
        }
        return out
    }

    private fun findExifItemId(bytes: ByteArray, iinf: Box): Long? {
        if (iinf.start + 4 > iinf.end) return null
        val version = bytes[iinf.start].toInt() and 0xFF
        val entriesAt = iinf.start + 4 + if (version == 0) 2 else 4
        if (entriesAt > iinf.end) return null
        for (infe in boxes(bytes, entriesAt, iinf.end)) {
            if (infe.type != "infe" || infe.start + 4 > infe.end) continue
            val infeVersion = bytes[infe.start].toInt() and 0xFF
            val id: Long
            val typeAt: Int
            when (infeVersion) {
                2 -> {
                    id = readBe(bytes, infe.start + 4, 2) ?: continue
                    typeAt = infe.start + 8
                }
                3 -> {
                    id = readBe(bytes, infe.start + 4, 4) ?: continue
                    typeAt = infe.start + 10
                }
                else -> continue
            }
            if (typeAt + 4 <= infe.end && matchesTag(bytes, typeAt, "Exif")) {
                return id
            }
        }
        return null
    }

    private fun readIlocItem(bytes: ByteArray, iloc: Box, idat: Box?, targetId: Long): ByteArray? {
        if (iloc.start + 6 > iloc.end) return null
        val version = bytes[iloc.start].toInt() and 0xFF
        var at = iloc.start + 4
        val sizes = bytes[at].toInt() and 0xFF
        val more = bytes[at + 1].toInt() and 0xFF
        val offsetSize = sizes ushr 4
        val lengthSize = sizes and 0x0F
        val baseSize = more ushr 4
        val indexSize = if (version >= 1) (more and 0x0F) else 0
        at += 2
        val idSize = if (version < 2) 2 else 4
        val count = readBe(bytes, at, idSize) ?: return null
        at += idSize
        val limit = minOf(count, MAX_ILOC_ITEMS.toLong()).toInt()
        for (i in 0 until limit) {
            val id = readBe(bytes, at, idSize) ?: return null
            at += idSize
            val method = if (version >= 1) {
                val m = (readBe(bytes, at, 2) ?: return null) and 0x0F
                at += 2
                m.toInt()
            } else {
                0
            }
            at += 2 // data_reference_index
            val base = if (baseSize > 0) (readBe(bytes, at, baseSize) ?: return null) else 0L
            at += baseSize
            val extentsCount = (readBe(bytes, at, 2) ?: return null).toInt()
            at += 2
            val extents = mutableListOf<Pair<Long, Long>>()
            for (e in 0 until extentsCount) {
                at += indexSize
                val offset = if (offsetSize > 0) (readBe(bytes, at, offsetSize) ?: return null) else 0L
                at += offsetSize
                val length = if (lengthSize > 0) (readBe(bytes, at, lengthSize) ?: return null) else 0L
                at += lengthSize
                extents.add(offset to length)
            }
            if (id != targetId) continue
            if (method != 0 && method != 1) return null
            val origin = when (method) {
                0 -> 0L
                1 -> idat?.start?.toLong() ?: return null
                else -> return null
            }
            val limitEnd = when (method) {
                0 -> bytes.size.toLong()
                1 -> idat?.end?.toLong() ?: return null
                else -> return null
            }
            val out = ByteArrayOutputStream()
            for ((offset, length) in extents) {
                if (length <= 0L) continue
                val startLong = origin + base + offset
                val endLong = startLong + length
                if (startLong < 0L || endLong > limitEnd || endLong < startLong) return null
                out.write(bytes, startLong.toInt(), length.toInt())
            }
            return out.toByteArray().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun readBe(bytes: ByteArray, at: Int, width: Int): Long? {
        if (width < 0 || at < 0 || at + width > bytes.size) return null
        var acc = 0L
        for (i in 0 until width) {
            acc = (acc shl 8) or (bytes[at + i].toLong() and 0xFFL)
        }
        return acc
    }

    private fun matchesTag(bytes: ByteArray, at: Int, tag: String): Boolean {
        if (at < 0 || at + 4 > bytes.size) return false
        for (i in 0 until 4) {
            if (bytes[at + i] != tag[i].code.toByte()) return false
        }
        return true
    }

    // ---- TIFF IFD cleaner & writer ----

    private class TiffReader(val bytes: ByteArray, val little: Boolean) {
        fun u16(at: Int): Int? {
            if (at < 0 || at + 2 > bytes.size) return null
            val b0 = bytes[at].toInt() and 0xFF
            val b1 = bytes[at + 1].toInt() and 0xFF
            return if (little) (b0 or (b1 shl 8)) else ((b0 shl 8) or b1)
        }

        fun u32(at: Int): Long? {
            if (at < 0 || at + 4 > bytes.size) return null
            val b0 = bytes[at].toLong() and 0xFFL
            val b1 = bytes[at + 1].toLong() and 0xFFL
            val b2 = bytes[at + 2].toLong() and 0xFFL
            val b3 = bytes[at + 3].toLong() and 0xFFL
            return if (little) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }
    }

    private data class IfdEntry(
        val tag: Int,
        val kind: Int,
        val count: Long,
        val value: ByteArray,
        val child: Ifd?,
    )

    private data class Ifd(val entries: List<IfdEntry>)

    private fun typeSize(kind: Int): Int? = when (kind) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12 -> 8
        else -> null
    }

    private fun childTags(depth: Int): Set<Int> = when (depth) {
        0 -> setOf(TAG_EXIF_IFD, TAG_GPS_IFD)
        1 -> setOf(TAG_INTEROP_IFD)
        else -> emptySet()
    }

    private fun readIfd(reader: TiffReader, offset: Int, depth: Int, seen: MutableSet<Int>): Ifd? {
        if (depth > MAX_IFD_DEPTH || offset < 0 || !seen.add(offset)) return null
        val count = reader.u16(offset) ?: return null
        if (count > MAX_IFD_ENTRIES) return null
        val entries = mutableListOf<IfdEntry>()
        for (index in 0 until count) {
            val at = offset + 2 + index * 12
            val tag = reader.u16(at) ?: return null
            val kind = reader.u16(at + 2) ?: return null
            val n = reader.u32(at + 4) ?: return null
            if (depth == 0 && tag in STRIPPED_IFD0_TAGS) continue
            if (tag in childTags(depth)) {
                val target = reader.u32(at + 8)?.toInt()
                if (target != null) {
                    val child = readIfd(reader, target, depth + 1, seen)
                    if (child != null && child.entries.isNotEmpty()) {
                        entries.add(IfdEntry(tag, 4, 1L, ByteArray(0), child))
                    }
                }
                continue
            }
            val unit = typeSize(kind) ?: continue
            val byteLenLong = unit.toLong() * n
            if (byteLenLong < 0L || byteLenLong > reader.bytes.size.toLong()) continue
            val byteLen = byteLenLong.toInt()
            val value = if (byteLen <= 4) {
                if (at + 8 + byteLen > reader.bytes.size) continue
                reader.bytes.copyOfRange(at + 8, at + 8 + byteLen)
            } else {
                val ptr = reader.u32(at + 8)?.toInt() ?: continue
                if (ptr < 0 || ptr + byteLen > reader.bytes.size) continue
                reader.bytes.copyOfRange(ptr, ptr + byteLen)
            }
            entries.add(IfdEntry(tag, kind, n, value, null))
        }
        entries.sortBy { it.tag }
        return Ifd(entries)
    }

    private fun writeIfd(out: MutableList<Byte>, ifd: Ifd, little: Boolean): Int? {
        if (out.size % 2 == 1) out.add(0)
        val start = out.size
        repeat(2 + ifd.entries.size * 12 + 4) { out.add(0) }
        putU16(out, start, ifd.entries.size, little)
        for ((index, entry) in ifd.entries.withIndex()) {
            val at = start + 2 + index * 12
            putU16(out, at, entry.tag, little)
            putU16(out, at + 2, entry.kind, little)
            putU32(out, at + 4, entry.count.toInt(), little)
            val child = entry.child
            if (child != null) {
                val childStart = writeIfd(out, child, little) ?: return null
                putU32(out, at + 8, childStart, little)
            } else if (entry.value.size <= 4) {
                for (i in entry.value.indices) {
                    out[at + 8 + i] = entry.value[i]
                }
            } else {
                if (out.size % 2 == 1) out.add(0)
                val dataOffset = out.size
                for (b in entry.value) out.add(b)
                putU32(out, at + 8, dataOffset, little)
            }
        }
        return start
    }

    private fun putU16(out: MutableList<Byte>, at: Int, value: Int, little: Boolean) {
        if (little) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value ushr 8) and 0xFF).toByte()
        } else {
            out[at] = ((value ushr 8) and 0xFF).toByte()
            out[at + 1] = (value and 0xFF).toByte()
        }
    }

    private fun putU32(out: MutableList<Byte>, at: Int, value: Int, little: Boolean) {
        if (little) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value ushr 8) and 0xFF).toByte()
            out[at + 2] = ((value ushr 16) and 0xFF).toByte()
            out[at + 3] = ((value ushr 24) and 0xFF).toByte()
        } else {
            out[at] = ((value ushr 24) and 0xFF).toByte()
            out[at + 1] = ((value ushr 16) and 0xFF).toByte()
            out[at + 2] = ((value ushr 8) and 0xFF).toByte()
            out[at + 3] = (value and 0xFF).toByte()
        }
    }
}
