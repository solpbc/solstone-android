// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalBrowserStreamingHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")
    private val diagEvents = CopyOnWriteArrayList<DiagEvent>()

    @Test
    fun streamsNineMegabyteRequestThroughSessionDefaultRoute() {
        var receivedRequestBodyBytes = 0L
        val upstream = object : JournalBrowserUpstream {
            override val isPoisoned: Boolean get() = false
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
            }

            override fun requestStreaming(
                method: String,
                path: String,
                headers: List<Pair<String, String>>,
                bodySource: BrowserRequestBodySource?,
                responseSink: BrowserResponseSink,
            ) {
                if (bodySource != null) {
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = bodySource.readChunk(buf, 0, buf.size)
                        if (read < 0) break
                        receivedRequestBodyBytes += read
                    }
                }
                responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Type" to "text/plain"))
                responseSink.onBodyChunk("UPLOADED".encodeToByteArray(), 0, 8)
                responseSink.onComplete()
            }

            override fun close() {}
        }

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { upstream },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val port = uri.port
        val host = uri.host

        val targetNineMiB = 9L * 1024 * 1024
        val socket = Socket("127.0.0.1", port)
        socket.use { sock ->
            val out = sock.getOutputStream()
            val input = sock.getInputStream()

            val header = "POST /large-upload HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "Content-Length: $targetNineMiB\r\n" +
                "\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()

            val chunk = ByteArray(64 * 1024) { 0x41.toByte() }
            var written = 0L
            while (written < targetNineMiB) {
                val size = minOf(chunk.size.toLong(), targetNineMiB - written).toInt()
                out.write(chunk, 0, size)
                out.flush()
                written += size
            }

            val response = readFullResponse(input)
            assertTrue(response.startsWith("HTTP/1.1 200 OK"), "Expected 200 OK, got: $response")
            assertTrue(response.contains("UPLOADED"))
            assertEquals(targetNineMiB, receivedRequestBodyBytes)
        }

        session.stop()
    }

    @Test
    fun pausedNineMiBRequestAndSeventeenMiBResponseEarlySinkByte() {
        val earlyByteObserved = AtomicBoolean(false)
        val requestBodyPausedLatch = CountDownLatch(1)
        val responseStartedLatch = CountDownLatch(1)

        val upstream = object : JournalBrowserUpstream {
            override val isPoisoned: Boolean get() = false
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                error("Not used")
            }

            override fun requestStreaming(
                method: String,
                path: String,
                headers: List<Pair<String, String>>,
                bodySource: BrowserRequestBodySource?,
                responseSink: BrowserResponseSink,
            ) {
                // Read first 1 MiB of request body, then send response headers and first chunk before finishing request
                val buf = ByteArray(64 * 1024)
                bodySource?.readChunk(buf, 0, buf.size)

                responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Length" to "${17 * 1024 * 1024}"))
                responseSink.onBodyChunk("EARLY_BYTE".toByteArray(Charsets.US_ASCII), 0, 10)
                responseStartedLatch.countDown()

                // Continue reading request body
                while (true) {
                    val read = bodySource?.readChunk(buf, 0, buf.size) ?: -1
                    if (read < 0) break
                }
                requestBodyPausedLatch.countDown()

                // Send remaining 17 MiB response
                val remainingBytes = (17 * 1024 * 1024) - 10
                val respChunk = ByteArray(64 * 1024)
                var sent = 0
                while (sent < remainingBytes) {
                    val count = minOf(respChunk.size, remainingBytes - sent)
                    responseSink.onBodyChunk(respChunk, 0, count)
                    sent += count
                }
                responseSink.onComplete()
            }

            override fun close() {}
        }

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { upstream },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val socket = Socket("127.0.0.1", uri.port)
        socket.use { sock ->
            val out = sock.getOutputStream()
            val input = sock.getInputStream()

            val header = "POST /early-byte HTTP/1.1\r\n" +
                "Host: ${uri.host}:${uri.port}\r\n" +
                "Content-Length: ${9 * 1024 * 1024}\r\n" +
                "\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()

            // Write first 1 MiB
            out.write(ByteArray(1024 * 1024))
            out.flush()

            // Await early byte response from downstream socket
            responseStartedLatch.await(5, TimeUnit.SECONDS)
            val line = readLine(BufferedInputStream(input))
            if (line.startsWith("HTTP/1.1 200 OK")) {
                earlyByteObserved.set(true)
            }

            // Write rest of 9 MiB request
            out.write(ByteArray(8 * 1024 * 1024))
            out.flush()
        }

        assertTrue(earlyByteObserved.get(), "Early sink byte must be observed before request source completion")
        session.stop()
    }

    @Test
    fun handlesExpect100ContinueHandshakeAndStripsExpectUpstream() {
        var upstreamSawExpect = false
        val upstream = object : JournalBrowserUpstream {
            override val isPoisoned: Boolean get() = false
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                return BrowserHttpResponse(200, emptyList(), ByteArray(0))
            }

            override fun requestStreaming(
                method: String,
                path: String,
                headers: List<Pair<String, String>>,
                bodySource: BrowserRequestBodySource?,
                responseSink: BrowserResponseSink,
            ) {
                if (headers.any { it.first.equals("expect", ignoreCase = true) }) {
                    upstreamSawExpect = true
                }
                responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Type" to "text/plain"))
                responseSink.onBodyChunk("OK".encodeToByteArray(), 0, 2)
                responseSink.onComplete()
            }

            override fun close() {}
        }

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { upstream },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val socket = Socket("127.0.0.1", uri.port)
        socket.use { sock ->
            val out = sock.getOutputStream()
            val input = BufferedInputStream(sock.getInputStream())

            val header = "POST /expect-test HTTP/1.1\r\n" +
                "Host: ${uri.host}:${uri.port}\r\n" +
                "Expect: 100-continue\r\n" +
                "Content-Length: 5\r\n" +
                "\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()

            val line1 = readLine(input)
            assertEquals("HTTP/1.1 100 Continue", line1)
            val emptyLine = readLine(input)
            assertEquals("", emptyLine)

            out.write("hello".toByteArray(Charsets.US_ASCII))
            out.flush()

            val finalStatus = readLine(input)
            assertEquals("HTTP/1.1 200 OK", finalStatus)
        }

        assertTrue(!upstreamSawExpect, "Expect header should have been stripped upstream")
        session.stop()
    }

    @Test
    fun idempotentMethodRetriesOnceBeforeSinkByte() {
        val attempts = AtomicInteger(0)
        val upstream = object : JournalBrowserUpstream {
            override val isPoisoned: Boolean get() = false
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse = error("Not used")

            override fun requestStreaming(
                method: String,
                path: String,
                headers: List<Pair<String, String>>,
                bodySource: BrowserRequestBodySource?,
                responseSink: BrowserResponseSink,
            ) {
                val attempt = attempts.incrementAndGet()
                if (attempt == 1) {
                    throw IOException("Initial transient connection drop")
                }
                responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Length" to "4"))
                responseSink.onBodyChunk("pass".toByteArray(Charsets.US_ASCII), 0, 4)
                responseSink.onComplete()
            }

            override fun close() {}
        }

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { upstream },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val socket = Socket("127.0.0.1", uri.port)
        socket.use { sock ->
            val out = sock.getOutputStream()
            val input = sock.getInputStream()

            val header = "GET /retry-get HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()

            val response = readFullResponse(input)
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("pass"))
        }

        assertEquals(2, attempts.get(), "GET should retry once on failure before sink byte")
        session.stop()
    }

    @Test
    fun nonIdempotentMethodFailureDoesNotReplay() {
        val openCount = AtomicInteger(0)
        val upstream = object : JournalBrowserUpstream {
            override val isPoisoned: Boolean get() = false
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse = error("Not used")

            override fun requestStreaming(
                method: String,
                path: String,
                headers: List<Pair<String, String>>,
                bodySource: BrowserRequestBodySource?,
                responseSink: BrowserResponseSink,
            ) {
                openCount.incrementAndGet()
                throw IOException("Simulated network blip during POST")
            }

            override fun close() {}
        }

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { upstream },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val socket = Socket("127.0.0.1", uri.port)
        socket.use { sock ->
            val out = sock.getOutputStream()
            val input = sock.getInputStream()

            val header = "POST /submit HTTP/1.1\r\n" +
                "Host: ${uri.host}:${uri.port}\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()

            val response = readFullResponse(input)
            assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
            assertTrue(response.contains("AMBIGUOUS_DELIVERY"))
        }

        assertEquals(1, openCount.get(), "POST must not be retried")
        session.stop()
    }

    @Test
    fun orderedRepeatedHeadersPreserved() {
        val observedHeaders = mutableListOf<Pair<String, String>>()
        val upstream = object : JournalBrowserUpstream {
            override val isPoisoned: Boolean get() = false
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse = error("Not used")

            override fun requestStreaming(
                method: String,
                path: String,
                headers: List<Pair<String, String>>,
                bodySource: BrowserRequestBodySource?,
                responseSink: BrowserResponseSink,
            ) {
                observedHeaders.addAll(headers)
                responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Length" to "0"))
                responseSink.onComplete()
            }

            override fun close() {}
        }

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { upstream },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val socket = Socket("127.0.0.1", uri.port)
        socket.use { sock ->
            val out = sock.getOutputStream()
            val input = sock.getInputStream()

            val req = "GET /headers HTTP/1.1\r\n" +
                "Host: ${uri.host}:${uri.port}\r\n" +
                "Accept: text/html\r\n" +
                "Accept: application/json\r\n" +
                "Accept-Language: en-US\r\n" +
                "Accept-Language: fr-FR\r\n" +
                "\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val response = readFullResponse(input)
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        }

        val accepts = observedHeaders.filter { it.first.equals("accept", ignoreCase = true) }.map { it.second }
        assertEquals(listOf("text/html", "application/json"), accepts)

        val languages = observedHeaders.filter { it.first.equals("accept-language", ignoreCase = true) }.map { it.second }
        assertEquals(listOf("en-US", "fr-FR"), languages)

        session.stop()
    }

    @Test
    fun privacyCanariesAbsentFromDiagAndSyntheticErrorBodies() {
        val canary = "CANARY_SECRET_TOKEN_9999"
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse = error("Not used")
                    override fun requestStreaming(method: String, path: String, headers: List<Pair<String, String>>, bodySource: BrowserRequestBodySource?, responseSink: BrowserResponseSink) {
                        throw IOException("Failure with canary: $canary")
                    }
                    override fun close() {}
                }
            },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val socket = Socket("127.0.0.1", uri.port)
        socket.use { sock ->
            val out = sock.getOutputStream()
            val input = sock.getInputStream()

            val req = "GET /canary-test?secret=$canary HTTP/1.1\r\n" +
                "Host: ${uri.host}:${uri.port}\r\n" +
                "Authorization: Bearer $canary\r\n" +
                "\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val resp = readFullResponse(input)
            assertFalse(resp.contains(canary), "Synthetic error body must not leak canary secret")
        }

        // Check diag events
        for (event in diagEvents) {
            val formatted = app.solstone.core.diagnostics.formatDiagEvent(event)
            assertFalse(formatted.contains(canary), "Diag format must not contain canary secret")
        }

        session.stop()
    }

    private fun readFullResponse(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            out.write(buf, 0, read)
        }
        return out.toString(Charsets.US_ASCII)
    }

    private fun readLine(input: BufferedInputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) {
                out.write(b)
            }
        }
        return out.toString(Charsets.US_ASCII)
    }
}
