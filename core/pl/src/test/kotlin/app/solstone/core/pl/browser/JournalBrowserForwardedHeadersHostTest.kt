// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalBrowserForwardedHeadersHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")

    @Test
    fun forwardsAllowedRequestHeadersIncludingLastEventIdAndDropsXForwardedFor() {
        val capturedHeaders = AtomicReference<List<Pair<String, String>>>()
        val capturedLatch = CountDownLatch(1)

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse =
                        error("Not used")

                    override fun requestStreaming(
                        method: String,
                        path: String,
                        headers: List<Pair<String, String>>,
                        bodySource: BrowserRequestBodySource?,
                        responseSink: BrowserResponseSink,
                    ) {
                        capturedHeaders.set(headers)
                        capturedLatch.countDown()
                        val body = "OK".toByteArray(Charsets.US_ASCII)
                        responseSink.onStatusAndHeaders(
                            200,
                            "OK",
                            listOf("Content-Type" to "text/plain", "Content-Length" to body.size.toString()),
                        )
                        responseSink.onBodyChunk(body, 0, body.size)
                        responseSink.onComplete()
                    }

                    override fun close() {}
                }
            },
            diag = {},
        )

        var clientSocket: Socket? = null
        try {
            val origin = session.start()
            val uri = URI(origin.url)
            val port = uri.port
            val host = uri.host

            clientSocket = Socket("127.0.0.1", port).apply {
                soTimeout = 5000
                val out = getOutputStream()
                val req = "GET /test HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Accept-Encoding: gzip\r\n" +
                    "If-None-Match: \"etag-1\"\r\n" +
                    "If-Modified-Since: Wed, 21 Oct 2015 07:28:00 GMT\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Last-Event-ID: 42\r\n" +
                    "X-Forwarded-For: 203.0.113.5\r\n" +
                    "\r\n"
                out.write(req.toByteArray(Charsets.US_ASCII))
                out.flush()
            }

            assertTrue(capturedLatch.await(5, TimeUnit.SECONDS), "upstream was not called")
            val captured = capturedHeaders.get()
            assertTrue(captured != null, "captured headers were null")

            assertEquals("gzip", captured.firstOrNull { it.first == "accept-encoding" }?.second, "accept-encoding")
            assertEquals("\"etag-1\"", captured.firstOrNull { it.first == "if-none-match" }?.second, "if-none-match")
            assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", captured.firstOrNull { it.first == "if-modified-since" }?.second, "if-modified-since")
            assertEquals("no-cache", captured.firstOrNull { it.first == "cache-control" }?.second, "cache-control")
            assertEquals("42", captured.firstOrNull { it.first == "last-event-id" }?.second, "last-event-id")
            assertFalse(captured.any { it.first == "x-forwarded-for" }, "x-forwarded-for must not be present")
        } finally {
            runCatching { clientSocket?.close() }
            session.stop()
        }
    }

    @Test
    fun evaluatesContentSecurityPolicyConfiningDirectivesAndAppendsFloorWhenMissing() {
        val floorLiteral =
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; media-src 'self' blob:; form-action 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"
        assertFalse(floorLiteral.contains("'unsafe-eval'"), "floorLiteral must not contain 'unsafe-eval'")

        val nextUpstreamHeaders = AtomicReference<List<Pair<String, String>>>(emptyList())
        val diagEvents = CopyOnWriteArrayList<DiagEvent>()

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse =
                        error("Not used")

                    override fun requestStreaming(
                        method: String,
                        path: String,
                        headers: List<Pair<String, String>>,
                        bodySource: BrowserRequestBodySource?,
                        responseSink: BrowserResponseSink,
                    ) {
                        val body = "OK".toByteArray(Charsets.US_ASCII)
                        val headersToSend = nextUpstreamHeaders.get().toMutableList().apply {
                            if (none { it.first.equals("content-type", ignoreCase = true) }) {
                                add("Content-Type" to "text/plain")
                            }
                            if (none { it.first.equals("content-length", ignoreCase = true) }) {
                                add("Content-Length" to body.size.toString())
                            }
                        }
                        responseSink.onStatusAndHeaders(200, "OK", headersToSend)
                        responseSink.onBodyChunk(body, 0, body.size)
                        responseSink.onComplete()
                    }

                    override fun close() {}
                }
            },
            diag = { diagEvents.add(it) },
        )

        fun fallbackCount(): Int =
            diagEvents.count { it is DiagEvent.JournalBrowser && it.eventClass == "csp" && it.outcome == "fallback" }

        fun sendAndRead(port: Int, host: String): Pair<String, List<Pair<String, String>>> {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val out = socket.getOutputStream()
                out.write("GET /test HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()

                val baos = ByteArrayOutputStream()
                val buf = ByteArray(1024)
                val inStream = socket.getInputStream()
                while (true) {
                    val read = inStream.read(buf)
                    if (read < 0) break
                    baos.write(buf, 0, read)
                    if (baos.toString(Charsets.US_ASCII.name()).contains("\r\n\r\n")) {
                        // For our small body "OK", once header end is reached, read the rest
                        val text = baos.toString(Charsets.US_ASCII.name())
                        if (text.substringAfter("\r\n\r\n").length >= 2) break
                    }
                }
                val raw = baos.toString(Charsets.US_ASCII.name())
                val headerLines = raw.substringBefore("\r\n\r\n").split("\r\n")
                val headers = mutableListOf<Pair<String, String>>()
                for (i in 1 until headerLines.size) {
                    val line = headerLines[i]
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers.add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
                    }
                }
                return raw to headers
            }
        }

        try {
            val origin = session.start()
            val uri = URI(origin.url)
            val port = uri.port
            val host = uri.host

            // Case A: confining policy A, multiple headers, zero fallback events
            nextUpstreamHeaders.set(
                listOf(
                    "Cache-Control" to "max-age=7, private",
                    "ETag" to "\"abc123\"",
                    "Last-Modified" to "Tue, 15 Nov 1994 12:45:26 GMT",
                    "Content-Encoding" to "gzip",
                    "Vary" to "Accept-Encoding",
                    "Retry-After" to "120",
                    "Server-Timing" to "app;dur=12.5",
                    "Content-Security-Policy" to "default-src 'none'; script-src 'self'",
                    "X-SPL-Loopback-Refused" to "mismatch",
                ),
            )
            val (rawA, headersA) = sendAndRead(port, host)
            val cspA = headersA.filter { it.first.equals("Content-Security-Policy", ignoreCase = true) }.map { it.second }
            assertEquals(listOf("default-src 'none'; script-src 'self'"), cspA)
            assertTrue(rawA.contains("Cache-Control: max-age=7, private"))
            assertTrue(rawA.contains("ETag: \"abc123\""))
            assertTrue(rawA.contains("Last-Modified: Tue, 15 Nov 1994 12:45:26 GMT"))
            assertTrue(rawA.contains("Content-Encoding: gzip"))
            assertTrue(rawA.contains("Vary: Accept-Encoding"))
            assertTrue(rawA.contains("Retry-After: 120"))
            assertTrue(rawA.contains("Server-Timing: app;dur=12.5"))
            assertTrue(rawA.contains("Referrer-Policy: no-referrer"))
            assertFalse(rawA.contains("X-SPL-Loopback-Refused"))
            assertEquals(0, fallbackCount(), "Case A must emit zero fallback events")

            // Case B: different confining value B, zero fallback events
            nextUpstreamHeaders.set(
                listOf(
                    "Content-Security-Policy" to "script-src 'self'; default-src 'self' data:",
                ),
            )
            val (_, headersB) = sendAndRead(port, host)
            val cspB = headersB.filter { it.first.equals("Content-Security-Policy", ignoreCase = true) }.map { it.second }
            assertEquals(listOf("script-src 'self'; default-src 'self' data:"), cspB)
            assertEquals(0, fallbackCount(), "Case B must emit zero fallback events")

            // Case C: connect-src 'self' only, zero fallback events
            nextUpstreamHeaders.set(
                listOf(
                    "Content-Security-Policy" to "connect-src 'self'",
                ),
            )
            val (_, headersC) = sendAndRead(port, host)
            val cspC = headersC.filter { it.first.equals("Content-Security-Policy", ignoreCase = true) }.map { it.second }
            assertEquals(listOf("connect-src 'self'"), cspC)
            assertEquals(0, fallbackCount(), "Case C must emit zero fallback events")

            // Case D: default-src as non-directive token -> non-confining, appends floor, emits 1 fallback
            nextUpstreamHeaders.set(
                listOf(
                    "Content-Security-Policy" to "script-src 'self'; img-src https://default-src.example",
                ),
            )
            val (_, headersD) = sendAndRead(port, host)
            val cspD = headersD.filter { it.first.equals("Content-Security-Policy", ignoreCase = true) }.map { it.second }
            assertEquals(listOf("script-src 'self'; img-src https://default-src.example", floorLiteral), cspD)
            assertEquals(1, fallbackCount(), "Case D must emit one fallback event")

            // Case E: no upstream CSP -> exactly floorLiteral, emits 1 fallback
            nextUpstreamHeaders.set(emptyList())
            val (_, headersE) = sendAndRead(port, host)
            val cspE = headersE.filter { it.first.equals("Content-Security-Policy", ignoreCase = true) }.map { it.second }
            assertEquals(listOf(floorLiteral), cspE)
            assertEquals(2, fallbackCount(), "Case E must emit one additional fallback event")

            // Case F: non-confining upstream policy -> upstream then floor, emits 1 fallback
            nextUpstreamHeaders.set(
                listOf(
                    "Content-Security-Policy" to "frame-ancestors 'none'; object-src 'none'; base-uri 'self'",
                ),
            )
            val (_, headersF) = sendAndRead(port, host)
            val cspF = headersF.filter { it.first.equals("Content-Security-Policy", ignoreCase = true) }.map { it.second }
            assertEquals(
                listOf("frame-ancestors 'none'; object-src 'none'; base-uri 'self'", floorLiteral),
                cspF,
            )
            assertEquals(3, fallbackCount(), "Case F must emit one additional fallback event")
        } finally {
            session.stop()
        }
    }
}
