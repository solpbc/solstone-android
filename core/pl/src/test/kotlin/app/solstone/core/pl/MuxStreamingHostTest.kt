// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuxStreamingHostTest {

    @Test
    fun streamsRequestAndResponseProgressively() {
        val (client, server) = pairedDuplexes(bufferSize = 128 * 1024)
        val bodySent = "test stream body".encodeToByteArray()

        val serverDone = CountDownLatch(1)
        val homeThread = thread(start = true) {
            server.use { duplex ->
                val openFrame = readFrame(duplex.input)
                assertEquals(1, openFrame.streamId)
                assertTrue((openFrame.flags and FLAG_OPEN) != 0)
                assertTrue((openFrame.flags and FLAG_DATA) != 0)
                val openPayload = openFrame.payload.toString(Charsets.US_ASCII)
                assertTrue(openPayload.contains("Content-Length: ${bodySent.size}"))
                assertFalse(openPayload.contains("Transfer-Encoding", ignoreCase = true))

                val bodyFrame = readFrame(duplex.input)
                assertEquals(1, bodyFrame.streamId)
                assertEquals(FLAG_DATA, bodyFrame.flags)
                assertEquals("test stream body", bodyFrame.payload.toString(Charsets.US_ASCII))

                val closeFrame = readFrame(duplex.input)
                assertEquals(FLAG_CLOSE, closeFrame.flags)

                // Send response in progressive chunks
                val resHeaders = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA, resHeaders)

                // Small chunks draw no WINDOW: credit comes back only at half the window.
                assertTrue(pollFrame(duplex.input, iterations = 20) == null)

                val chunk1 = "Hello, ".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA, chunk1)

                assertTrue(pollFrame(duplex.input, iterations = 20) == null)

                val chunk2 = "streaming world!".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA or FLAG_CLOSE, chunk2)
                serverDone.countDown()
            }
        }

        val receivedStatus = AtomicInteger(0)
        val receivedBody = ByteArrayOutputStream()
        val completed = AtomicBoolean(false)
        val sinkLatch = CountDownLatch(1)

        val sink = object : BrowserResponseSink {
            override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                receivedStatus.set(status)
            }

            override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {
                receivedBody.write(chunk, offset, length)
            }

            override fun onComplete() {
                completed.set(true)
                sinkLatch.countDown()
            }

            override fun onError(cause: Throwable) {
                sinkLatch.countDown()
            }
        }

        val bais = ByteArrayInputStream(bodySent)
        val bodySource = object : BrowserRequestBodySource {
            override val contentLength: Long = bodySent.size.toLong()
            override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                return bais.read(target, offset, length)
            }
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            session.requestStreaming(
                method = "POST",
                path = "/stream-endpoint",
                headers = listOf("Content-Type" to "text/plain"),
                bodySource = bodySource,
                responseSink = sink,
            )
        }

        assertTrue(sinkLatch.await(5, TimeUnit.SECONDS))
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        homeThread.join(5000)

        assertEquals(200, receivedStatus.get())
        assertEquals("Hello, streaming world!", receivedBody.toString(Charsets.US_ASCII))
        assertTrue(completed.get())
    }

    @Test
    fun sessionTerminalOnReservedFlagBeforeAllocation() {
        val (client, server) = pairedDuplexes()
        val homeThread = thread(start = true) {
            server.use { duplex ->
                readFrame(duplex.input) // open frame
                readFrame(duplex.input) // close frame
                // Send frame with reserved flag bit 0x80
                sendFrame(duplex, 1, FLAG_RESERVED or FLAG_DATA, ByteArray(16))
            }
        }

        val sink = object : BrowserResponseSink {
            override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {}
            override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
            override fun onComplete() {}
            override fun onError(cause: Throwable) {}
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            val err = assertFailsWith<IOException> {
                session.requestStreaming("GET", "/test", emptyList(), null, sink)
            }
            assertTrue(err.message?.contains("reserved") == true)
            assertTrue(session.isPoisoned)
        }
        homeThread.join(5000)
    }

    @Test
    fun nullAndNegativeContentLengthBrowserSourceFailsBeforeFrame() {
        val (client, server) = pairedDuplexes()
        val sink = object : BrowserResponseSink {
            override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {}
            override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
            override fun onComplete() {}
            override fun onError(cause: Throwable) {}
        }

        client.use { duplex ->
            val session = MuxSession(duplex)

            // null contentLength (default)
            val nullSource = BrowserRequestBodySource { target, offset, length -> -1 }
            assertFailsWith<IOException> {
                session.requestStreaming("POST", "/test", emptyList(), nullSource, sink)
            }
            assertEquals(0, server.input.available())

            // negative contentLength
            val negSource = object : BrowserRequestBodySource {
                override val contentLength: Long = -1L
                override fun readChunk(target: ByteArray, offset: Int, length: Int): Int = -1
            }
            assertFailsWith<IOException> {
                session.requestStreaming("POST", "/test", emptyList(), negSource, sink)
            }
            assertEquals(0, server.input.available())
        }
    }

    @Test
    fun threadIdentityDuplexAndSourceOnCallingThread() {
        val (client, server) = pairedDuplexes(bufferSize = 128 * 1024)
        val testThread = Thread.currentThread()
        val threadsSeen = java.util.Collections.synchronizedSet(mutableSetOf<Thread>())

        val wrappedClient = object : ByteDuplex {
            override val input: InputStream = object : InputStream() {
                override fun read(): Int {
                    threadsSeen.add(Thread.currentThread())
                    return client.input.read()
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    threadsSeen.add(Thread.currentThread())
                    return client.input.read(b, off, len)
                }
                override fun close() = client.input.close()
            }
            override val output: OutputStream = object : OutputStream() {
                override fun write(b: Int) {
                    threadsSeen.add(Thread.currentThread())
                    client.output.write(b)
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    threadsSeen.add(Thread.currentThread())
                    client.output.write(b, off, len)
                }
                override fun flush() = client.output.flush()
                override fun close() = client.output.close()
            }
            override fun close() = client.close()
        }

        val serverDone = CountDownLatch(1)
        val peerThread = thread(start = true) {
            server.use { duplex ->
                // Call 1: browser overload
                readFrame(duplex.input)
                readFrame(duplex.input)
                readFrame(duplex.input)
                val resp1 = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA or FLAG_CLOSE, resp1)

                // Call 2: HttpResponse overload
                readFrame(duplex.input)
                readFrame(duplex.input)
                readFrame(duplex.input)
                val resp2 = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nDONE".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 3, FLAG_DATA or FLAG_CLOSE, resp2)

                serverDone.countDown()
            }
        }

        wrappedClient.use { duplex ->
            val session = MuxSession(duplex)

            // Browser overload
            val body1Bytes = "b1".encodeToByteArray()
            val bais1 = ByteArrayInputStream(body1Bytes)
            val browserSource = object : BrowserRequestBodySource {
                override val contentLength: Long = 2L
                override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                    threadsSeen.add(Thread.currentThread())
                    return bais1.read(target, offset, length)
                }
            }
            val sinkLatch = CountDownLatch(1)
            val sink = object : BrowserResponseSink {
                override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {}
                override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
                override fun onComplete() { sinkLatch.countDown() }
                override fun onError(cause: Throwable) { sinkLatch.countDown() }
            }
            session.requestStreaming("POST", "/b1", emptyList(), browserSource, sink)
            assertTrue(sinkLatch.await(5, TimeUnit.SECONDS))

            // HttpResponse overload
            val body2Bytes = "b2".encodeToByteArray()
            val bais2 = ByteArrayInputStream(body2Bytes)
            val resp = session.requestStreaming(
                method = "POST",
                path = "/b2",
                headers = emptyList(),
                contentLength = 2L,
                readChunk = { t, o, l ->
                    threadsSeen.add(Thread.currentThread())
                    bais2.read(t, o, l)
                },
            )
            assertEquals(200, resp.status)
            assertEquals("DONE", resp.body.toString(Charsets.US_ASCII))
        }

        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        peerThread.join(5000)

        for (t in threadsSeen) {
            assertEquals(testThread, t)
        }
    }

    @Test
    fun httpResponseStripsCallerClAndTeInHead() {
        val (client, server) = pairedDuplexes()
        val serverDone = CountDownLatch(1)
        val peerThread = thread(start = true) {
            server.use { duplex ->
                val openFrame = readFrame(duplex.input)
                val openPayload = openFrame.payload.toString(Charsets.US_ASCII)
                assertEquals(1, openPayload.split("\r\n").count { it.startsWith("Content-Length:", ignoreCase = true) })
                assertTrue(openPayload.contains("Content-Length: 4\r\n"))
                assertFalse(openPayload.contains("Transfer-Encoding", ignoreCase = true))

                readFrame(duplex.input) // body frame
                readFrame(duplex.input) // close frame

                val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA or FLAG_CLOSE, resp)
                serverDone.countDown()
            }
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            val bais = ByteArrayInputStream("test".toByteArray(Charsets.US_ASCII))
            val resp = session.requestStreaming(
                method = "POST",
                path = "/strip-headers",
                headers = listOf("CoNtEnT-LeNgTh" to "1", "Transfer-Encoding" to "chunked"),
                contentLength = 4L,
                readChunk = { t, o, l -> bais.read(t, o, l) },
            )
            assertEquals(200, resp.status)
        }
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        peerThread.join(5000)
    }

    @Test
    fun httpResponseHandlesInterim103EarlyHintsThen200() {
        val (client, server) = pairedDuplexes()
        val serverDone = CountDownLatch(1)
        val peerThread = thread(start = true) {
            server.use { duplex ->
                readFrame(duplex.input) // open
                readFrame(duplex.input) // close
                val resp103 = "HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\n".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA, resp103)
                val resp200 = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA or FLAG_CLOSE, resp200)
                serverDone.countDown()
            }
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            val resp = session.requestStreaming(
                method = "GET",
                path = "/hints",
                headers = emptyList(),
                contentLength = 0L,
                readChunk = { _, _, _ -> -1 },
            )
            assertEquals(200, resp.status)
            assertTrue(resp.headers.containsKey("content-type"))
            assertFalse(resp.headers.containsKey("link"))
            assertEquals("{}", resp.body.toString(Charsets.US_ASCII))
        }
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        peerThread.join(5000)
    }

    @Test
    fun httpResponseBodyOverMaxResponseBytesThrowsAndResetsNotPoisoned() {
        val (client, server) = pairedDuplexes()
        val serverDone = CountDownLatch(1)
        val resetReceived = AtomicBoolean(false)
        val peerThread = thread(start = true) {
            server.use { duplex ->
                readFrame(duplex.input) // open
                readFrame(duplex.input) // close
                val respHead = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA, respHead)
                val bodyPart = "123456".toByteArray(Charsets.US_ASCII) // 6 bytes > max 5
                sendFrame(duplex, 1, FLAG_DATA, bodyPart)

                val frame = readFrame(duplex.input)
                if (frame.flags == FLAG_RESET && frame.payload.contentEquals(byteArrayOf(0x05))) {
                    resetReceived.set(true)
                }
                serverDone.countDown()
            }
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            val err = assertFailsWith<IOException> {
                session.requestStreaming(
                    method = "GET",
                    path = "/overflow",
                    headers = emptyList(),
                    contentLength = 0L,
                    readChunk = { _, _, _ -> -1 },
                    maxResponseBytes = 5,
                )
            }
            assertTrue(err.message?.contains("maxResponseBytes") == true || err.message?.contains("body") == true)
            assertFalse(session.isPoisoned)
        }
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        assertTrue(resetReceived.get())
        peerThread.join(5000)
    }

    @Test
    fun httpResponsePeerCloseBeforeHeadFinishesThrows() {
        val (client, server) = pairedDuplexes()
        val serverDone = CountDownLatch(1)
        val peerThread = thread(start = true) {
            server.use { duplex ->
                readFrame(duplex.input) // open
                readFrame(duplex.input) // close
                val partialHead = "HTTP/1.1 200 OK\r\nContent-Type: text".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA or FLAG_CLOSE, partialHead)
                serverDone.countDown()
            }
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            assertFailsWith<IOException> {
                session.requestStreaming(
                    method = "GET",
                    path = "/unfinished-head",
                    headers = emptyList(),
                    contentLength = 0L,
                    readChunk = { _, _, _ -> -1 },
                )
            }
        }
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        peerThread.join(5000)
    }

    @Test
    fun httpResponsePeerCloseWithShortBodyThrows() {
        val (client, server) = pairedDuplexes()
        val serverDone = CountDownLatch(1)
        val peerThread = thread(start = true) {
            server.use { duplex ->
                readFrame(duplex.input) // open
                readFrame(duplex.input) // close
                val resp = "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n12345".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA or FLAG_CLOSE, resp)
                serverDone.countDown()
            }
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            assertFailsWith<IOException> {
                session.requestStreaming(
                    method = "GET",
                    path = "/short-body",
                    headers = emptyList(),
                    contentLength = 0L,
                    readChunk = { _, _, _ -> -1 },
                )
            }
        }
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        peerThread.join(5000)
    }

    @Test
    fun httpResponseChunkedFinalResponseThrows() {
        val (client, server) = pairedDuplexes()
        val serverDone = CountDownLatch(1)
        val peerThread = thread(start = true) {
            server.use { duplex ->
                readFrame(duplex.input) // open
                readFrame(duplex.input) // close
                val resp = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA or FLAG_CLOSE, resp)
                serverDone.countDown()
            }
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            assertFailsWith<IOException> {
                session.requestStreaming(
                    method = "GET",
                    path = "/chunked-resp",
                    headers = emptyList(),
                    contentLength = 0L,
                    readChunk = { _, _, _ -> -1 },
                )
            }
        }
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        peerThread.join(5000)
    }

    @Test
    fun browserShortSourceSendsResetNotPoisonedFollowupSucceeds() {
        val (client, server) = pairedDuplexes(bufferSize = 128 * 1024)
        val executor = Executors.newSingleThreadExecutor()
        val serverDone = CountDownLatch(1)
        val resetSeen = AtomicBoolean(false)
        val closeSeen = AtomicBoolean(false)
        val bodyBytesSeen = AtomicInteger(0)

        val peerThread = thread(start = true) {
            server.use { duplex ->
                // Request 1: short source
                val open = readFrame(duplex.input)
                assertEquals(1, open.streamId)
                while (true) {
                    val frame = readFrame(duplex.input)
                    if ((frame.flags and FLAG_DATA) != 0) {
                        bodyBytesSeen.addAndGet(frame.payload.size)
                    }
                    if ((frame.flags and FLAG_CLOSE) != 0) {
                        closeSeen.set(true)
                    }
                    if (frame.flags == FLAG_RESET) {
                        assertEquals(byteArrayOf(0x05).toList(), frame.payload.toList())
                        resetSeen.set(true)
                        break
                    }
                }

                // Request 2: followup bodiless
                val open2 = readFrame(duplex.input)
                assertEquals(3, open2.streamId)
                val close2 = readFrame(duplex.input)
                assertEquals(FLAG_CLOSE, close2.flags)
                val resp2 = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 3, FLAG_DATA or FLAG_CLOSE, resp2)
                serverDone.countDown()
            }
        }

        try {
            client.use { duplex ->
                val session = MuxSession(duplex)

                val bais = ByteArrayInputStream(ByteArray(50) { 1 })
                val shortSource = object : BrowserRequestBodySource {
                    override val contentLength: Long = 100L
                    override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
                        return bais.read(target, offset, length)
                    }
                }

                val sink1 = object : BrowserResponseSink {
                    override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {}
                    override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
                    override fun onComplete() {}
                    override fun onError(cause: Throwable) {}
                }

                val future1 = executor.submit<Unit> {
                    session.requestStreaming("POST", "/short", emptyList(), shortSource, sink1)
                }

                val err = assertFailsWith<ExecutionException> {
                    future1.get(5, TimeUnit.SECONDS)
                }
                assertTrue(err.cause is IOException)
                assertFalse(session.isPoisoned)

                // Followup bodiless request
                val sink2Complete = CountDownLatch(1)
                var sink2Status = 0
                val sink2 = object : BrowserResponseSink {
                    override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                        sink2Status = status
                    }
                    override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
                    override fun onComplete() { sink2Complete.countDown() }
                    override fun onError(cause: Throwable) { sink2Complete.countDown() }
                }
                session.requestStreaming("GET", "/followup", emptyList(), null, sink2)
                assertTrue(sink2Complete.await(5, TimeUnit.SECONDS))
                assertEquals(200, sink2Status)
            }
        } finally {
            executor.shutdownNow()
        }

        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        peerThread.join(5000)
        assertEquals(50, bodyBytesSeen.get())
        assertTrue(resetSeen.get())
        assertFalse(closeSeen.get())
    }

    private fun sendFrame(duplex: TestDuplex, streamId: Int, flags: Int, payload: ByteArray) {
        duplex.output.write(encodeFrame(streamId, flags, payload))
        duplex.output.flush()
    }

    private fun readFrame(input: InputStream): Frame {
        val header = readExactly(input, 8)
        val length = ((header[5].toInt() and 0xff) shl 16) or
            ((header[6].toInt() and 0xff) shl 8) or
            (header[7].toInt() and 0xff)
        val payload = readExactly(input, length)
        return decodeFrame(header + payload, 0).frame
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) throw IOException("stream closed")
            offset += read
        }
        return out
    }

    private fun pollFrame(input: InputStream, iterations: Int = 100): Frame? {
        for (i in 0 until iterations) {
            if (input.available() >= 8) {
                return readFrame(input)
            }
            Thread.sleep(10)
        }
        return null
    }

    private fun pairedDuplexes(bufferSize: Int = 16 * 1024): Pair<TestDuplex, TestDuplex> {
        val clientInput = PipedInputStream(bufferSize)
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream(bufferSize)
        val clientOutput = PipedOutputStream(serverInput)
        return TestDuplex(clientInput, clientOutput) to TestDuplex(serverInput, serverOutput)
    }

    private class TestDuplex(
        override val input: InputStream,
        override val output: OutputStream,
    ) : ByteDuplex {
        override fun close() {
            input.close()
            output.close()
        }
    }
}
