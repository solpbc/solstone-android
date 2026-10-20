// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.DirectDialObserver
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.FLAG_CLOSE
import app.solstone.core.pl.FLAG_DATA
import app.solstone.core.pl.FLAG_OPEN
import app.solstone.core.pl.FLAG_RESET
import app.solstone.core.pl.FLAG_RESERVED
import app.solstone.core.pl.FLAG_WINDOW
import app.solstone.core.pl.Frame
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.INITIAL_SEND_WINDOW
import app.solstone.core.pl.MAX_DATA_CHUNK_BYTES
import app.solstone.core.pl.MuxSession
import app.solstone.core.pl.PlStreamObserver
import app.solstone.core.pl.SESSION_UNUSABLE
import app.solstone.core.pl.StreamingBody
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.core.pl.encodeFrame
import app.solstone.core.pl.encodeWindowCredit
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
                    readFrameFromStream(`in`)
                    out.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray(Charsets.US_ASCII)))
                    out.flush()

                    // 2. 304 Not Modified
                    readFrameFromStream(`in`)
                    readFrameFromStream(`in`)
                    out.write(encodeFrame(3, FLAG_DATA or FLAG_CLOSE, "HTTP/1.1 304 Not Modified\r\nContent-Length: 50\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray(Charsets.US_ASCII)))
                    out.flush()

                    // 3. 205 Reset Content
                    readFrameFromStream(`in`)
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
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
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

    @Test
    fun streamingThreeMegabyteUploadWithCreditStallAndVerification() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 15000 }
        val serverDone = CountDownLatch(1)
        val peerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val totalBytes = 3 * 1024 * 1024L // 3145728

        val bodyData = ByteArray(totalBytes.toInt()) { (it % 251).toByte() }
        val sourceReadCount = AtomicLong(0)
        val bodyCloseCount = AtomicInteger(0)

        val body = object : StreamingBody {
            override val contentLength: Long = totalBytes
            private var sourceOffset = 0
            override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                if (sourceOffset >= bodyData.size) return -1
                val toRead = minOf(length, bodyData.size - sourceOffset)
                System.arraycopy(bodyData, sourceOffset, target, offset, toRead)
                sourceOffset += toRead
                sourceReadCount.addAndGet(toRead.toLong())
                return toRead
            }
            override fun close() {
                bodyCloseCount.incrementAndGet()
            }
        }

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 15000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    // 1. Read OPEN frame
                    val openFrame = readFrameFromStream(`in`)
                    assertEquals(1, openFrame.streamId)
                    assertTrue((openFrame.flags and FLAG_OPEN) != 0)
                    val openPayload = openFrame.payload.toString(Charsets.US_ASCII)
                    assertEquals(1, openPayload.split("\r\n").count { it.startsWith("Content-Length:", ignoreCase = true) })
                    assertTrue(openPayload.contains("Content-Length: 3145728\r\n"))
                    assertFalse(openPayload.contains("Transfer-Encoding", ignoreCase = true))
                    assertTrue(openPayload.contains("accept: application/json", ignoreCase = true))

                    // 2. Read body frames up to INITIAL_SEND_WINDOW
                    val receivedBytes = ByteArrayOutputStream()
                    var frameCount = 0
                    while (receivedBytes.size() < INITIAL_SEND_WINDOW) {
                        val frame = readFrameFromStream(`in`)
                        assertEquals(1, frame.streamId)
                        assertEquals(FLAG_DATA, frame.flags)
                        receivedBytes.write(frame.payload)
                        frameCount++
                    }
                    assertTrue(frameCount > 1, "Expected body to arrive in multiple frames")

                    // 3. Credit stall check: poll for 300ms, no more frames
                    sslSocket.soTimeout = 300
                    var extraFrame: Frame? = null
                    try {
                        extraFrame = readFrameFromStream(`in`)
                    } catch (_: java.net.SocketTimeoutException) {
                        // Expected stall
                    }
                    sslSocket.soTimeout = 15000
                    assertEquals(null, extraFrame, "Expected stall, but received unexpected frame")
                    val bytesReadBeforeCredit = sourceReadCount.get()
                    assertTrue(bytesReadBeforeCredit <= INITIAL_SEND_WINDOW + MAX_DATA_CHUNK_BYTES,
                        "Source read count $bytesReadBeforeCredit exceeded initial window + chunk bytes")

                    // 4. Grant WINDOW credit
                    val creditNeeded = (totalBytes - receivedBytes.size() + 65536).toInt()
                    out.write(encodeFrame(1, FLAG_WINDOW, encodeWindowCredit(creditNeeded)))
                    out.flush()

                    // 5. Read remaining frames until CLOSE
                    var closeSeen = false
                    while (!closeSeen) {
                        val frame = readFrameFromStream(`in`)
                        assertEquals(1, frame.streamId)
                        if ((frame.flags and FLAG_DATA) != 0) {
                            receivedBytes.write(frame.payload)
                        }
                        if ((frame.flags and FLAG_CLOSE) != 0) {
                            closeSeen = true
                        }
                    }

                    assertEquals(totalBytes.toInt(), receivedBytes.size())
                    assertTrue(bodyData.contentEquals(receivedBytes.toByteArray()))

                    // Send 200 OK
                    val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, resp))
                    out.flush()
                } finally {
                    sslSocket.close()
                }
            } catch (t: Throwable) {
                peerError.set(t)
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

        val resp = client.requestStreaming(
            method = "POST",
            path = "/upload-3mb",
            headers = emptyMap(),
            body = body,
        )

        assertEquals(200, resp.status)
        assertEquals("OK", resp.bodyText())
        assertEquals(1, bodyCloseCount.get())

        client.close()
        assertTrue(serverDone.await(15, TimeUnit.SECONDS))
        peerError.get()?.let { throw it }
    }

    @Test
    fun early413WhileParkedOnCreditDoesNotPoisonAndFollowupSucceeds() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 15000 }
        val serverDone = CountDownLatch(1)
        val peerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val totalBytes = 3 * 1024 * 1024L

        val sourceBytesRead = AtomicLong(0)
        val bodyCloseCount = AtomicInteger(0)
        val body = object : StreamingBody {
            override val contentLength: Long = totalBytes
            override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                sourceBytesRead.addAndGet(length.toLong())
                return length
            }
            override fun close() {
                bodyCloseCount.incrementAndGet()
            }
        }

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 15000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    // Read stream 1 OPEN
                    val openFrame = readFrameFromStream(`in`)
                    assertEquals(1, openFrame.streamId)

                    // Read body up to INITIAL_SEND_WINDOW
                    var bytesReceived = 0
                    while (bytesReceived < INITIAL_SEND_WINDOW) {
                        val frame = readFrameFromStream(`in`)
                        bytesReceived += frame.payload.size
                    }

                    // Write 413 Payload Too Large immediately without reading another frame
                    val resp413 = "HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, resp413))
                    out.flush()

                    // Peer then reads RESET and does not see CLOSE on stream 1
                    val resetFrame = readFrameFromStream(`in`)
                    assertEquals(1, resetFrame.streamId)
                    assertEquals(FLAG_RESET, resetFrame.flags)
                    assertEquals(byteArrayOf(0x05).toList(), resetFrame.payload.toList())

                    // Serve following client.request on stream 3
                    val open3 = readFrameFromStream(`in`)
                    assertEquals(3, open3.streamId)
                    val close3 = readFrameFromStream(`in`)
                    assertEquals(FLAG_CLOSE, close3.flags)
                    val resp200 = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(3, FLAG_DATA or FLAG_CLOSE, resp200))
                    out.flush()
                } finally {
                    sslSocket.close()
                }
            } catch (t: Throwable) {
                peerError.set(t)
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

        val resp = client.requestStreaming(
            method = "POST",
            path = "/early-413",
            headers = emptyMap(),
            body = body,
        )

        assertEquals(413, resp.status)
        val bytesAtReturn = sourceBytesRead.get()
        Thread.sleep(50)
        assertEquals(bytesAtReturn, sourceBytesRead.get(), "Source was read after return")
        assertEquals(1, bodyCloseCount.get())
        assertFalse(client.isPoisoned)

        // Followup request on same client
        val followupResp = client.request("GET", "/after-413", emptyMap(), null)
        assertEquals(200, followupResp.status)
        assertEquals("OK", followupResp.bodyText())

        client.close()
        assertTrue(serverDone.await(15, TimeUnit.SECONDS))
        peerError.get()?.let { throw it }
    }

    @Test
    fun badSourcesMatrixWithWindowResetNotPoisonedAndFollowupSucceeds() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 15000 }
        val serverDone = CountDownLatch(1)
        val peerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val declaredLength = INITIAL_SEND_WINDOW + 1000L

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 15000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    var streamId = 1
                    for (faultIndex in 1..3) {
                        // Read OPEN
                        val openFrame = readFrameFromStream(`in`)
                        assertEquals(streamId, openFrame.streamId)

                        // Read initial window
                        var bytesReceived = 0L
                        while (bytesReceived < INITIAL_SEND_WINDOW) {
                            val frame = readFrameFromStream(`in`)
                            bytesReceived += frame.payload.size
                        }

                        // Grant window
                        out.write(encodeFrame(streamId, FLAG_WINDOW, encodeWindowCredit(2000)))
                        out.flush()

                        // Read until RESET
                        var closeSeen = false
                        while (true) {
                            val frame = readFrameFromStream(`in`)
                            assertEquals(streamId, frame.streamId)
                            if ((frame.flags and FLAG_DATA) != 0) {
                                bytesReceived += frame.payload.size
                            }
                            if ((frame.flags and FLAG_CLOSE) != 0) {
                                closeSeen = true
                            }
                            if (frame.flags == FLAG_RESET) {
                                assertEquals(byteArrayOf(0x05).toList(), frame.payload.toList())
                                break
                            }
                        }
                        assertTrue(bytesReceived < declaredLength, "Peer saw all declared bytes before reset")
                        assertFalse(closeSeen, "Peer should never see CLOSE on bad source")

                        // Following client.request on next stream
                        streamId += 2
                        val followOpen = readFrameFromStream(`in`)
                        assertEquals(streamId, followOpen.streamId)
                        val followClose = readFrameFromStream(`in`)
                        assertEquals(FLAG_CLOSE, followClose.flags)
                        val ok = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII)
                        out.write(encodeFrame(streamId, FLAG_DATA or FLAG_CLOSE, ok))
                        out.flush()

                        streamId += 2
                    }
                } finally {
                    sslSocket.close()
                }
            } catch (t: Throwable) {
                peerError.set(t)
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

        val executor = Executors.newSingleThreadExecutor()
        try {
            // Fault 1: short by 1 byte
            val shortBodyClose = AtomicInteger(0)
            val shortBody = object : StreamingBody {
                override val contentLength: Long = declaredLength
                var produced = 0L
                override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                    val remaining = (declaredLength - 1) - produced
                    if (remaining <= 0) return -1
                    val toRead = minOf(length.toLong(), remaining).toInt()
                    produced += toRead
                    return toRead
                }
                override fun close() { shortBodyClose.incrementAndGet() }
            }
            val future1 = executor.submit<HttpResponse> {
                client.requestStreaming("POST", "/bad1", emptyMap(), shortBody)
            }
            try {
                val err = assertFailsWith<ExecutionException> { future1.get(5, TimeUnit.SECONDS) }
                assertTrue(err.cause is IOException)
            } catch (e: TimeoutException) {
                client.close()
                throw e
            }
            assertEquals(1, shortBodyClose.get())
            assertFalse(client.isPoisoned)
            val ok1 = client.request("GET", "/ok1", emptyMap(), null)
            assertEquals(200, ok1.status)

            // Fault 2: long by 1 byte
            val longBodyClose = AtomicInteger(0)
            val longBody = object : StreamingBody {
                override val contentLength: Long = declaredLength
                var produced = 0L
                override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                    val remaining = declaredLength - produced
                    if (remaining > 0) {
                        val toRead = minOf(length.toLong(), remaining).toInt()
                        produced += toRead
                        return toRead
                    }
                    // When asked for EOS, return 1 positive byte instead of -1
                    return 1
                }
                override fun close() { longBodyClose.incrementAndGet() }
            }
            val future2 = executor.submit<HttpResponse> {
                client.requestStreaming("POST", "/bad2", emptyMap(), longBody)
            }
            try {
                val err = assertFailsWith<ExecutionException> { future2.get(5, TimeUnit.SECONDS) }
                assertTrue(err.cause is IOException)
            } catch (e: TimeoutException) {
                client.close()
                throw e
            }
            assertEquals(1, longBodyClose.get())
            assertFalse(client.isPoisoned)
            val ok2 = client.request("GET", "/ok2", emptyMap(), null)
            assertEquals(200, ok2.status)

            // Fault 3: throws after grant
            val throwingBodyClose = AtomicInteger(0)
            val throwingBody = object : StreamingBody {
                override val contentLength: Long = declaredLength
                var produced = 0L
                override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                    if (produced >= INITIAL_SEND_WINDOW + 500) {
                        throw IOException("source exploded after grant")
                    }
                    val toRead = minOf(length.toLong(), (INITIAL_SEND_WINDOW + 500) - produced).toInt()
                    produced += toRead
                    return toRead
                }
                override fun close() { throwingBodyClose.incrementAndGet() }
            }
            val future3 = executor.submit<HttpResponse> {
                client.requestStreaming("POST", "/bad3", emptyMap(), throwingBody)
            }
            try {
                val err = assertFailsWith<ExecutionException> { future3.get(5, TimeUnit.SECONDS) }
                assertTrue(err.cause is IOException)
            } catch (e: TimeoutException) {
                client.close()
                throw e
            }
            assertEquals(1, throwingBodyClose.get())
            assertFalse(client.isPoisoned)
            val ok3 = client.request("GET", "/ok3", emptyMap(), null)
            assertEquals(200, ok3.status)
        } finally {
            executor.shutdownNow()
        }

        client.close()
        assertTrue(serverDone.await(15, TimeUnit.SECONDS))
        peerError.get()?.let { throw it }
    }

    @Test
    fun webViewPostThroughJournalBrowserSessionToConscryptClient() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 15000 }
        val serverDone = CountDownLatch(1)
        val peerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 15000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    val openFrame = readFrameFromStream(`in`)
                    assertEquals(1, openFrame.streamId)
                    val openPayload = openFrame.payload.toString(Charsets.US_ASCII)
                    assertTrue(openPayload.contains("Content-Length: 5\r\n"))
                    assertFalse(openPayload.contains("Transfer-Encoding", ignoreCase = true))

                    val dataFrame = readFrameFromStream(`in`)
                    assertEquals(1, dataFrame.streamId)
                    assertEquals("hello", dataFrame.payload.toString(Charsets.US_ASCII))

                    val closeFrame = readFrameFromStream(`in`)
                    assertEquals(FLAG_CLOSE, closeFrame.flags)

                    val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, resp))
                    out.flush()
                } finally {
                    sslSocket.close()
                }
            } catch (t: Throwable) {
                peerError.set(t)
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

        val session = JournalBrowserSession(
            pairing = { PairingGeneration("inst1", "sha256:client") },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = client.isPoisoned
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse = error("Not used")
                    override fun requestStreaming(
                        method: String,
                        path: String,
                        headers: List<Pair<String, String>>,
                        bodySource: BrowserRequestBodySource?,
                        responseSink: BrowserResponseSink,
                    ) {
                        client.requestStreaming(method, path, headers, bodySource, responseSink)
                    }
                    override fun close() {
                        client.close()
                    }
                }
            },
            diag = {},
        )
        val origin = session.start()
        val uri = URI(origin.url)

        val socket = Socket("127.0.0.1", uri.port)
        val out = socket.getOutputStream()
        val `in` = socket.getInputStream()
        out.write("POST /post HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nContent-Length: 5\r\n\r\nhello".toByteArray(Charsets.US_ASCII))
        out.flush()

        val respText = String(`in`.readBytes(), Charsets.US_ASCII)
        assertTrue(respText.contains("200 OK"))
        assertTrue(respText.contains("OK"))
        socket.close()
        session.stop()

        client.close()
        assertTrue(serverDone.await(15, TimeUnit.SECONDS))
        peerError.get()?.let { throw it }
    }

    @Test
    fun callerSetAcceptPreservedPoisonedClientAndIntMaxPlusOneStreaming() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 15000 }
        val serverDone = CountDownLatch(1)
        val peerError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

        val serverThread = Thread {
            try {
                val sslSocket = serverSocket.accept() as SSLSocket
                sslSocket.soTimeout = 15000
                try {
                    sslSocket.startHandshake()
                    val `in` = BufferedInputStream(sslSocket.getInputStream())
                    val out = BufferedOutputStream(sslSocket.getOutputStream())

                    // 1. Caller-set Accept on stream 1
                    val open1 = readFrameFromStream(`in`)
                    val open1Payload = open1.payload.toString(Charsets.US_ASCII)
                    assertTrue(open1Payload.contains("Accept: text/plain", ignoreCase = true))
                    assertFalse(open1Payload.contains("application/json"))
                    readFrameFromStream(`in`) // body
                    readFrameFromStream(`in`) // close
                    val resp200 = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII)
                    out.write(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, resp200))
                    out.flush()

                    // 2. contentLength = Int.MAX_VALUE + 1 on stream 3
                    val open3 = readFrameFromStream(`in`)
                    assertEquals(3, open3.streamId)
                    val open3Payload = open3.payload.toString(Charsets.US_ASCII)
                    assertTrue(open3Payload.contains("Content-Length: 2147483648\r\n"))
                    val reset3 = readFrameFromStream(`in`)
                    assertEquals(3, reset3.streamId)
                    assertEquals(FLAG_RESET, reset3.flags)
                    assertEquals(byteArrayOf(0x05).toList(), reset3.payload.toList())

                    // 3. Poison the session: on stream 5, send FLAG_RESERVED
                    val open5 = readFrameFromStream(`in`)
                    assertEquals(5, open5.streamId)
                    readFrameFromStream(`in`) // close
                    out.write(encodeFrame(5, FLAG_RESERVED or FLAG_DATA, ByteArray(16)))
                    out.flush()
                } finally {
                    sslSocket.close()
                }
            } catch (t: Throwable) {
                peerError.set(t)
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

        // Subcase 1: Preserved caller-set Accept
        val customBody = object : StreamingBody {
            override val contentLength: Long = 2L
            private var read = false
            override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                if (read) return -1
                target[offset] = 'o'.code.toByte()
                target[offset + 1] = 'k'.code.toByte()
                read = true
                return 2
            }
            override fun close() {}
        }
        val customResp = client.requestStreaming(
            method = "POST",
            path = "/accept",
            headers = mapOf("Accept" to "text/plain"),
            body = customBody,
        )
        assertEquals(200, customResp.status)

        // Subcase 2: contentLength = Int.MAX_VALUE + 1 short immediately
        val bigBodyReadCalled = AtomicBoolean(false)
        val bigBodyCloseCount = AtomicInteger(0)
        val bigBody = object : StreamingBody {
            override val contentLength: Long = Int.MAX_VALUE.toLong() + 1L
            override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                bigBodyReadCalled.set(true)
                return -1
            }
            override fun close() {
                bigBodyCloseCount.incrementAndGet()
            }
        }
        assertFailsWith<IOException> {
            client.requestStreaming("POST", "/big", emptyMap(), bigBody)
        }
        assertTrue(bigBodyReadCalled.get(), "readChunk should have been called (not buffering default)")
        assertEquals(1, bigBodyCloseCount.get())
        assertFalse(client.isPoisoned)

        // Subcase 3: Poisoned client
        assertFailsWith<IOException> {
            client.request("GET", "/trigger-poison", emptyMap(), null)
        }
        assertTrue(client.isPoisoned)

        val poisonedBodyReadCalled = AtomicBoolean(false)
        val poisonedBodyCloseCount = AtomicInteger(0)
        val poisonedBody = object : StreamingBody {
            override val contentLength: Long = 10L
            override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                poisonedBodyReadCalled.set(true)
                return -1
            }
            override fun close() {
                poisonedBodyCloseCount.incrementAndGet()
            }
        }
        val err = assertFailsWith<IOException> {
            client.requestStreaming("POST", "/after-poison", emptyMap(), poisonedBody)
        }
        assertTrue(err.message?.contains(SESSION_UNUSABLE) == true, "Expected message to contain '$SESSION_UNUSABLE'")
        assertFalse(poisonedBodyReadCalled.get(), "readChunk must not be called when poisoned")
        assertEquals(1, poisonedBodyCloseCount.get(), "body.close() must be called once")

        client.close()
        assertTrue(serverDone.await(15, TimeUnit.SECONDS))
        peerError.get()?.let { throw it }
    }
}
