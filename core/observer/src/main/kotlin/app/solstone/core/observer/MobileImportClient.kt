// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.StreamingBody
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.IOException
import java.security.SecureRandom

sealed interface MobileImportItem {
    val clientItemId: String
}

class MobileImportFile(
    override val clientItemId: String,
    val filename: String,
    val mediaType: String,
    val length: Long,
    val source: MobileImportSource,
) : MobileImportItem

data class MobileImportText(
    override val clientItemId: String,
    val text: String,
) : MobileImportItem

interface MobileImportSource {
    fun read(target: ByteArray, offset: Int, length: Int): Int
    fun close()
}

sealed interface MobileImportOutcome {
    data class Saved(val path: String, val timestamp: String) : MobileImportOutcome
    data object Delivered : MobileImportOutcome
    data object AuthStop : MobileImportOutcome
    data object Retry : MobileImportOutcome
    data class NeedsAttention(val error: String, val reasonCode: String?, val kind: String?) : MobileImportOutcome
}

private const val SAVE_PATH = "/app/import/api/save"
private const val START_PATH = "/app/import/api/start"
private const val IMPORTED_VIA = "mobile_share"
private const val GENERIC_REFUSAL = "your journal couldn't accept this."
private const val HEX_DIGITS = "0123456789abcdef"

class MobileImportClient(private val http: PlHttpClient) {
    fun save(item: MobileImportItem): MobileImportOutcome {
        val boundary = generateBoundary()
        val body = buildStreamingBody(boundary, item)
        return try {
            val response = http.requestStreaming(
                method = "POST",
                path = SAVE_PATH,
                headers = mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                body = body,
            )
            classifyMobileImportResponse(
                operation = MobileImportOperation.SAVE,
                httpStatus = response.status,
                body = response.body,
            )
        } catch (_: IOException) {
            MobileImportOutcome.Retry
        } finally {
            body.close()
        }
    }

    fun start(path: String, timestamp: String): MobileImportOutcome {
        val requestBody = toJson(linkedMapOf("path" to path, "timestamp" to timestamp))
            .toByteArray(Charsets.UTF_8)
        return try {
            val response = http.request(
                method = "POST",
                path = START_PATH,
                headers = mapOf("Content-Type" to "application/json"),
                body = requestBody,
            )
            classifyMobileImportResponse(
                operation = MobileImportOperation.START,
                httpStatus = response.status,
                body = response.body,
            )
        } catch (_: IOException) {
            MobileImportOutcome.Retry
        }
    }

    private fun generateBoundary(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        val chars = CharArray(32)
        for (i in 0 until 16) {
            val b = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[b ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[b and 0x0F]
        }
        return String(chars)
    }

    private fun buildStreamingBody(boundary: String, item: MobileImportItem): StreamingBody {
        val prefix: ByteArray
        val payloadLength: Long
        val source: MobileImportSource

        when (item) {
            is MobileImportFile -> {
                val sanitizedFilename = sanitizeFilename(item.filename)
                val sanitizedMediaType = sanitizeMediaType(item.mediaType)
                prefix = buildString {
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"client_item_id\"\r\n\r\n")
                    append(item.clientItemId).append("\r\n")
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"imported_via\"\r\n\r\n")
                    append(IMPORTED_VIA).append("\r\n")
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(sanitizedFilename).append("\"\r\n")
                    if (sanitizedMediaType.isNotEmpty()) {
                        append("Content-Type: ").append(sanitizedMediaType).append("\r\n")
                    }
                    append("\r\n")
                }.toByteArray(Charsets.UTF_8)
                payloadLength = item.length
                source = item.source
            }
            is MobileImportText -> {
                prefix = buildString {
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"client_item_id\"\r\n\r\n")
                    append(item.clientItemId).append("\r\n")
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"imported_via\"\r\n\r\n")
                    append(IMPORTED_VIA).append("\r\n")
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"text\"\r\n\r\n")
                }.toByteArray(Charsets.UTF_8)
                val textBytes = item.text.toByteArray(Charsets.UTF_8)
                payloadLength = textBytes.size.toLong()
                source = ByteArraySource(textBytes)
            }
        }

        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return MobileImportStreamingBody(prefix, payloadLength, source, suffix)
    }

    companion object {
        private val secureRandom = SecureRandom()
    }
}

private class ByteArraySource(private val bytes: ByteArray) : MobileImportSource {
    private var offset = 0

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (this.offset >= bytes.size) return -1
        val toCopy = minOf(length, bytes.size - this.offset)
        System.arraycopy(bytes, this.offset, target, offset, toCopy)
        this.offset += toCopy
        return toCopy
    }

    override fun close() {}
}

private class MobileImportStreamingBody(
    private val prefix: ByteArray,
    private val payloadLength: Long,
    private val source: MobileImportSource,
    private val suffix: ByteArray,
) : StreamingBody {
    override val contentLength: Long = prefix.size.toLong() + payloadLength + suffix.size.toLong()

    private var prefixOffset = 0
    private var payloadRead = 0L
    private var suffixOffset = 0
    private var closed = false

    override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("StreamingBody is closed")
        if (length <= 0) return 0
        var written = 0
        var currentOffset = offset
        var remainingRoom = length

        if (prefixOffset < prefix.size) {
            val toCopy = minOf(remainingRoom, prefix.size - prefixOffset)
            System.arraycopy(prefix, prefixOffset, target, currentOffset, toCopy)
            prefixOffset += toCopy
            currentOffset += toCopy
            remainingRoom -= toCopy
            written += toCopy
            if (remainingRoom == 0) return written
        }

        if (payloadLength > 0 && payloadRead < payloadLength) {
            val payloadRemaining = payloadLength - payloadRead
            val want = if (payloadRemaining > remainingRoom.toLong()) remainingRoom else payloadRemaining.toInt()
            if (want > 0) {
                val n = source.read(target, currentOffset, want)
                if (n <= 0 || n > want) {
                    throw IOException("Source read unexpected count $n (want $want, remaining $payloadRemaining)")
                }
                payloadRead += n
                currentOffset += n
                remainingRoom -= n
                written += n
                if (n < want || remainingRoom == 0) {
                    return written
                }
            }
        }

        if (payloadRead == payloadLength && suffixOffset < suffix.size) {
            val toCopy = minOf(remainingRoom, suffix.size - suffixOffset)
            System.arraycopy(suffix, suffixOffset, target, currentOffset, toCopy)
            suffixOffset += toCopy
            currentOffset += toCopy
            remainingRoom -= toCopy
            written += toCopy
        }

        return if (written > 0) written else -1
    }

    override fun close() {
        if (!closed) {
            closed = true
            source.close()
        }
    }
}

private fun sanitizeFilename(rawFilename: String): String {
    val lastComponent = rawFilename.split('/', '\\').last()
    val filtered = buildString(lastComponent.length) {
        for (ch in lastComponent) {
            if (ch != '"' && ch != '\\' && ch != '\r' && ch != '\n') {
                append(ch)
            }
        }
    }
    val dotIndex = filtered.lastIndexOf('.')
    val (initialStem, initialExt) = if (dotIndex >= 0) {
        filtered.substring(0, dotIndex) to filtered.substring(dotIndex)
    } else {
        filtered to ""
    }
    val stem = if (initialStem.isEmpty()) "file" else initialStem
    val ext = initialExt

    val stemBytes = stem.toByteArray(Charsets.UTF_8).size
    val extBytes = ext.toByteArray(Charsets.UTF_8).size

    if (stemBytes + extBytes <= 128) {
        return stem + ext
    }

    if (extBytes <= 128) {
        var offset = 0
        val codePointOffsets = mutableListOf<Int>()
        while (offset < stem.length) {
            codePointOffsets.add(offset)
            offset += Character.charCount(stem.codePointAt(offset))
        }
        codePointOffsets.add(stem.length)

        var count = codePointOffsets.size - 1
        while (count > 0) {
            count--
            val candidate = stem.substring(0, codePointOffsets[count])
            if (candidate.toByteArray(Charsets.UTF_8).size + extBytes <= 128) {
                return candidate + ext
            }
        }
        return ext
    }

    var maxValidLen = 0
    var offset = 0
    while (offset < ext.length) {
        val cp = ext.codePointAt(offset)
        val charCount = Character.charCount(cp)
        val candidate = ext.substring(0, offset + charCount)
        if (candidate.toByteArray(Charsets.UTF_8).size > 128) {
            break
        }
        maxValidLen = offset + charCount
        offset += charCount
    }
    return ext.substring(0, maxValidLen)
}

private fun sanitizeMediaType(mediaType: String): String = buildString(mediaType.length) {
    for (ch in mediaType) {
        if (ch != '\r' && ch != '\n') {
            append(ch)
        }
    }
}

internal enum class MobileImportOperation { SAVE, START }

internal fun classifyMobileImportResponse(
    operation: MobileImportOperation,
    httpStatus: Int,
    body: ByteArray,
): MobileImportOutcome {
    if (httpStatus == 401 || httpStatus == 403) {
        return MobileImportOutcome.AuthStop
    }
    if (httpStatus in 500..599) {
        return MobileImportOutcome.Retry
    }
    if (httpStatus == 200) {
        val root = try {
            parseJson(body.toString(Charsets.UTF_8)) as? Map<*, *>
        } catch (_: IllegalArgumentException) {
            null
        } ?: return MobileImportOutcome.Retry

        return when (operation) {
            MobileImportOperation.SAVE -> {
                val status = root["status"] as? String
                val path = root["path"] as? String
                val timestamp = root["timestamp"] as? String
                if (status == "staged" && path != null && path.isNotEmpty() && timestamp != null && timestamp.isNotEmpty()) {
                    MobileImportOutcome.Saved(path, timestamp)
                } else {
                    MobileImportOutcome.Retry
                }
            }
            MobileImportOperation.START -> {
                val status = root["status"] as? String
                val taskId = root["task_id"] as? String
                val importId = root["import_id"] as? String
                if (status == "ok" && taskId != null && taskId.isNotEmpty() && importId != null && importId.isNotEmpty()) {
                    MobileImportOutcome.Delivered
                } else {
                    MobileImportOutcome.Retry
                }
            }
        }
    }
    if (httpStatus in 200..299) {
        return MobileImportOutcome.Retry
    }

    val root = try {
        parseJson(body.toString(Charsets.UTF_8)) as? Map<*, *>
    } catch (_: IllegalArgumentException) {
        null
    } ?: return MobileImportOutcome.NeedsAttention(GENERIC_REFUSAL, null, null)

    if (httpStatus == 400 && root["reason_code"] == "invalid_operation_for_state" && root["kind"] == "already_imported") {
        return MobileImportOutcome.Delivered
    }

    val error = root["error"] as? String
    val reasonCode = root["reason_code"] as? String
    if (error != null && reasonCode != null) {
        val kind = root["kind"] as? String
        return MobileImportOutcome.NeedsAttention(error, reasonCode, kind)
    }

    return MobileImportOutcome.NeedsAttention(GENERIC_REFUSAL, null, null)
}
