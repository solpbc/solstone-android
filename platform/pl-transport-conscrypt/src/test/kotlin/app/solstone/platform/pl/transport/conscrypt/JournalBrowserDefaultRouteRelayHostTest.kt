// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.ByteDuplex
import app.solstone.core.pl.FLAG_CLOSE
import app.solstone.core.pl.FLAG_DATA
import app.solstone.core.pl.FLAG_OPEN
import app.solstone.core.pl.Frame
import app.solstone.core.pl.MAX_DATA_CHUNK_BYTES
import app.solstone.core.pl.PlStreamObserver
import app.solstone.core.pl.RelayDialObserver
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.encodeFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalBrowserDefaultRouteRelayHostTest {

    private lateinit var fixture: EphemeralTlsFixture

    @BeforeTest
    fun setUp() {
        fixture = EphemeralTlsFixture.generate()
    }

    private class ServerWebSocketDuplex(
        private val socket: Socket,
        private val `in`: InputStream,
        private val out: OutputStream,
    ) : ByteDuplex {
        private val closed = AtomicBoolean(false)
        private var currentPayload: ByteArray = ByteArray(0)
        private var currentPos = 0

        override val input: InputStream = object : InputStream() {
            override fun read(): Int {
                val b = ByteArray(1)
                val r = read(b, 0, 1)
                return if (r < 0) -1 else b[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (closed.get()) return -1
                if (currentPos >= currentPayload.size) {
                    if (!fetchNextFrame()) return -1
                }
                val available = currentPayload.size - currentPos
                val toCopy = minOf(available, len)
                System.arraycopy(currentPayload, currentPos, b, off, toCopy)
                currentPos += toCopy
                return toCopy
            }

            private fun fetchNextFrame(): Boolean {
                val b0 = `in`.read()
                if (b0 < 0) return false
                val opcode = b0 and 0x0f
                if (opcode == 0x08) { // CLOSE
                    return false
                }
                val b1 = `in`.read()
                if (b1 < 0) return false
                val masked = (b1 and 0x80) != 0
                var payloadLen = (b1 and 0x7f).toLong()
                if (payloadLen == 126L) {
                    val lenBytes = ByteArray(2)
                    if (!readFully(lenBytes)) return false
                    payloadLen = (((lenBytes[0].toInt() and 0xff) shl 8) or (lenBytes[1].toInt() and 0xff)).toLong()
                } else if (payloadLen == 127L) {
                    val lenBytes = ByteArray(8)
                    if (!readFully(lenBytes)) return false
                    var v = 0L
                    for (i in 0..7) v = (v shl 8) or (lenBytes[i].toLong() and 0xff)
                    payloadLen = v
                }

                val mask = ByteArray(4)
                if (masked) {
                    if (!readFully(mask)) return false
                }

                val data = ByteArray(payloadLen.toInt())
                if (!readFully(data)) return false
                if (masked) {
                    for (i in data.indices) {
                        data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }
                currentPayload = data
                currentPos = 0
                return true
            }

            private fun readFully(target: ByteArray): Boolean {
                var offset = 0
                while (offset < target.size) {
                    val r = `in`.read(target, offset, target.size - offset)
                    if (r < 0) return false
                    offset += r
                }
                return true
            }
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                write(byteArrayOf(b.toByte()), 0, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed.get()) throw EOFException("closed")
                synchronized(out) {
                    out.write(0x82) // FIN + binary opcode
                    if (len <= 125) {
                        out.write(len)
                    } else if (len <= 65535) {
                        out.write(126)
                        out.write((len shr 8) and 0xff)
                        out.write(len and 0xff)
                    } else {
                        out.write(127)
                        for (shift in 56 downTo 0 step 8) {
                            out.write(((len.toLong() shr shift) and 0xff).toInt())
                        }
                    }
                    out.write(b, off, len)
                    out.flush()
                }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    synchronized(out) {
                        out.write(byteArrayOf(0x88.toByte(), 0x00))
                        out.flush()
                    }
                } catch (_: Exception) {}
                socket.close()
            }
        }
    }

    private fun handleWsUpgrade(rawIn: InputStream, rawOut: OutputStream): Boolean {
        val lines = mutableListOf<String>()
        val lineBuf = StringBuilder()
        while (true) {
            val c = rawIn.read()
            if (c < 0) return false
            if (c == '\n'.code) {
                val line = lineBuf.toString().trim()
                if (line.isEmpty()) break
                lines.add(line)
                lineBuf.clear()
            } else if (c != '\r'.code) {
                lineBuf.append(c.toChar())
            }
        }

        val secKey = lines.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: return false

        val acceptSha = MessageDigest.getInstance("SHA-1").digest(
            (secKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)
        )
        val acceptKey = Base64.getEncoder().encodeToString(acceptSha)

        val response = ("HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $acceptKey\r\n\r\n").toByteArray(Charsets.US_ASCII)
        rawOut.write(response)
        rawOut.flush()
        return true
    }

    private fun readFrameFromStream(input: InputStream): Frame {
        val header = ByteArray(8)
        var read = 0
        while (read < 8) {
            val r = input.read(header, read, 8 - read)
            if (r < 0) throw EOFException("EOF while reading frame header")
            read += r
        }
        val streamId = ((header[0].toInt() and 0xff) shl 24) or
            ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or
            (header[3].toInt() and 0xff)
        val flags = header[4].toInt() and 0xff
        val length = ((header[5].toInt() and 0xff) shl 16) or
            ((header[6].toInt() and 0xff) shl 8) or
            (header[7].toInt() and 0xff)
        val payload = ByteArray(length)
        read = 0
        while (read < length) {
            val r = input.read(payload, read, length - read)
            if (r < 0) throw EOFException("EOF while reading frame payload")
            read += r
        }
        return Frame(streamId, flags, payload)
    }

    private class RecordingStreamObserver : PlStreamObserver {
        val bytesReceived = AtomicLong(0)
        override fun onStreamOpened(streamId: Int) {}
        override fun onResponseDataConsumed(streamId: Int, deltaBytes: Int, cumulativeBytes: Int) {
            bytesReceived.addAndGet(deltaBytes.toLong())
        }
        override fun onStreamTerminated(streamId: Int, successful: Boolean) {}
    }

    private class RecordingRelayDialObserver : RelayDialObserver {
        val attempts = AtomicInteger(0)
        override fun onRelayDialAttempt(attemptNumber: Int, host: String, port: Int) {
            attempts.incrementAndGet()
        }
    }

    @Test
    fun relaySyncClientStreamsDuplexOverRealWebSocketAndInnerTls() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = false).apply { soTimeout = 10000 }
        val serverDone = CountDownLatch(1)
        val clientFinished = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 10000
                try {
                    sslSocket.startHandshake()
                    val rawIn = sslSocket.getInputStream()
                    val rawOut = sslSocket.getOutputStream()

                    assertTrue(handleWsUpgrade(rawIn, rawOut), "WS upgrade handshake failed")

                    // Wrap raw socket streams in WebSocket duplex
                    val wsDuplex = ServerWebSocketDuplex(sslSocket, rawIn, rawOut)

                    // Inner TLS server engine
                    val innerContext = fixture.createServerSslContext(needClientAuth = true)
                    val innerEngine = innerContext.createSSLEngine().apply {
                        useClientMode = false
                        needClientAuth = true
                    }
                    val innerTls = TlsEngineDuplex(innerEngine, wsDuplex)

                    // Now read/write Mux frames over inner TLS
                    val muxIn = innerTls.input
                    val muxOut = innerTls.output

                    val reqFrame = readFrameFromStream(muxIn)
                    assertEquals(1, reqFrame.streamId)
                    assertTrue((reqFrame.flags and FLAG_OPEN) != 0)

                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 12\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    muxOut.write(encodeFrame(1, FLAG_DATA, headers))
                    val body = "Hello Relay!".toByteArray(Charsets.US_ASCII)
                    muxOut.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, body))
                    muxOut.flush()
                    clientFinished.await(5, TimeUnit.SECONDS)
                } finally {
                    sslSocket.close()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
                serverDone.countDown()
            }
        }
        serverThread.start()

        val streamObs = RecordingStreamObserver()
        val dialObs = RecordingRelayDialObserver()

        fixture.withClientTrustDefault {
            val client = openRelaySyncClient(
                relayOrigin = "https://127.0.0.1:${serverSocket.localPort}",
                instanceId = "inst123",
                deviceToken = "tok456",
                credential = fixture.credential,
                streamObserver = streamObs,
                dialObserver = dialObs,
            )

            val bodyAcc = ByteArrayOutputStream()
            val done = CountDownLatch(1)

            client.requestStreaming(
                method = "GET",
                path = "/relay-data",
                headers = emptyList(),
                bodySource = null,
                responseSink = object : BrowserResponseSink {
                    override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                        assertEquals(200, status)
                    }

                    override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {
                        bodyAcc.write(chunk, offset, length)
                    }

                    override fun onTrailers(trailers: List<Pair<String, String>>) {}

                    override fun onComplete() {
                        done.countDown()
                    }

                    override fun onError(cause: Throwable) {
                        done.countDown()
                    }
                },
            )

            assertTrue(done.await(5, TimeUnit.SECONDS))
            clientFinished.countDown()
            assertTrue(serverDone.await(5, TimeUnit.SECONDS))
            assertEquals("Hello Relay!", bodyAcc.toString(Charsets.US_ASCII.name()))
            assertEquals(1, dialObs.attempts.get())
            assertTrue(streamObs.bytesReceived.get() > 0)

            client.close()
        }
    }
}
