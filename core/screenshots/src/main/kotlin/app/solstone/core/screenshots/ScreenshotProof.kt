// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

/**
 * A screenshot is the platform's own record inside the file.
 *
 * AOSP's image exporter writes the Software tag as "Android" plus the build's display
 * string, and does not write a camera Make. A camera photo usually carries Make.
 * The folder and the file name are not a signal.
 */
private val ANDROID_SOFTWARE = Regex("^Android .+")

const val SCREENSHOT_MAX_BYTES = 64L * 1024L * 1024L
const val SCREENSHOT_MAX_PIXELS = 100_000_000L

enum class ShotKind { PNG, JPEG, WEBP }

enum class ShotEnd { REFUSED_TYPE, REFUSED_SIZE, UNREADABLE }

sealed interface ShotRead {
    data class Shot(
        val kind: ShotKind,
        val localDateTime: String?,
        val width: Int,
        val height: Int,
        val offset: String? = null,
    ) : ShotRead

    data class Ended(val kind: ShotKind, val end: ShotEnd) : ShotRead
    data object NotScreenshot : ShotRead
}

fun classifyScreenshot(bytes: ByteArray): ShotRead {
    if (bytes.size > SCREENSHOT_MAX_BYTES) {
        val head = classifyHeader(bytes.copyOf(minOf(bytes.size, 256 * 1024)))
        return if (head is ShotRead.Shot) ShotRead.Ended(head.kind, ShotEnd.REFUSED_SIZE) else ShotRead.NotScreenshot
    }
    return classifyHeader(bytes)
}

private fun classifyHeader(bytes: ByteArray): ShotRead {
    val kind = sniff(bytes) ?: return ShotRead.NotScreenshot
    val tags = when (kind) {
        ShotKind.JPEG -> jpegTags(bytes)
        ShotKind.PNG -> pngTags(bytes)
        ShotKind.WEBP -> webpTags(bytes)
    } ?: return ShotRead.NotScreenshot
    val software = tags.software?.trim().orEmpty()
    if (!ANDROID_SOFTWARE.matches(software)) return ShotRead.NotScreenshot
    if (!tags.make.isNullOrBlank()) return ShotRead.NotScreenshot
    val width = tags.width
    val height = tags.height
    if (width <= 0 || height <= 0) return ShotRead.Ended(kind, ShotEnd.UNREADABLE)
    if (width.toLong() * height.toLong() > SCREENSHOT_MAX_PIXELS) {
        return ShotRead.Ended(kind, ShotEnd.REFUSED_SIZE)
    }
    return ShotRead.Shot(kind, tags.dateTime, width, height, tags.offset)
}

private data class Tags(
    val software: String?,
    val make: String?,
    val dateTime: String?,
    val width: Int,
    val height: Int,
    val offset: String? = null,
)

private fun sniff(bytes: ByteArray): ShotKind? = when {
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> ShotKind.JPEG
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIG) -> ShotKind.PNG
    bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals(RIFF) &&
        bytes.copyOfRange(8, 12).contentEquals(WEBP) -> ShotKind.WEBP
    else -> null
}

private fun jpegTags(bytes: ByteArray): Tags? {
    var software: String? = null
    var make: String? = null
    var date: String? = null
    var offset: String? = null
    var width = 0
    var height = 0
    var i = 2
    while (i + 4 <= bytes.size) {
        if (bytes[i] != 0xFF.toByte()) break
        while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++
        if (i >= bytes.size) break
        val marker = bytes[i].toInt() and 0xFF
        i++
        if (marker == 0xD9 || marker == 0xDA) break
        if (i + 2 > bytes.size) break
        val length = u16(bytes, i)
        if (length < 2 || i + length > bytes.size) break
        val payload = i + 2
        if (marker == 0xE1) {
            val exif = app1Tiff(bytes, payload, i + length)
            if (exif != null) {
                software = software ?: exif.software
                make = make ?: exif.make
                date = date ?: exif.dateTime
                offset = offset ?: exif.offset
            }
        } else if (marker in SOF && height == 0) {
            if (length >= 7) {
                height = u16(bytes, payload + 1)
                width = u16(bytes, payload + 3)
            }
        }
        i += length
    }
    if (software == null && make == null) return null
    return Tags(software, make, date, width, height, offset)
}

private fun pngTags(bytes: ByteArray): Tags? {
    var software: String? = null
    var make: String? = null
    var date: String? = null
    var offset: String? = null
    var width = 0
    var height = 0
    var i = PNG_SIG.size
    while (i + 12 <= bytes.size) {
        val length = u32(bytes, i).toInt()
        if (length < 0 || i + 12 + length > bytes.size) break
        val type = bytes.copyOfRange(i + 4, i + 8).toString(Charsets.US_ASCII)
        val data = bytes.copyOfRange(i + 8, i + 8 + length)
        when (type) {
            "IHDR" -> if (data.size >= 8) {
                width = u32(data, 0).toInt()
                height = u32(data, 4).toInt()
            }
            "eXIf" -> readTiff(data)?.let {
                software = software ?: it.software
                make = make ?: it.make
                date = date ?: it.dateTime
                offset = offset ?: it.offset
            }
            "tEXt" -> textPair(data)?.let { (key, value) -> if (key == "Software") software = software ?: value }
            "iTXt" -> itxt(data)?.let { (key, value) -> if (key == "Software") software = software ?: value }
        }
        i += 12 + length
    }
    if (software == null && make == null) return null
    return Tags(software, make, date, width, height, offset)
}

private fun webpTags(bytes: ByteArray): Tags? {
    var software: String? = null
    var make: String? = null
    var date: String? = null
    var offset: String? = null
    var width = 0
    var height = 0
    var i = 12
    while (i + 8 <= bytes.size) {
        val type = bytes.copyOfRange(i, i + 4).toString(Charsets.US_ASCII)
        val size = u32le(bytes, i + 4).toInt()
        if (size < 0 || i + 8 + size > bytes.size) break
        val data = bytes.copyOfRange(i + 8, i + 8 + size)
        if (type == "EXIF") {
            readTiff(data)?.let {
                software = software ?: it.software
                make = make ?: it.make
                date = date ?: it.dateTime
                offset = offset ?: it.offset
            }
        } else if (type == "VP8X" && data.size >= 10) {
            width = 1 + (data[4].toInt() and 0xFF) + ((data[5].toInt() and 0xFF) shl 8) + ((data[6].toInt() and 0xFF) shl 16)
            height = 1 + (data[7].toInt() and 0xFF) + ((data[8].toInt() and 0xFF) shl 8) + ((data[9].toInt() and 0xFF) shl 16)
        } else if (type == "VP8 " && data.size >= 10 && width == 0) {
            width = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0x3F) shl 8)
            height = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0x3F) shl 8)
        }
        i += 8 + size + (size and 1)
    }
    if (software == null && make == null) return null
    if (width <= 0 || height <= 0) return Tags(software, make, date, 1, 1, offset)
    return Tags(software, make, date, width, height, offset)
}

private data class ExifTags(
    val software: String?,
    val make: String?,
    val dateTime: String?,
    val offset: String? = null,
)

private fun app1Tiff(bytes: ByteArray, start: Int, end: Int): ExifTags? {
    val marker = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
    if (end - start < marker.size + 8) return null
    if (!bytes.copyOfRange(start, start + marker.size).contentEquals(marker)) return null
    return readTiff(bytes.copyOfRange(start + marker.size, end))
}

private fun readTiff(data: ByteArray): ExifTags? {
    val body = if (data.size >= 6 && data.copyOfRange(0, 6).contentEquals("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII))) {
        data.copyOfRange(6, data.size)
    } else {
        data
    }
    if (body.size < 8) return null
    val le = when {
        body[0] == 'I'.code.toByte() && body[1] == 'I'.code.toByte() -> true
        body[0] == 'M'.code.toByte() && body[1] == 'M'.code.toByte() -> false
        else -> return null
    }
    if (u16(body, 2, le) != 42) return null
    val ifd = u32(body, 4, le).toInt()
    val found = linkedMapOf<Int, String>()
    readIfd(body, ifd, le, found, depth = 0)
    return ExifTags(found[0x0131], found[0x010F], found[0x9003] ?: found[0x0132], found[0x9011])
}

private fun readIfd(body: ByteArray, offset: Int, le: Boolean, found: MutableMap<Int, String>, depth: Int) {
    if (depth > 4 || offset < 0 || offset + 2 > body.size) return
    val count = u16(body, offset, le)
    var entry = offset + 2
    for (n in 0 until count) {
        if (entry + 12 > body.size) return
        val tag = u16(body, entry, le)
        val type = u16(body, entry + 2, le)
        val nvals = u32(body, entry + 4, le).toInt()
        if (tag == 0x8769) {
            val next = u32(body, entry + 8, le).toInt()
            readIfd(body, next, le, found, depth + 1)
        } else if (type == 2 && nvals in 1..256 && tag !in found) {
            val unit = 1
            val size = nvals * unit
            val raw = if (size <= 4) {
                body.copyOfRange(entry + 8, entry + 8 + size.coerceAtMost(body.size - (entry + 8)))
            } else {
                val at = u32(body, entry + 8, le).toInt()
                if (at < 0 || at + size > body.size) byteArrayOf() else body.copyOfRange(at, at + size)
            }
            val text = raw.toString(Charsets.UTF_8).substringBefore('\u0000').trim()
            if (text.isNotEmpty()) found[tag] = text
        }
        entry += 12
    }
}

private fun textPair(data: ByteArray): Pair<String, String>? {
    val split = data.indexOf(0.toByte())
    if (split <= 0) return null
    val key = data.copyOfRange(0, split).toString(Charsets.US_ASCII)
    val value = data.copyOfRange(split + 1, data.size).toString(Charsets.UTF_8).trim()
    return key to value
}

private fun itxt(data: ByteArray): Pair<String, String>? {
    val keyEnd = data.indexOf(0.toByte())
    if (keyEnd <= 0 || keyEnd + 1 >= data.size) return null
    if (data[keyEnd + 1] != 0.toByte()) return null
    var i = keyEnd + 3
    var zeros = 0
    while (i < data.size && zeros < 2) {
        if (data[i] == 0.toByte()) zeros++
        i++
    }
    if (zeros < 2 || i > data.size) return null
    val key = data.copyOfRange(0, keyEnd).toString(Charsets.US_ASCII)
    val value = data.copyOfRange(i, data.size).toString(Charsets.UTF_8).trim()
    return key to value
}

private val SOF = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

private fun u16(bytes: ByteArray, offset: Int, le: Boolean = false): Int {
    val a = bytes[offset].toInt() and 0xFF
    val b = bytes[offset + 1].toInt() and 0xFF
    return if (le) a or (b shl 8) else (a shl 8) or b
}

private fun u32(bytes: ByteArray, offset: Int, le: Boolean = false): Long {
    val a = bytes[offset].toLong() and 0xFF
    val b = bytes[offset + 1].toLong() and 0xFF
    val c = bytes[offset + 2].toLong() and 0xFF
    val d = bytes[offset + 3].toLong() and 0xFF
    return if (le) a or (b shl 8) or (c shl 16) or (d shl 24) else (a shl 24) or (b shl 16) or (c shl 8) or d
}

private fun u32le(bytes: ByteArray, offset: Int): Long = u32(bytes, offset, le = true)
