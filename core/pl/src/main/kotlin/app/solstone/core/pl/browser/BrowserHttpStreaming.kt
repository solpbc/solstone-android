// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.pl.LedgerCategory
import app.solstone.core.pl.LiveRoot
import app.solstone.core.pl.MemoryLedger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

fun interface BrowserRequestBodySource {
    fun readChunk(target: ByteArray, offset: Int, length: Int): Int
}

interface BrowserResponseSink {
    fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>)
    fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int)
    fun onTrailers(trailers: List<Pair<String, String>>) {}
    fun onComplete()
    fun onError(cause: Throwable) {}
}

fun formatStreamingHttpRequest(
    method: String,
    path: String,
    headers: List<Pair<String, String>>,
    hasBody: Boolean,
    contentLength: Long? = null,
): ByteArray {
    val out = ByteArrayOutputStream()
    val writer = out.bufferedWriter(Charsets.US_ASCII)
    writer.write("$method $path HTTP/1.1\r\n")

    var hasHost = false
    var hasConnection = false
    var hasContentLength = false
    var hasTransferEncoding = false

    for ((key, value) in headers) {
        val lower = key.lowercase(Locale.ROOT)
        if (lower == "host") hasHost = true
        if (lower == "connection") hasConnection = true
        if (lower == "content-length") hasContentLength = true
        if (lower == "transfer-encoding") hasTransferEncoding = true
        writer.write("$key: $value\r\n")
    }

    if (!hasHost) {
        writer.write("Host: spl.local\r\n")
    }
    if (!hasConnection) {
        writer.write("Connection: close\r\n")
    }
    if (hasBody && !hasContentLength && !hasTransferEncoding) {
        if (contentLength != null) {
            writer.write("Content-Length: $contentLength\r\n")
        } else {
            writer.write("Transfer-Encoding: chunked\r\n")
        }
    }
    writer.write("\r\n")
    writer.flush()
    return out.toByteArray()
}

class ProgressiveBrowserResponseParser(
    private val sink: BrowserResponseSink,
    private val isHeadRequest: Boolean = false,
) : LiveRoot {
    override val category: LedgerCategory = LedgerCategory.HTTP_PARSER_HEADER_TRAILER
    override val capacity: Long
        get() = (headerBuffer.size() + chunkLineBuffer.size() + trailersBuffer.size()).toLong()

    init {
        MemoryLedger.registerRoot(this)
    }

    private enum class State {
        READING_HEADERS,
        BODY_FIXED_LENGTH,
        BODY_CHUNK_SIZE,
        BODY_CHUNK_DATA,
        BODY_CHUNK_CRLF,
        BODY_TRAILERS,
        BODY_CLOSE_DELIMITED,
        BODY_NONE,
        COMPLETED,
    }

    private var state = State.READING_HEADERS
    private val headerBuffer = ByteArrayOutputStream()
    private val chunkLineBuffer = ByteArrayOutputStream()
    private val trailersBuffer = ByteArrayOutputStream()
    private var expectedContentLength: Long = -1L
    private var receivedBodyBytes: Long = 0L
    private var currentStatus: Int = 0
    private var remainingInCurrentChunk: Long = 0L
    private var crlfBytesNeeded: Int = 0
    private val declaredTrailers = mutableSetOf<String>()

    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (state == State.COMPLETED) return
        var pos = offset
        val end = offset + length

        try {
            while (pos < end && state != State.COMPLETED) {
                when (state) {
                    State.READING_HEADERS -> {
                        val byte = data[pos++]
                        headerBuffer.write(byte.toInt())
                        val currentHeaderBytes = headerBuffer.toByteArray()
                        val crlfIndex = findHeaderEnd(currentHeaderBytes)
                        if (crlfIndex != -1) {
                            val headerText = String(currentHeaderBytes, 0, crlfIndex, Charsets.US_ASCII)
                            processHeaders(headerText)
                            val extraStart = crlfIndex + 4
                            val extraBytesCount = currentHeaderBytes.size - extraStart
                            if (extraBytesCount > 0) {
                                feed(currentHeaderBytes, extraStart, extraBytesCount)
                            }
                        } else if (headerBuffer.size() > 64 * 1024) {
                            throw IOException("Response header fields too large")
                        }
                    }
                    State.BODY_FIXED_LENGTH -> {
                        val remainingInChunk = end - pos
                        val needed = expectedContentLength - receivedBodyBytes
                        val toTake = minOf(remainingInChunk.toLong(), needed).toInt()
                        if (toTake > 0) {
                            sink.onBodyChunk(data, pos, toTake)
                            receivedBodyBytes += toTake
                            pos += toTake
                        }
                        if (receivedBodyBytes >= expectedContentLength) {
                            state = State.BODY_NONE
                        }
                    }
                    State.BODY_CHUNK_SIZE -> {
                        val b = data[pos++]
                        if (b == '\n'.code.toByte()) {
                            val line = chunkLineBuffer.toString(Charsets.US_ASCII.name()).trim()
                            chunkLineBuffer.reset()
                            val hexPart = line.split(";", limit = 2)[0].trim()
                            val chunkSize = hexPart.toLongOrNull(16) ?: throw IOException("Malformed chunk size")
                            if (chunkSize < 0) throw IOException("Negative chunk size")
                            if (chunkSize == 0L) {
                                state = State.BODY_TRAILERS
                            } else {
                                remainingInCurrentChunk = chunkSize
                                state = State.BODY_CHUNK_DATA
                            }
                        } else if (b != '\r'.code.toByte()) {
                            chunkLineBuffer.write(b.toInt())
                            if (chunkLineBuffer.size() > 1024) {
                                throw IOException("Chunk size line too large")
                            }
                        }
                    }
                    State.BODY_CHUNK_DATA -> {
                        val remainingInFeed = end - pos
                        val toTake = minOf(remainingInFeed.toLong(), remainingInCurrentChunk).toInt()
                        if (toTake > 0) {
                            sink.onBodyChunk(data, pos, toTake)
                            receivedBodyBytes += toTake
                            remainingInCurrentChunk -= toTake
                            pos += toTake
                        }
                        if (remainingInCurrentChunk == 0L) {
                            state = State.BODY_CHUNK_CRLF
                            crlfBytesNeeded = 2
                        }
                    }
                    State.BODY_CHUNK_CRLF -> {
                        val b = data[pos++]
                        if (b == '\r'.code.toByte() || b == '\n'.code.toByte()) {
                            crlfBytesNeeded--
                            if (crlfBytesNeeded <= 0 || b == '\n'.code.toByte()) {
                                state = State.BODY_CHUNK_SIZE
                            }
                        } else {
                            throw IOException("Malformed chunk delimiter")
                        }
                    }
                    State.BODY_TRAILERS -> {
                        val b = data[pos++]
                        trailersBuffer.write(b.toInt())
                        val bytes = trailersBuffer.toByteArray()
                        if (bytes.size >= 2 && bytes[bytes.size - 2] == '\r'.code.toByte() && bytes[bytes.size - 1] == '\n'.code.toByte()) {
                            if (bytes.size == 2 || (bytes.size >= 4 && bytes[bytes.size - 4] == '\r'.code.toByte() && bytes[bytes.size - 3] == '\n'.code.toByte())) {
                                processTrailers(String(bytes, Charsets.US_ASCII))
                                state = State.BODY_NONE
                            }
                        }
                    }
                    State.BODY_CLOSE_DELIMITED -> {
                        val remainingInChunk = end - pos
                        if (remainingInChunk > 0) {
                            sink.onBodyChunk(data, pos, remainingInChunk)
                            receivedBodyBytes += remainingInChunk
                            pos += remainingInChunk
                        }
                    }
                    State.BODY_NONE, State.COMPLETED -> {
                        pos = end
                    }
                }
            }
        } catch (e: Exception) {
            MemoryLedger.unregisterRoot(this)
            sink.onError(e)
            throw e
        }
    }

    fun onMuxClose(successful: Boolean) {
        MemoryLedger.unregisterRoot(this)
        if (!successful) {
            sink.onError(IOException("Stream terminated with error"))
            return
        }
        if (state == State.BODY_FIXED_LENGTH && receivedBodyBytes < expectedContentLength) {
            sink.onError(IOException("Premature stream termination"))
            return
        }
        state = State.COMPLETED
        sink.onComplete()
    }

    private fun processHeaders(headerText: String) {
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) throw IOException("Empty response header")
        val statusLine = lines[0]
        val statusParts = statusLine.split(" ", limit = 3)
        if (statusParts.size < 2) throw IOException("Invalid status line")
        val status = statusParts[1].toIntOrNull() ?: throw IOException("Invalid status code")
        val reason = if (statusParts.size > 2) statusParts[2] else ""
        currentStatus = status

        if (status == 101) {
            throw IOException("101 Switching Protocols rejected")
        }

        val parsedHeaders = mutableListOf<Pair<String, String>>()
        var transferEncoding: String? = null
        var contentLengthHeader: String? = null

        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) continue
            val key = line.substring(0, colonIndex).trim()
            val value = line.substring(colonIndex + 1).trim()
            val lower = key.lowercase(Locale.ROOT)

            if (lower == "transfer-encoding") transferEncoding = value.lowercase(Locale.ROOT)
            if (lower == "content-length") contentLengthHeader = value
            if (lower == "trailer") {
                value.split(",").forEach { declaredTrailers.add(it.trim().lowercase(Locale.ROOT)) }
            }

            parsedHeaders.add(key to value)
        }

        // Interim 1xx (such as 103 Early Hints)
        if (status in 100..199) {
            if (status == 103) {
                sink.onStatusAndHeaders(status, reason, parsedHeaders)
            }
            headerBuffer.reset()
            state = State.READING_HEADERS
            return
        }

        // HEAD request handling: Preserve Content-Length in header forwarding, but zero body bytes to sink
        if (isHeadRequest) {
            state = State.BODY_NONE
            val filtered = parsedHeaders.filterNot { it.first.equals("transfer-encoding", ignoreCase = true) }
            sink.onStatusAndHeaders(status, reason, filtered)
            return
        }

        // 204 No Content handling: Reject illegal body framing
        if (status == 204) {
            if (contentLengthHeader != null && contentLengthHeader.toLongOrNull() != 0L) {
                throw IOException("Illegal Content-Length on 204")
            }
            if (transferEncoding?.contains("chunked") == true) {
                throw IOException("Illegal Transfer-Encoding on 204")
            }
            state = State.BODY_NONE
            val filtered = parsedHeaders.filterNot {
                it.first.equals("content-length", ignoreCase = true) || it.first.equals("transfer-encoding", ignoreCase = true)
            }
            sink.onStatusAndHeaders(status, reason, filtered)
            return
        }

        // 304 Not Modified: Retain Content-Length, filter Transfer-Encoding, zero body bytes
        if (status == 304) {
            state = State.BODY_NONE
            val filtered = parsedHeaders.filterNot { it.first.equals("transfer-encoding", ignoreCase = true) }
            sink.onStatusAndHeaders(status, reason, filtered)
            return
        }

        // 205 Reset Content: Strip CL/TE, emit Content-Length: 0, wait for Mux CLOSE to complete
        if (status == 205) {
            state = State.BODY_NONE
            val filtered = parsedHeaders.filterNot {
                it.first.equals("content-length", ignoreCase = true) || it.first.equals("transfer-encoding", ignoreCase = true)
            }.toMutableList()
            filtered.add("Content-Length" to "0")
            sink.onStatusAndHeaders(status, reason, filtered)
            return
        }

        if (transferEncoding?.contains("chunked") == true) {
            state = State.BODY_CHUNK_SIZE
            val filtered = parsedHeaders.filterNot { it.first.equals("content-length", ignoreCase = true) }
            sink.onStatusAndHeaders(status, reason, filtered)
            return
        }

        if (contentLengthHeader != null) {
            expectedContentLength = contentLengthHeader.toLongOrNull() ?: throw IOException("Invalid Content-Length")
            if (expectedContentLength < 0) throw IOException("Negative Content-Length")
            state = if (expectedContentLength == 0L) State.BODY_NONE else State.BODY_FIXED_LENGTH
        } else {
            state = State.BODY_CLOSE_DELIMITED
        }

        sink.onStatusAndHeaders(status, reason, parsedHeaders)
    }

    private fun processTrailers(rawTrailers: String) {
        val lines = rawTrailers.split("\r\n")
        val trailers = mutableListOf<Pair<String, String>>()
        for (line in lines) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            val lower = key.lowercase(Locale.ROOT)
            if (lower in declaredTrailers || lower == "digest") {
                trailers.add(key to value)
            }
        }
        if (trailers.isNotEmpty()) {
            sink.onTrailers(trailers)
        }
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }
}
