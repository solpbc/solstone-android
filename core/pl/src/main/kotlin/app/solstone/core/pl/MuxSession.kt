// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.ProgressiveBrowserResponseParser
import app.solstone.core.pl.browser.formatStreamingHttpRequest
import app.solstone.core.pl.browser.parseBrowserHttpResponse

class MuxSession(
    private val duplex: ByteDuplex,
    private val observer: PlStreamObserver? = null,
    private val initialReceiveWindow: Int = INITIAL_RECEIVE_WINDOW,
    initialStreamId: Int = 1,
) : Closeable, LiveRoot {
    override val category: LedgerCategory = LedgerCategory.MUX_HEADER_PAYLOAD
    override val capacity: Long = (MAX_DATA_CHUNK_BYTES * 2).toLong()

    init {
        MemoryLedger.registerRoot(this)
    }

    private val input = duplex.input
    private val output = duplex.output
    private val dialer = FrameDialer(initialStreamId)
    private var poisoned = false

    val isPoisoned: Boolean
        get() = poisoned

    fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    ): HttpResponse = exchange(method, path, headers, body, maxResponseBytes, ::parseHttpResponse)

    fun requestBrowser(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Int = MAX_BROWSER_RESPONSE_BYTES,
    ): BrowserHttpResponse =
        exchange(method, path, headers, body, maxResponseBytes, ::parseBrowserHttpResponse)

    fun requestStreaming(
        method: String,
        path: String,
        headers: List<Pair<String, String>>,
        bodySource: BrowserRequestBodySource?,
        responseSink: BrowserResponseSink,
    ) {
        if (poisoned) {
            val ex = IOException(SESSION_UNUSABLE)
            responseSink.onError(ex)
            throw ex
        }
        val cl = bodySource?.contentLength
        if (bodySource != null && (cl == null || cl < 0)) {
            val ex = IOException("Streaming request body requires exact non-negative content length")
            responseSink.onError(ex)
            throw ex
        }
        val progressiveParser = ProgressiveBrowserResponseParser(
            responseSink,
            isHeadRequest = method.equals("HEAD", ignoreCase = true),
        )
        val consumer = object : StreamingResponseConsumer<Unit> {
            override fun onData(payload: ByteArray, offset: Int, length: Int) {
                progressiveParser.feed(payload, offset, length)
            }

            override fun onMuxClose(successful: Boolean) {
                progressiveParser.onMuxClose(successful = true)
            }
        }
        try {
            executeStreamingExchange(
                method = method,
                path = path,
                headers = headers,
                contentLength = cl,
                readChunk = bodySource?.let { src -> { buf, off, len -> src.readChunk(buf, off, len) } },
                maxResponseBytes = null,
                consumer = consumer,
            )
        } catch (e: Exception) {
            responseSink.onError(e)
            throw e
        }
    }

    fun requestStreaming(
        method: String,
        path: String,
        headers: List<Pair<String, String>>,
        contentLength: Long,
        readChunk: (ByteArray, Int, Int) -> Int,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    ): HttpResponse {
        if (poisoned) {
            throw IOException(SESSION_UNUSABLE)
        }
        if (contentLength < 0) {
            throw IOException("Streaming request body requires exact non-negative content length")
        }
        val consumer = StreamingHttpResponseAssembler(maxResponseBytes)
        return executeStreamingExchange(
            method = method,
            path = path,
            headers = headers,
            contentLength = contentLength,
            readChunk = readChunk,
            maxResponseBytes = maxResponseBytes,
            consumer = consumer,
        )
    }

    private interface StreamingResponseConsumer<T> {
        fun onData(payload: ByteArray, offset: Int, length: Int)
        fun onMuxClose(successful: Boolean): T
    }

    private class StreamingHttpResponseAssembler(
        private val maxResponseBytes: Int,
    ) : StreamingResponseConsumer<HttpResponse> {
        private enum class State {
            READING_HEADERS,
            READING_BODY,
        }

        private var state = State.READING_HEADERS
        private val rawHeaderStream = ByteArrayOutputStream()
        private val bodyStream = ByteArrayOutputStream()

        private var status: Int = 0
        private var headers = LinkedHashMap<String, String>()
        private var expectedContentLength: Long? = null

        override fun onData(payload: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return

            var pos = offset
            val end = offset + length

            while (pos < end) {
                when (state) {
                    State.READING_HEADERS -> {
                        rawHeaderStream.write(payload[pos].toInt())
                        pos++
                        while (state == State.READING_HEADERS) {
                            val bytes = rawHeaderStream.toByteArray()
                            val split = findHeaderEnd(bytes)
                            if (split < 0) break

                            val headerText = String(bytes, 0, split, Charsets.ISO_8859_1)
                            val remainingBytes = bytes.copyOfRange(split + 4, bytes.size)
                            rawHeaderStream.reset()

                            parseHeaders(headerText)

                            if (status in 100..199) {
                                if (remainingBytes.isNotEmpty()) {
                                    rawHeaderStream.write(remainingBytes)
                                }
                                state = State.READING_HEADERS
                            } else {
                                state = State.READING_BODY
                                if (remainingBytes.isNotEmpty()) {
                                    appendBody(remainingBytes, 0, remainingBytes.size)
                                }
                            }
                        }
                    }
                    State.READING_BODY -> {
                        val remainingInChunk = end - pos
                        appendBody(payload, pos, remainingInChunk)
                        pos = end
                    }
                }
            }
        }

        private fun appendBody(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            if (bodyStream.size() + length > maxResponseBytes) {
                throw IOException("PL response body exceeded maxResponseBytes ($maxResponseBytes)")
            }
            bodyStream.write(bytes, offset, length)
            expectedContentLength?.let { cl ->
                if (bodyStream.size().toLong() > cl) {
                    throw IOException("PL response body exceeded Content-Length ($cl)")
                }
            }
        }

        private fun parseHeaders(headerText: String) {
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) throw IOException("Empty HTTP response")
            val statusLine = lines[0]
            val statusParts = statusLine.split(" ", limit = 3)
            if (statusParts.size < 2) throw IOException("Bad HTTP status line: $statusLine")
            val parsedStatus = statusParts[1].toIntOrNull() ?: throw IOException("Invalid status code in: $statusLine")
            status = parsedStatus

            if (status in 100..199) {
                return
            }

            var contentLengthCount = 0
            var transferEncoding: String? = null
            var contentLengthVal: String? = null
            val parsedHeaders = LinkedHashMap<String, String>()

            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim().lowercase(Locale.US)
                val value = line.substring(colon + 1).trim()
                if (name == "content-length") {
                    contentLengthCount++
                    contentLengthVal = value
                }
                if (name == "transfer-encoding") {
                    transferEncoding = value.lowercase(Locale.US)
                }
                parsedHeaders[name] = value
            }

            if (contentLengthCount > 1) {
                throw IOException("Multiple Content-Length headers in HTTP response")
            }
            if (transferEncoding != null && transferEncoding.contains("chunked")) {
                throw IOException("Chunked Transfer-Encoding is not supported")
            }

            if (contentLengthVal != null) {
                val cl = contentLengthVal.toLongOrNull() ?: throw IOException("Invalid Content-Length: $contentLengthVal")
                if (cl < 0) throw IOException("Negative Content-Length: $cl")
                expectedContentLength = cl
            }

            headers = parsedHeaders
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

        override fun onMuxClose(successful: Boolean): HttpResponse {
            if (state == State.READING_HEADERS) {
                throw IOException("PL stream ended with unfinished response head")
            }
            expectedContentLength?.let { cl ->
                if (bodyStream.size().toLong() != cl) {
                    throw IOException("Premature response body termination: expected $cl bytes, received ${bodyStream.size()}")
                }
            }
            return HttpResponse(status, headers, bodyStream.toByteArray())
        }
    }

    private fun <T> executeStreamingExchange(
        method: String,
        path: String,
        headers: List<Pair<String, String>>,
        contentLength: Long?,
        readChunk: ((ByteArray, Int, Int) -> Int)?,
        maxResponseBytes: Int?,
        consumer: StreamingResponseConsumer<T>,
    ): T {
        val streamId = dialer.allocate()
        notifyObserver { it.onStreamOpened(streamId) }
        var successful = false
        var requestClosed = false
        try {
            val reqHeaderBytes = formatStreamingHttpRequest(
                method = method,
                path = path,
                headers = headers,
                hasBody = contentLength != null,
                contentLength = contentLength,
            )
            writeFrame(streamId, FLAG_OPEN or FLAG_DATA, reqHeaderBytes)

            var produced = 0L
            var sent = 0L
            var eosConfirmed = false

            if (contentLength == null) {
                writeFrame(streamId, FLAG_CLOSE, ByteArray(0))
                requestClosed = true
            }

            // Framing § flow control: the OPEN frame's head draws on the same send window as the
            // body. Left out, a body over 1 MiB overran a journal that had not granted yet by the
            // head's size, and the journal reset the stream with FLOW_CONTROL_ERROR.
            var sendCredit = INITIAL_SEND_WINDOW.toLong() - reqHeaderBytes.size
            val receive = ReceiveCredit(initialReceiveWindow)
            var cumulativeBytes = 0L
            var isPreOpen = true
            val unsent = ByteArrayOutputStream(MAX_DATA_CHUNK_BYTES)

            while (true) {
                while (unsent.size() > 0 && sendCredit > 0) {
                    val hasFinalByte = (contentLength != null && produced == contentLength)
                    if (hasFinalByte && !eosConfirmed) {
                        break
                    }
                    val count = minOf(MAX_DATA_CHUNK_BYTES.toLong(), unsent.size().toLong(), sendCredit).toInt()
                    val all = unsent.toByteArray()
                    val chunk = if (count == all.size) all else all.copyOfRange(0, count)
                    writeFrame(streamId, FLAG_DATA, chunk)
                    sendCredit -= count
                    sent += count
                    unsent.reset()
                    if (count < all.size) {
                        unsent.write(all, count, all.size - count)
                    }
                }

                if (contentLength != null && eosConfirmed && unsent.size() == 0 && !requestClosed) {
                    writeFrame(streamId, FLAG_CLOSE, ByteArray(0))
                    requestClosed = true
                }

                if (contentLength == 0L && !eosConfirmed) {
                    val eosBuf = ByteArray(64)
                    val read = try {
                        readChunk!!(eosBuf, 0, eosBuf.size)
                    } catch (t: Throwable) {
                        writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                        throw if (t is IOException) t else IOException("Streaming source read threw", t)
                    }
                    if (read < 0) {
                        eosConfirmed = true
                        writeFrame(streamId, FLAG_CLOSE, ByteArray(0))
                        requestClosed = true
                    } else if (read > 0) {
                        writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                        throw IOException("Streaming source produced bytes for zero-length body")
                    }
                }

                if (contentLength != null && !requestClosed && produced < contentLength && sendCredit > 0 && unsent.size() < MAX_DATA_CHUNK_BYTES) {
                    val toRead = minOf((MAX_DATA_CHUNK_BYTES - unsent.size()).toLong(), contentLength - produced, sendCredit).toInt()
                    val readBuf = ByteArray(toRead)
                    val read = try {
                        readChunk!!(readBuf, 0, toRead)
                    } catch (t: Throwable) {
                        writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                        throw if (t is IOException) t else IOException("Streaming source read threw", t)
                    }
                    if (read < 0) {
                        writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                        throw IOException("Streaming source ended prematurely: produced $produced bytes, expected $contentLength")
                    } else if (read > toRead) {
                        writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                        throw IOException("Streaming source read more bytes than requested")
                    } else if (read > 0) {
                        unsent.write(readBuf, 0, read)
                        produced += read
                    }
                }

                if (contentLength != null && produced == contentLength && !eosConfirmed) {
                    while (produced == contentLength && !eosConfirmed) {
                        val eosBuf = ByteArray(64)
                        val eosRead = try {
                            readChunk!!(eosBuf, 0, eosBuf.size)
                        } catch (t: Throwable) {
                            writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                            throw if (t is IOException) t else IOException("Streaming source read threw on EOS check", t)
                        }
                        if (eosRead > 0) {
                            writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                            throw IOException("Streaming source produced more bytes than declared contentLength: $contentLength")
                        } else if (eosRead < 0) {
                            eosConfirmed = true
                            break
                        }
                    }
                }

                if ((unsent.size() > 0 && sendCredit > 0 && (eosConfirmed || (contentLength != null && produced < contentLength))) ||
                    (contentLength != null && !requestClosed && produced < contentLength && sendCredit > 0 && unsent.size() < MAX_DATA_CHUNK_BYTES) ||
                    (contentLength != null && eosConfirmed && unsent.size() == 0 && !requestClosed)
                ) {
                    continue
                }

                val frame = readFrame()
                val classification = MuxClassifier.classify(
                    streamId = frame.streamId,
                    flags = frame.flags,
                    payloadLength = frame.payload.size,
                    activeStreamId = streamId,
                    nextStreamId = dialer.nextStreamId,
                    isPreOpen = isPreOpen,
                )
                when (classification) {
                    MuxClassification.SESSION_TERMINAL_RESERVED -> {
                        markPoisoned()
                        throw IOException("PL protocol error: reserved flag")
                    }
                    MuxClassification.SESSION_TERMINAL_OVERSIZED -> {
                        markPoisoned()
                        throw IOException("PL protocol error: advertised payload too large")
                    }
                    MuxClassification.SESSION_TERMINAL_MALFORMED_CONTROL -> {
                        markPoisoned()
                        throw IOException("PL protocol error: malformed control frame")
                    }
                    MuxClassification.SESSION_TERMINAL_NEGATIVE_ID -> {
                        markPoisoned()
                        throw IOException("PL protocol error: negative stream ID")
                    }
                    MuxClassification.SESSION_TERMINAL_MALFORMED_SHAPE,
                    MuxClassification.SESSION_TERMINAL_ZERO_MASK_NONZERO_PAYLOAD,
                    MuxClassification.SESSION_TERMINAL_FUTURE_LOCAL -> {
                        markPoisoned()
                        throw IOException("PL protocol error")
                    }
                    MuxClassification.CONTROL_PING -> {
                        writeFrame(0, FLAG_PONG, frame.payload)
                        continue
                    }
                    MuxClassification.CONTROL_PONG,
                    MuxClassification.PAST_LOCAL_DROP,
                    MuxClassification.FOREIGN_EVEN_IGNORE -> {
                        continue
                    }
                    MuxClassification.FOREIGN_EVEN_RESET -> {
                        writeFrame(frame.streamId, FLAG_RESET, byteArrayOf(0x01))
                        continue
                    }
                    MuxClassification.ACTIVE_INVALID_FLAGS -> {
                        writeFrame(streamId, FLAG_RESET, byteArrayOf(0x01))
                        throw IOException("PL protocol error: invalid flags")
                    }
                    MuxClassification.ACTIVE_RESET -> {
                        throw IOException("PL stream reset: " + resetReason(frame.payload))
                    }
                    MuxClassification.ACTIVE_WINDOW -> {
                        sendCredit += decodeWindowCredit(frame.payload).toLong() and 0xffffffffL
                        if (sendCredit > Int.MAX_VALUE) {
                            writeFrame(streamId, FLAG_RESET, byteArrayOf(0x02))
                            throw IOException("PL send window overflow")
                        }
                        continue
                    }
                    MuxClassification.ACTIVE_DATA -> {
                        isPreOpen = false
                        val size = frame.payload.size
                        val closesStream = (frame.flags and FLAG_CLOSE) != 0
                        if (size > 0) {
                            if (!receive.debit(size)) {
                                writeFrame(streamId, FLAG_RESET, byteArrayOf(0x02))
                                throw IOException("PL receive window exceeded")
                            }
                            cumulativeBytes += size
                            notifyObserver { it.onResponseDataConsumed(streamId, size, cumulativeBytes.toInt()) }
                            try {
                                consumer.onData(frame.payload, 0, size)
                            } catch (e: Exception) {
                                if (!isPoisoned) writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                                throw if (e is IOException) e else IOException("Consumer onData failed", e)
                            }
                            if (!closesStream) {
                                receive.grant()?.let { writeFrame(streamId, FLAG_WINDOW, encodeWindowCredit(it)) }
                            }
                        }
                        if (closesStream) {
                            if (!requestClosed) {
                                writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                                requestClosed = true
                            }
                            val result = try {
                                consumer.onMuxClose(successful = true)
                            } catch (e: Exception) {
                                if (!isPoisoned) writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                                throw if (e is IOException) e else IOException("Consumer onMuxClose failed", e)
                            }
                            successful = true
                            return result
                        }
                    }
                    MuxClassification.ACTIVE_CLOSE -> {
                        isPreOpen = false
                        if (!requestClosed) {
                            writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                            requestClosed = true
                        }
                        val result = try {
                            consumer.onMuxClose(successful = true)
                        } catch (e: Exception) {
                            if (!isPoisoned) writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                            throw if (e is IOException) e else IOException("Consumer onMuxClose failed", e)
                        }
                        successful = true
                        return result
                    }
                }
            }
        } finally {
            notifyObserver { it.onStreamTerminated(streamId, successful) }
        }
    }

    private fun <T> exchange(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Int,
        parser: (ByteArray) -> T,
    ): T {
        if (poisoned) {
            throw IOException(SESSION_UNUSABLE)
        }
        val streamId = dialer.allocate()
        notifyObserver { it.onStreamOpened(streamId) }
        var successful = false
        try {
            val request = httpRequestBytes(method, path, headers, body)
            var offset = 0
            var requestClosed = false
            // Framing § flow control: the request draws on per-stream send credit, and only the
            // peer's WINDOW grants replenish it. Sending past it draws a FLOW_CONTROL_ERROR reset,
            // which is what stalled every ingest POST over 1 MiB once the journal drained slower
            // than the wire. So the request goes out as credit allows, between frame reads.
            var sendCredit = INITIAL_SEND_WINDOW.toLong()
            val response = ByteArrayOutputStream()
            val receive = ReceiveCredit(initialReceiveWindow)
            while (true) {
                while (offset < request.size && sendCredit > 0) {
                    val count = minOf(MAX_DATA_CHUNK_BYTES.toLong(), (request.size - offset).toLong(), sendCredit).toInt()
                    val flags = if (offset == 0) FLAG_OPEN or FLAG_DATA else FLAG_DATA
                    writeFrame(streamId, flags, request.copyOfRange(offset, offset + count))
                    offset += count
                    sendCredit -= count
                }
                if (offset == request.size && !requestClosed) {
                    writeFrame(streamId, FLAG_CLOSE, ByteArray(0))
                    requestClosed = true
                }
                val frame = readFrame()
                val classification = MuxClassifier.classify(
                    streamId = frame.streamId,
                    flags = frame.flags,
                    payloadLength = frame.payload.size,
                    activeStreamId = streamId,
                    nextStreamId = dialer.nextStreamId,
                )
                when (classification) {
                    MuxClassification.SESSION_TERMINAL_RESERVED -> {
                        markPoisoned()
                        throw IOException("PL protocol error: reserved flag")
                    }
                    MuxClassification.SESSION_TERMINAL_OVERSIZED -> {
                        markPoisoned()
                        throw IOException("PL protocol error: advertised payload too large")
                    }
                    MuxClassification.SESSION_TERMINAL_MALFORMED_CONTROL -> {
                        markPoisoned()
                        throw IOException("PL protocol error: malformed control frame")
                    }
                    MuxClassification.SESSION_TERMINAL_NEGATIVE_ID -> {
                        markPoisoned()
                        throw IOException("PL protocol error: negative stream ID")
                    }
                    MuxClassification.SESSION_TERMINAL_MALFORMED_SHAPE,
                    MuxClassification.SESSION_TERMINAL_ZERO_MASK_NONZERO_PAYLOAD,
                    MuxClassification.SESSION_TERMINAL_FUTURE_LOCAL -> {
                        markPoisoned()
                        throw IOException("PL protocol error")
                    }
                    MuxClassification.CONTROL_PING -> {
                        writeFrame(0, FLAG_PONG, frame.payload)
                        continue
                    }
                    MuxClassification.CONTROL_PONG,
                    MuxClassification.PAST_LOCAL_DROP,
                    MuxClassification.FOREIGN_EVEN_IGNORE -> {
                        continue
                    }
                    MuxClassification.FOREIGN_EVEN_RESET -> {
                        writeFrame(frame.streamId, FLAG_RESET, byteArrayOf(0x01))
                        continue
                    }
                    MuxClassification.ACTIVE_INVALID_FLAGS -> {
                        writeFrame(streamId, FLAG_RESET, byteArrayOf(0x01))
                        throw IOException("PL protocol error: invalid flags")
                    }
                    MuxClassification.ACTIVE_RESET -> {
                        throw IOException("PL stream reset: " + resetReason(frame.payload))
                    }
                    MuxClassification.ACTIVE_WINDOW -> {
                        sendCredit += decodeWindowCredit(frame.payload).toLong() and 0xffffffffL
                        if (sendCredit > Int.MAX_VALUE) {
                            writeFrame(streamId, FLAG_RESET, byteArrayOf(0x02))
                            throw IOException("PL send window overflow")
                        }
                        continue
                    }
                    MuxClassification.ACTIVE_DATA -> {
                        val size = frame.payload.size
                        val closesStream = (frame.flags and FLAG_CLOSE) != 0
                        if (size > 0) {
                            if (!receive.debit(size)) {
                                writeFrame(streamId, FLAG_RESET, byteArrayOf(0x02))
                                throw IOException("PL receive window exceeded")
                            }
                            if (response.size() + size > maxResponseBytes) {
                                writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                                throw IOException("PL response too large")
                            }
                            response.write(frame.payload)
                            notifyObserver { it.onResponseDataConsumed(streamId, size, response.size()) }
                            // See the streaming path above: no credit back on the closing frame.
                            if (!closesStream) {
                                receive.grant()?.let { writeFrame(streamId, FLAG_WINDOW, encodeWindowCredit(it)) }
                            }
                        }
                        if (closesStream) {
                            // An answer before the whole request went out (an early 413, say):
                            // the rest of the body is abandoned rather than left half-open.
                            if (!requestClosed) writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                            val parsed = parser(response.toByteArray())
                            successful = true
                            return parsed
                        }
                    }
                    MuxClassification.ACTIVE_CLOSE -> {
                        if (!requestClosed) writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                        val parsed = parser(response.toByteArray())
                        successful = true
                        return parsed
                    }
                }
            }
        } finally {
            notifyObserver { it.onStreamTerminated(streamId, successful) }
        }
    }

    private inline fun notifyObserver(block: (PlStreamObserver) -> Unit) {
        val current = observer ?: return
        try {
            block(current)
        } catch (_: Throwable) {
            // Optional observation must never alter production transport behavior.
        }
    }

    private fun writeFrame(streamId: Int, flags: Int, payload: ByteArray) {
        if (poisoned) return
        val frame = encodeFrame(streamId, flags, payload)
        try {
            output.write(frame)
            output.flush()
        } catch (e: IOException) {
            if ((flags and FLAG_RESET) == 0 && (flags and FLAG_WINDOW) == 0) throw e
        }
    }

    private fun readFrame(): Frame {
        try {
            val header = readExactly(8)
            val streamId = ((header[0].toInt() and 0xff) shl 24) or
                ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)
            val flags = header[4].toInt() and 0xff
            val length = ((header[5].toInt() and 0xff) shl 16) or
                ((header[6].toInt() and 0xff) shl 8) or
                (header[7].toInt() and 0xff)

            if ((flags and FLAG_RESERVED) != 0) {
                markPoisoned()
                throw IOException("PL protocol error: reserved flag")
            }
            if (length > MAX_DATA_CHUNK_BYTES) {
                markPoisoned()
                throw IOException("PL protocol error: advertised payload too large")
            }
            if (streamId < 0) {
                markPoisoned()
                throw IOException("PL protocol error: negative stream ID")
            }

            val payload = readExactly(length)
            return Frame(streamId, flags, payload)
        } catch (e: SocketTimeoutException) {
            markPoisoned()
            throw IOException("timed out waiting for PL frame", e)
        } catch (e: IOException) {
            markPoisoned()
            throw e
        }
    }

    private fun readExactly(length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) {
                throw IOException("socket closed while reading PL frame")
            }
            offset += read
        }
        return out
    }

    override fun close() {
        MemoryLedger.unregisterRoot(this)
        duplex.close()
    }

    private fun markPoisoned() {
        poisoned = true
    }

    private fun resetReason(payload: ByteArray): String =
        if (payload.isEmpty()) "" else (payload[0].toInt() and 0xff).toString()
}

/**
 * Receive-side credit for one stream, the policy of spl-core's `RecvWindow`: DATA is debited as it
 * arrives and consumed at once, and the consumed bytes are granted back together only once half
 * the initial window has built up.
 *
 * ⛔ Not a grant per frame. A journal answers with DATA and then a separate CLOSE, and forgets the
 * stream at that CLOSE once this side has closed, so the grant for its last DATA frame always
 * reaches a forgotten id. Framing § late frames makes the journal answer it with a PROTOCOL reset,
 * which was one refusal on nearly every request. Below half a window no grant is ever sent.
 */
internal class ReceiveCredit(private val initialWindow: Int) {
    private var credit = initialWindow.toLong()
    private var unacked = 0L

    /** Debits [size] bytes, or returns false without changing state when they exceed the credit. */
    fun debit(size: Int): Boolean {
        if (size > credit) return false
        credit -= size
        unacked += size
        return true
    }

    /** The credit to return now, or null while less than half the window has been consumed. */
    fun grant(): Int? {
        if (unacked < initialWindow / 2) return null
        val grant = unacked.toInt()
        credit += unacked
        unacked = 0
        return grant
    }
}

const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
const val MAX_BROWSER_RESPONSE_BYTES = 16 * 1024 * 1024
const val INITIAL_RECEIVE_WINDOW = 1024 * 1024
const val INITIAL_SEND_WINDOW = 1024 * 1024
const val MAX_DATA_CHUNK_BYTES = 64 * 1024
const val SESSION_UNUSABLE = "PL session unusable"
const val SOCKET_TIMEOUT_MS = 30000
