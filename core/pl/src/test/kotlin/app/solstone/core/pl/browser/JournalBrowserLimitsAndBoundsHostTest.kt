// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalBrowserLimitsAndBoundsHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")
    private val diagEvents = mutableListOf<DiagEvent>()

    @Test
    fun enforcesFifoQueueAndRejectsAboveCapacity() {
        val blockUpstreamLatch = CountDownLatch(1)
        val activeThreadsLatch = CountDownLatch(2)

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        activeThreadsLatch.countDown()
                        blockUpstreamLatch.await(5, TimeUnit.SECONDS)
                        return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
                    }
                    override fun close() {}
                }
            },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val port = uri.port
        val host = uri.host

        // 1. Launch 4 active requests
        val activeSockets = mutableListOf<Socket>()
        repeat(4) {
            val s = Socket("127.0.0.1", port)
            activeSockets.add(s)
            val out = BufferedOutputStream(s.getOutputStream())
            out.write("GET /block HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        assertTrue(activeThreadsLatch.await(3, TimeUnit.SECONDS))

        // 2. Launch 8 waiters
        val waiterSockets = mutableListOf<Socket>()
        repeat(8) {
            val s = Socket("127.0.0.1", port)
            waiterSockets.add(s)
            val out = BufferedOutputStream(s.getOutputStream())
            out.write("GET /waiter HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        // 3. 13th connection exceeds 4 active + 8 waiters -> 503 Service Unavailable
        val s13 = Socket("127.0.0.1", port)
        val out13 = BufferedOutputStream(s13.getOutputStream())
        val in13 = BufferedInputStream(s13.getInputStream())
        out13.write("GET /excess HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out13.flush()

        val buf = ByteArray(1024)
        val read13 = in13.read(buf)
        val res13 = if (read13 > 0) String(buf, 0, read13, Charsets.UTF_8) else ""
        assertTrue(res13.startsWith("HTTP/1.1 503 Service Unavailable"))
        s13.close()

        // Unblock active requests
        blockUpstreamLatch.countDown()

        activeSockets.forEach { try { it.close() } catch (_: Exception) {} }
        waiterSockets.forEach { try { it.close() } catch (_: Exception) {} }
        session.stop()
    }

    @Test
    fun rejectsOversizedRequestBody() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
                    }
                    override fun close() {}
                }
            },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val port = uri.port
        val host = uri.host

        // 9 MiB declared body exceeds 8 MiB limit
        val oversizeLength = 9 * 1024 * 1024
        val request = "POST /upload HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Content-Length: $oversizeLength\r\n" +
            "\r\n"

        val response = sendRawHttpRequest(port, request)
        assertTrue(response.startsWith("HTTP/1.1 413 Payload Too Large"))

        session.stop()
    }

    @Test
    fun enforcesLineAndHeaderBoundsAndMethodRestrictions() {
        var upstreamCalled = false
        var upstreamStatus = 200

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        upstreamCalled = true
                        return BrowserHttpResponse(upstreamStatus, listOf("Content-Type" to "text/plain"), "STATUS_$upstreamStatus".encodeToByteArray())
                    }
                    override fun close() {}
                }
            },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val port = uri.port
        val host = uri.host

        // 1. Request line > 8KiB -> 414 URI Too Long
        val longPath = "/".repeat(9 * 1024)
        val res414 = sendRawHttpRequest(port, "GET $longPath HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res414.startsWith("HTTP/1.1 414 URI Too Long"))

        // 2. More than 100 headers -> 431 Request Header Fields Too Large
        val manyHeaders = (1..105).joinToString("") { "X-Header-$it: value\r\n" }
        val res431Count = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $host:$port\r\n$manyHeaders\r\n")
        assertTrue(res431Count.startsWith("HTTP/1.1 431 Request Header Fields Too Large"))

        // 3. Header block > 64KiB -> 431 Request Header Fields Too Large
        val largeHeaderVal = "a".repeat(70 * 1024)
        val res431Size = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $host:$port\r\nX-Large: $largeHeaderVal\r\n\r\n")
        assertTrue(res431Size.startsWith("HTTP/1.1 431 Request Header Fields Too Large"))

        // 4. TRACE and CONNECT -> 405 Method Not Allowed + Allow header, no upstream call
        upstreamCalled = false
        val resTrace = sendRawHttpRequest(port, "TRACE / HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(resTrace.startsWith("HTTP/1.1 405 Method Not Allowed"))
        assertTrue(resTrace.contains("Allow: GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS"))
        assertEquals(false, upstreamCalled)

        val resConnect = sendRawHttpRequest(port, "CONNECT example.com:443 HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(resConnect.startsWith("HTTP/1.1 405 Method Not Allowed"))
        assertEquals(false, upstreamCalled)

        // 5. Upstream 404 and 500 pass through unchanged
        upstreamStatus = 404
        val res404 = sendRawHttpRequest(port, "GET /not-found HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res404.startsWith("HTTP/1.1 404 Not Found"))
        assertTrue(res404.contains("STATUS_404"))

        upstreamStatus = 500
        val res500 = sendRawHttpRequest(port, "GET /error HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res500.startsWith("HTTP/1.1 500 Internal Server Error"))
        assertTrue(res500.contains("STATUS_500"))

        session.stop()
    }

    @Test
    fun parallelRequestsMaintainExactAssociation() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        Thread.sleep(50) // simulate work
                        return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "BODY_FOR_$path".encodeToByteArray())
                    }
                    override fun close() {}
                }
            },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val port = uri.port
        val host = uri.host

        val latch = CountDownLatch(2)
        var resA = ""
        var resB = ""

        val tA = Thread {
            resA = sendRawHttpRequest(port, "GET /endpointA HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
            latch.countDown()
        }
        val tB = Thread {
            resB = sendRawHttpRequest(port, "GET /endpointB HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
            latch.countDown()
        }

        tA.start()
        tB.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertTrue(resA.contains("BODY_FOR_/endpointA"), "resA was: $resA")
        assertTrue(resB.contains("BODY_FOR_/endpointB"), "resB was: $resB")

        session.stop()
    }

    private fun sendRawHttpRequest(port: Int, rawHttp: String): String {
        Socket("127.0.0.1", port).use { socket ->
            val out = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            out.write(rawHttp.toByteArray(Charsets.US_ASCII))
            out.flush()
            val buf = ByteArray(4096)
            val read = input.read(buf)
            return if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
        }
    }
}
