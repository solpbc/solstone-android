// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException

import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.ProgressiveBrowserResponseParser
import app.solstone.core.pl.browser.formatStreamingHttpRequest
import app.solstone.core.pl.browser.parseBrowserHttpResponse

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

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
        val streamId = dialer.allocate()
        notifyObserver { it.onStreamOpened(streamId) }
        var successful = false
        val progressiveParser = ProgressiveBrowserResponseParser(responseSink, isHeadRequest = method.equals("HEAD", ignoreCase = true))

        val sendCreditLock = ReentrantLock()
        val sendCreditCond = sendCreditLock.newCondition()
        var sendWindow = initialReceiveWindow.toLong()
        val senderDone = AtomicBoolean(false)
        val senderError = AtomicReference<Throwable?>(null)

        try {
            val reqHeaderBytes = formatStreamingHttpRequest(
                method = method,
                path = path,
                headers = headers,
                hasBody = bodySource != null,
            )
            writeFrame(streamId, FLAG_OPEN or FLAG_DATA, reqHeaderBytes)

            val senderThread = Thread({
                try {
                    if (bodySource != null) {
                        val chunkBuf = ByteArray(MAX_DATA_CHUNK_BYTES)
                        while (!poisoned) {
                            val read = bodySource.readChunk(chunkBuf, 0, chunkBuf.size)
                            if (read < 0) break
                            if (read > 0) {
                                sendCreditLock.withLock {
                                    while (!poisoned && sendWindow < read) {
                                        sendCreditCond.await(1000, TimeUnit.MILLISECONDS)
                                    }
                                    sendWindow -= read
                                }
                                writeFrame(streamId, FLAG_DATA, chunkBuf.copyOf(read))
                            }
                        }
                    }
                    writeFrame(streamId, FLAG_CLOSE, ByteArray(0))
                } catch (t: Throwable) {
                    senderError.set(t)
                } finally {
                    sendCreditLock.withLock {
                        senderDone.set(true)
                        sendCreditCond.signalAll()
                    }
                }
            }, "MuxSender-$streamId")
            senderThread.isDaemon = true
            senderThread.start()

            val receive = ReceiveCredit(initialReceiveWindow)
            var cumulativeBytes = 0L
            var isPreOpen = true

            while (true) {
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
                    MuxClassification.SESSION_TERMINAL_RESERVED,
                    MuxClassification.SESSION_TERMINAL_OVERSIZED,
                    MuxClassification.SESSION_TERMINAL_MALFORMED_CONTROL,
                    MuxClassification.SESSION_TERMINAL_NEGATIVE_ID,
                    MuxClassification.SESSION_TERMINAL_MALFORMED_SHAPE,
                    MuxClassification.SESSION_TERMINAL_ZERO_MASK_NONZERO_PAYLOAD,
                    MuxClassification.SESSION_TERMINAL_FUTURE_LOCAL -> {
                        markPoisoned()
                        sendCreditLock.withLock { sendCreditCond.signalAll() }
                        val ex = IOException("PL protocol error")
                        responseSink.onError(ex)
                        throw ex
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
                        sendCreditLock.withLock { sendCreditCond.signalAll() }
                        val ex = IOException("PL protocol error: invalid flags")
                        responseSink.onError(ex)
                        throw ex
                    }
                    MuxClassification.ACTIVE_RESET -> {
                        sendCreditLock.withLock { sendCreditCond.signalAll() }
                        val ex = IOException("PL stream reset: " + resetReason(frame.payload))
                        responseSink.onError(ex)
                        throw ex
                    }
                    MuxClassification.ACTIVE_WINDOW -> {
                        val credit = decodeWindowCredit(frame.payload)
                        sendCreditLock.withLock {
                            sendWindow += credit
                            sendCreditCond.signalAll()
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
                                val ex = IOException("PL receive window exceeded")
                                responseSink.onError(ex)
                                throw ex
                            }
                            cumulativeBytes += size
                            notifyObserver { it.onResponseDataConsumed(streamId, size, cumulativeBytes.toInt()) }
                            progressiveParser.feed(frame.payload, 0, size)
                            // ⛔ No credit back on the frame that ends the stream. Framing § late
                            // frames on unknown ids: a WINDOW for a forgotten id asserts the sender
                            // believes the stream is live, so the peer must answer it with a RESET
                            // and count a protocol error. Every request that ended on a payload-
                            // carrying CLOSE was drawing one.
                            if (!closesStream) {
                                receive.grant()?.let { writeFrame(streamId, FLAG_WINDOW, encodeWindowCredit(it)) }
                            }
                        }
                        if (closesStream) {
                            successful = true
                            progressiveParser.onMuxClose(successful = true)
                            return
                        }
                    }
                    MuxClassification.ACTIVE_CLOSE -> {
                        isPreOpen = false
                        successful = true
                        progressiveParser.onMuxClose(successful = true)
                        return
                    }
                }
            }
        } catch (e: Exception) {
            responseSink.onError(e)
            throw e
        } finally {
            sendCreditLock.withLock { sendCreditCond.signalAll() }
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
