// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.ByteDuplex
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal interface BinaryWebSocket {
    fun send(bytes: ByteString): Boolean
    fun close(code: Int, reason: String?)
}

class RelayWebSocketClosedException(val code: Int, val reason: String) :
    IOException("relay websocket closed: $code $reason")

class RelayDialWaitingException(waitingTimeoutMillis: Long) :
    IOException("relay dial waiting: home offline (no inner data within ${waitingTimeoutMillis}ms)")

class OkHttpWebSocketDuplex internal constructor(
    socket: BinaryWebSocket,
    private val capacityBytes: Int = DEFAULT_BUFFER_BYTES,
    private val waitingTimeoutMillis: Long = 0L,
) : ByteDuplex {
    private val lock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var bufferedBytes = 0
    private var current: ByteArray? = null
    private var currentOffset = 0
    private var failure: IOException? = null
    private var inboundClosed = false
    private var inboundEverReceived = false
    private var closed = false
    private var socket: BinaryWebSocket? = socket

    internal constructor(
        capacityBytes: Int = DEFAULT_BUFFER_BYTES,
        waitingTimeoutMillis: Long = 0L,
    ) : this(NoSocket, capacityBytes, waitingTimeoutMillis) {
        socket = null
    }

    override val input: InputStream = WsInputStream()
    override val output: OutputStream = WsOutputStream()

    internal fun attach(socket: BinaryWebSocket) {
        synchronized(lock) {
            this.socket = socket
            lock.notifyAll()
        }
    }

    internal fun receive(bytes: ByteString) {
        val data = bytes.toByteArray()
        synchronized(lock) {
            while (!closed && failure == null && bufferedBytes + data.size > capacityBytes) {
                lock.wait()
            }
            if (closed || failure != null) {
                return
            }
            inboundEverReceived = true
            queue.addLast(data)
            bufferedBytes += data.size
            lock.notifyAll()
        }
    }

    internal fun closeInbound() {
        synchronized(lock) {
            inboundClosed = true
            lock.notifyAll()
        }
    }

    internal fun closeFromWebSocket(code: Int, reason: String) {
        if (code == 1000) {
            closeInbound()
        } else {
            fail(RelayWebSocketClosedException(code, reason))
        }
    }

    internal fun fail(error: IOException) {
        synchronized(lock) {
            failure = error
            inboundClosed = true
            lock.notifyAll()
        }
    }

    internal fun failureOrNull(): IOException? =
        synchronized(lock) {
            failure
        }

    override fun close() {
        val toClose: BinaryWebSocket?
        synchronized(lock) {
            if (closed) {
                return
            }
            closed = true
            inboundClosed = true
            toClose = socket
            lock.notifyAll()
        }
        toClose?.close(1000, "closed")
    }

    private fun socketOrThrow(): BinaryWebSocket {
        synchronized(lock) {
            while (socket == null && failure == null && !closed) {
                lock.wait()
            }
            failure?.let { throw it }
            if (closed) {
                throw IOException("WebSocket duplex is closed")
            }
            return socket ?: throw IOException("WebSocket was not opened")
        }
    }

    private inner class WsInputStream : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val read = read(one, 0, 1)
            return if (read < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) {
                return 0
            }
            synchronized(lock) {
                var deadlineNanos = 0L
                var haveDeadline = false
                while (current == null && queue.isEmpty() && failure == null && !inboundClosed) {
                    if (!inboundEverReceived && waitingTimeoutMillis > 0) {
                        val nowNanos = System.nanoTime()
                        if (!haveDeadline) {
                            deadlineNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(waitingTimeoutMillis)
                            haveDeadline = true
                        }
                        val remainingNanos = deadlineNanos - nowNanos
                        if (remainingNanos <= 0L) {
                            throw RelayDialWaitingException(waitingTimeoutMillis)
                        }
                        val waitMillis = maxOf(
                            1L,
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos) +
                                if (remainingNanos % 1_000_000L != 0L) 1L else 0L,
                        )
                        lock.wait(waitMillis)
                    } else {
                        lock.wait()
                    }
                }
                failure?.let { throw it }
                if (current == null) {
                    current = if (queue.isEmpty()) null else queue.removeFirst()
                    currentOffset = 0
                }
                val source = current ?: return -1
                val count = minOf(len, source.size - currentOffset)
                source.copyInto(b, off, currentOffset, currentOffset + count)
                currentOffset += count
                if (currentOffset == source.size) {
                    bufferedBytes -= source.size
                    current = null
                    currentOffset = 0
                    lock.notifyAll()
                }
                return count
            }
        }
    }

    private inner class WsOutputStream : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len
            while (remaining > 0) {
                val count = minOf(MAX_WS_CHUNK_BYTES, remaining)
                val sent = socketOrThrow().send(b.toByteString(offset, count))
                if (!sent) {
                    throw IOException("WebSocket send failed")
                }
                offset += count
                remaining -= count
            }
        }
    }

    private object NoSocket : BinaryWebSocket {
        override fun send(bytes: ByteString): Boolean = false
        override fun close(code: Int, reason: String?) = Unit
    }

    companion object {
        internal const val MAX_WS_CHUNK_BYTES = 64 * 1024
        private const val DEFAULT_BUFFER_BYTES = 256 * 1024
        private const val OPEN_TIMEOUT_SECONDS = 30L

        fun open(
            client: OkHttpClient,
            request: Request,
            waitingTimeoutMillis: Long = 0L,
        ): OkHttpWebSocketDuplex {
            val duplex = OkHttpWebSocketDuplex(waitingTimeoutMillis = waitingTimeoutMillis)
            val opened = CountDownLatch(1)
            val webSocket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        duplex.attach(OkHttpBinaryWebSocket(webSocket))
                        opened.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        duplex.receive(bytes)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        duplex.closeFromWebSocket(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        duplex.closeFromWebSocket(code, reason)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        duplex.fail(IOException("WebSocket failed", t))
                        opened.countDown()
                    }
                },
            )
            if (!opened.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                webSocket.close(1000, "open timeout")
                duplex.close()
                throw IOException("timed out opening WebSocket")
            }
            duplex.failureOrNull()?.let { throw it }
            return duplex
        }
    }
}

private class OkHttpBinaryWebSocket(private val webSocket: WebSocket) : BinaryWebSocket {
    override fun send(bytes: ByteString): Boolean = webSocket.send(bytes)

    override fun close(code: Int, reason: String?) {
        webSocket.close(code, reason)
    }
}
