// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.DirectDialObserver
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.FLAG_CLOSE
import app.solstone.core.pl.FLAG_DATA
import app.solstone.core.pl.FLAG_OPEN
import app.solstone.core.pl.Frame
import app.solstone.core.pl.MAX_DATA_CHUNK_BYTES
import app.solstone.core.pl.MuxSession
import app.solstone.core.pl.PlStreamObserver
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.encodeFrame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JournalBrowserDefaultRouteDirectHostTest {

    private lateinit var fixture: EphemeralTlsFixture

    @BeforeTest
    fun setUp() {
        fixture = EphemeralTlsFixture.generate()
    }

    private class RecordingStreamObserver : PlStreamObserver {
        val streamOpened = AtomicInteger(0)
        val streamClosed = AtomicInteger(0)
        val bytesReceived = AtomicLong(0)

        override fun onStreamOpened(streamId: Int) {
            streamOpened.incrementAndGet()
        }

        override fun onResponseDataConsumed(streamId: Int, deltaBytes: Int, cumulativeBytes: Int) {
            bytesReceived.addAndGet(deltaBytes.toLong())
        }

        override fun onStreamTerminated(streamId: Int, successful: Boolean) {
            streamClosed.incrementAndGet()
        }
    }

    private class RecordingDialObserver : DirectDialObserver {
        val dialedHosts = mutableListOf<String>()
        val dialedPorts = mutableListOf<Int>()

        override fun onDirectDialAttempt(host: String, port: Int) {
            dialedHosts.add(host)
            dialedPorts.add(port)
        }
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

    @Test
    fun streamsOverOneHundredFourMegabytesWithoutHeapExhaustion() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 15000 }
        val totalPayloadBytes = 105 * 1024 * 1024L // 105 MiB > 104 MiB
        val serverDone = CountDownLatch(1)
        val clientFinished = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 15000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    // Read client Mux OPEN frame
                    val reqFrame = readFrameFromStream(`in`)
                    assertEquals(1, reqFrame.streamId)
                    assertTrue((reqFrame.flags and FLAG_OPEN) != 0)

                    // Start background drain thread for client frames (WINDOW / RESET / CLOSE)
                    val drainThread = Thread {
                        try {
                            while (!sslSocket.isClosed) {
                                readFrameFromStream(`in`)
                            }
                        } catch (_: Exception) {}
                    }
                    drainThread.isDaemon = true
                    drainThread.start()

                    // Send 200 OK headers
                    val headers = "HTTP/1.1 200 OK\r\nContent-Length: $totalPayloadBytes\r\nContent-Type: application/octet-stream\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA, headers))
                    out.flush()

                    // Stream 105 MiB in 64 KiB chunks
                    val chunk = ByteArray(MAX_DATA_CHUNK_BYTES) { (it % 251).toByte() }
                    var sent = 0L
                    while (sent < totalPayloadBytes) {
                        val toSend = minOf(chunk.size.toLong(), totalPayloadBytes - sent).toInt()
                        val isLast = (sent + toSend) == totalPayloadBytes
                        val flags = if (isLast) (FLAG_DATA or FLAG_CLOSE) else FLAG_DATA
                        val payload = if (toSend == chunk.size) chunk else chunk.copyOf(toSend)
                        out.write(encodeFrame(1, flags, payload))
                        sent += toSend
                    }
                    out.flush()
                    clientFinished.await(15, TimeUnit.SECONDS)
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
        val dialObs = RecordingDialObserver()

        val client = openAuthenticatedClient(
            DirectEndpoint("127.0.0.1", serverSocket.localPort),
            fixture.credential,
            streamObserver = streamObs,
            directDialObserver = dialObs,
        )

        val receivedBytes = AtomicLong(0)
        val completeLatch = CountDownLatch(1)

        client.requestStreaming(
            method = "GET",
            path = "/large-journal",
            headers = listOf("Host" to "127.0.0.1"),
            bodySource = null,
            responseSink = object : BrowserResponseSink {
                override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                    assertEquals(200, status)
                }

                override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {
                    receivedBytes.addAndGet(length.toLong())
                }

                override fun onTrailers(trailers: List<Pair<String, String>>) {}

                override fun onComplete() {
                    completeLatch.countDown()
                }

                override fun onError(cause: Throwable) {
                    completeLatch.countDown()
                }
            },
        )

        clientFinished.countDown()
        assertTrue(completeLatch.await(15, TimeUnit.SECONDS), "Stream failed to complete within timeout")
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        assertEquals(totalPayloadBytes, receivedBytes.get())
        assertEquals(1, dialObs.dialedHosts.size)
        assertEquals("127.0.0.1", dialObs.dialedHosts[0])
        assertTrue(streamObs.bytesReceived.get() > 0)

        client.close()
    }

    @Test
    fun streamsChunkedResponseWithDigestTrailerAndFiltersDeclaredTrailers() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 10000 }
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 10000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    val reqFrame = readFrameFromStream(`in`)
                    assertTrue((reqFrame.flags and FLAG_OPEN) != 0)

                    val headers = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: Digest, X-Allowed\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA, headers))

                    val chunk1 = "5\r\nhello\r\n".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA, chunk1))

                    val chunk2 = "6\r\n world\r\n".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA, chunk2))

                    val trailers = "0\r\nDigest: SHA-256=4re8vU8y\r\nX-Allowed: yes\r\nX-Unallowed: evil\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, trailers))
                    out.flush()
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

        val client = openAuthenticatedClient(
            DirectEndpoint("127.0.0.1", serverSocket.localPort),
            fixture.credential,
        )

        val bodyAcc = ByteArrayOutputStream()
        val receivedTrailers = mutableListOf<Pair<String, String>>()
        val done = CountDownLatch(1)

        client.requestStreaming(
            method = "GET",
            path = "/chunked",
            headers = emptyList(),
            bodySource = null,
            responseSink = object : BrowserResponseSink {
                override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                    assertEquals(200, status)
                }

                override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {
                    bodyAcc.write(chunk, offset, length)
                }

                override fun onTrailers(trailers: List<Pair<String, String>>) {
                    receivedTrailers.addAll(trailers)
                }

                override fun onComplete() {
                    done.countDown()
                }

                override fun onError(cause: Throwable) {
                    done.countDown()
                }
            },
        )

        assertTrue(done.await(5, TimeUnit.SECONDS))
        serverDone.await(5, TimeUnit.SECONDS)
        assertEquals("hello world", bodyAcc.toString(Charsets.US_ASCII.name()))
        assertTrue(receivedTrailers.any { it.first.equals("Digest", ignoreCase = true) })
        assertTrue(receivedTrailers.any { it.first.equals("X-Allowed", ignoreCase = true) })
        assertFalse(receivedTrailers.any { it.first.equals("X-Unallowed", ignoreCase = true) })

        client.close()
    }

    @Test
    fun earlyInterim103HintsFollowedBy200Ok() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 10000 }
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 10000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    readFrameFromStream(`in`)

                    val hints = "HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA, hints))
                    out.flush()

                    Thread.sleep(20)

                    val ok = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, ok))
                    out.flush()
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

        val client = openAuthenticatedClient(
            DirectEndpoint("127.0.0.1", serverSocket.localPort),
            fixture.credential,
        )

        val statuses = mutableListOf<Int>()
        val done = CountDownLatch(1)

        client.requestStreaming(
            method = "GET",
            path = "/hints",
            headers = emptyList(),
            bodySource = null,
            responseSink = object : BrowserResponseSink {
                override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                    statuses.add(status)
                }

                override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
                override fun onTrailers(trailers: List<Pair<String, String>>) {}
                override fun onComplete() { done.countDown() }
                override fun onError(cause: Throwable) { done.countDown() }
            },
        )

        assertTrue(done.await(5, TimeUnit.SECONDS))
        serverDone.await(5, TimeUnit.SECONDS)
        assertEquals(listOf(103, 200), statuses)

        client.close()
    }

    @Test
    fun headAndSpecialStatusMatrixBehaviors() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 10000 }
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 10000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    // 1. HEAD
                    readFrameFromStream(`in`)
                    out.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray(Charsets.US_ASCII)))
                    out.flush()

                    // 2. 304 Not Modified
                    readFrameFromStream(`in`)
                    out.write(encodeFrame(3, FLAG_DATA or FLAG_CLOSE, "HTTP/1.1 304 Not Modified\r\nContent-Length: 50\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray(Charsets.US_ASCII)))
                    out.flush()

                    // 3. 205 Reset Content
                    readFrameFromStream(`in`)
                    out.write(encodeFrame(5, FLAG_DATA or FLAG_CLOSE, "HTTP/1.1 205 Reset Content\r\n\r\n".toByteArray(Charsets.US_ASCII)))
                    out.flush()
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

        val client = openAuthenticatedClient(
            DirectEndpoint("127.0.0.1", serverSocket.localPort),
            fixture.credential,
        )

        // HEAD test
        val headDone = CountDownLatch(1)
        var headStatus = 0
        var headCl: String? = null
        var headBytes = 0
        client.requestStreaming("HEAD", "/item", emptyList(), null, object : BrowserResponseSink {
            override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                headStatus = status
                headCl = headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }?.second
            }
            override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) { headBytes += length }
            override fun onTrailers(trailers: List<Pair<String, String>>) {}
            override fun onComplete() { headDone.countDown() }
            override fun onError(cause: Throwable) { headDone.countDown() }
        })
        assertTrue(headDone.await(5, TimeUnit.SECONDS))
        assertEquals(200, headStatus)
        assertEquals("100", headCl)
        assertEquals(0, headBytes)

        // 304 test
        val notModifiedDone = CountDownLatch(1)
        var notModHeaders: List<Pair<String, String>> = emptyList()
        client.requestStreaming("GET", "/item304", emptyList(), null, object : BrowserResponseSink {
            override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                notModHeaders = headers
            }
            override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
            override fun onTrailers(trailers: List<Pair<String, String>>) {}
            override fun onComplete() { notModifiedDone.countDown() }
            override fun onError(cause: Throwable) { notModifiedDone.countDown() }
        })
        assertTrue(notModifiedDone.await(5, TimeUnit.SECONDS))
        assertTrue(notModHeaders.any { it.first.equals("Content-Length", ignoreCase = true) })
        assertFalse(notModHeaders.any { it.first.equals("Transfer-Encoding", ignoreCase = true) })

        // 205 test
        val resetDone = CountDownLatch(1)
        var resetCl: String? = null
        client.requestStreaming("GET", "/item205", emptyList(), null, object : BrowserResponseSink {
            override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                resetCl = headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }?.second
            }
            override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
            override fun onTrailers(trailers: List<Pair<String, String>>) {}
            override fun onComplete() { resetDone.countDown() }
            override fun onError(cause: Throwable) { resetDone.countDown() }
        })
        assertTrue(resetDone.await(5, TimeUnit.SECONDS))
        assertEquals("0", resetCl)

        client.close()
    }

    @Test
    fun range206And200Responses() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 10000 }
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 10000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    // Stream 1: Range 206
                    readFrameFromStream(`in`)
                    val res206 = "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-4/10\r\nContent-Length: 5\r\n\r\nPART1".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, res206))
                    out.flush()

                    // Stream 2: Range 200 (Full content fallback)
                    readFrameFromStream(`in`)
                    val res200 = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nFULL".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(3, FLAG_DATA or FLAG_CLOSE, res200))
                    out.flush()
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

        val client = openAuthenticatedClient(
            DirectEndpoint("127.0.0.1", serverSocket.localPort),
            fixture.credential,
        )

        val res1 = client.request("GET", "/media", mapOf("Range" to "bytes=0-4"), null)
        assertEquals(206, res1.status)
        assertEquals("PART1", res1.bodyText())

        val res2 = client.request("GET", "/media", mapOf("Range" to "bytes=0-"), null)
        assertEquals(200, res2.status)
        assertEquals("FULL", res2.bodyText())

        client.close()
    }
}
