// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.httpRequestBytes
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalBrowserHeaderFilterHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")
    private val diagEvents = mutableListOf<DiagEvent>()

    @Test
    fun filtersRequestHeadersAndRegeneratesHost() {
        var capturedHeaders: Map<String, String>? = null

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        capturedHeaders = headers
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

        val request = "GET /test HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "User-Agent: TestAgent\r\n" +
            "Accept: text/html\r\n" +
            "Cookie: session=abc\r\n" +
            "Cookie: theme=dark\r\n" +
            "Authorization: Bearer secret\r\n" +
            "X-Custom-Secret: canary\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n"

        sendRawHttpRequest(port, request)

        assertNotNull(capturedHeaders)
        assertEquals("text/html", capturedHeaders!!["accept"])
        assertEquals("session=abc; theme=dark", capturedHeaders!!["cookie"])
        assertEquals("TestAgent", capturedHeaders!!["user-agent"])
        assertFalse(capturedHeaders!!.containsKey("authorization"))
        assertFalse(capturedHeaders!!.containsKey("x-custom-secret"))
        assertFalse(capturedHeaders!!.containsKey("connection"))

        session.stop()
    }

    @Test
    fun formatsHttpRequestBytesWithSplLocalHostHeader() {
        val bytes = httpRequestBytes(
            method = "GET",
            path = "/api/resource",
            headers = mapOf("accept" to "application/json", "cookie" to "session=1"),
            body = null,
        )
        val text = String(bytes, Charsets.US_ASCII)
        assertTrue(text.contains("host: spl.local\r\n", ignoreCase = true), "Expected Host: spl.local in request framing: $text")
        assertTrue(text.startsWith("GET /api/resource HTTP/1.1\r\n"))
    }

    @Test
    fun stripsCookieDomainAndAppendsNoReferrerAndPassesPartialContent() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        return BrowserHttpResponse(
                            status = 206,
                            headers = listOf(
                                "Content-Type" to "video/mp4",
                                "Content-Range" to "bytes 0-100/1000",
                                "Accept-Ranges" to "bytes",
                                "Set-Cookie" to "id=1; Domain=localhost; Path=/; Secure; HttpOnly",
                                "Set-Cookie" to "__Host-auth=xyz; Domain=spl.local; Path=/; Secure",
                                "X-Extra-Secret" to "canary",
                            ),
                            body = ByteArray(101) { 0x01 },
                        )
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

        val response = sendRawHttpRequest(port, "GET /video HTTP/1.1\r\nHost: $host:$port\r\nRange: bytes=0-100\r\n\r\n")

        assertTrue(response.startsWith("HTTP/1.1 206 Partial Content"))
        assertTrue(response.contains("Content-Range: bytes 0-100/1000"))
        assertTrue(response.contains("Accept-Ranges: bytes"))
        assertTrue(response.contains("Referrer-Policy: no-referrer"))
        assertTrue(response.contains("Content-Security-Policy: default-src 'self'"))
        assertTrue(response.contains("Set-Cookie: id=1; Path=/; Secure; HttpOnly"))
        assertTrue(response.contains("Set-Cookie: __Host-auth=xyz; Path=/; Secure"))
        assertFalse(response.contains("Domain=localhost"))
        assertFalse(response.contains("Domain=spl.local"))
        assertFalse(response.contains("X-Extra-Secret"))

        session.stop()
    }

    @Test
    fun forwardsSingleLocaleHeaderAndSuppliesFallbackOnlyOutsideEnglish() {
        val observed = mutableListOf<List<Pair<String, String>>>()

        val xaSession = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse =
                        BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
                    override fun requestStreaming(
                        method: String,
                        path: String,
                        headers: List<Pair<String, String>>,
                        bodySource: BrowserRequestBodySource?,
                        responseSink: BrowserResponseSink,
                    ) {
                        observed.add(headers)
                        responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Type" to "text/plain", "Content-Length" to "2"))
                        responseSink.onBodyChunk("OK".encodeToByteArray(), 0, 2)
                        responseSink.onComplete()
                    }
                    override fun close() {}
                }
            },
            diag = { diagEvents.add(it) },
            requestLocale = { "en-XA" },
        )

        val xaOrigin = xaSession.start()
        val xaUri = URI(xaOrigin.url)
        // Explicit header from WebView or page fetch passes through once.
        sendRawHttpRequest(
            xaUri.port,
            "GET /doc HTTP/1.1\r\nHost: ${xaUri.host}:${xaUri.port}\r\nX-Solstone-Locale: en-XA\r\nX-Solstone-Locale: en-XA\r\n\r\n",
        )
        // Request where WebView couldn't add header (e.g. redirect, fetch) still receives fallback tag.
        sendRawHttpRequest(
            xaUri.port,
            "GET /api/localized HTTP/1.1\r\nHost: ${xaUri.host}:${xaUri.port}\r\n\r\n",
        )
        xaSession.stop()

        assertEquals(2, observed.size)
        assertEquals(
            listOf("en-XA"),
            observed[0].filter { it.first.equals("x-solstone-locale", ignoreCase = true) }.map { it.second },
        )
        assertEquals(
            listOf("en-XA"),
            observed[1].filter { it.first.equals("x-solstone-locale", ignoreCase = true) }.map { it.second },
        )

        // English session sends nothing new.
        observed.clear()
        val enSession = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse =
                        BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
                    override fun requestStreaming(
                        method: String,
                        path: String,
                        headers: List<Pair<String, String>>,
                        bodySource: BrowserRequestBodySource?,
                        responseSink: BrowserResponseSink,
                    ) {
                        observed.add(headers)
                        responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Type" to "text/plain", "Content-Length" to "2"))
                        responseSink.onBodyChunk("OK".encodeToByteArray(), 0, 2)
                        responseSink.onComplete()
                    }
                    override fun close() {}
                }
            },
            diag = { diagEvents.add(it) },
            requestLocale = { null },
        )
        val enOrigin = enSession.start()
        val enUri = URI(enOrigin.url)
        sendRawHttpRequest(enUri.port, "GET /doc HTTP/1.1\r\nHost: ${enUri.host}:${enUri.port}\r\n\r\n")
        enSession.stop()

        assertEquals(1, observed.size)
        assertTrue(observed[0].none { it.first.equals("x-solstone-locale", ignoreCase = true) })
    }

    @Test
    fun forwardsContentDispositionForExportsExistingMediaAndAttachmentsAndHandlesLargeHead410And416() {
        val observedRequests = mutableListOf<Triple<String, String, List<Pair<String, String>>>>()
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse =
                        error("streaming path expected")

                    override fun requestStreaming(
                        method: String,
                        path: String,
                        headers: List<Pair<String, String>>,
                        bodySource: BrowserRequestBodySource?,
                        responseSink: BrowserResponseSink,
                    ) {
                        observedRequests.add(Triple(method, path, headers))
                        when {
                            method == "HEAD" && path.startsWith("/app/export/api/ready/") -> {
                                responseSink.onStatusAndHeaders(
                                    200,
                                    "OK",
                                    listOf(
                                        "Content-Type" to "application/zip",
                                        "Content-Disposition" to "attachment; filename=\"solstone-journal-2026-11-26.zip\"",
                                        "Accept-Ranges" to "bytes",
                                        "ETag" to "\"sha256-deadbeef\"",
                                        "Content-Length" to "300000000000",
                                    ),
                                )
                                responseSink.onComplete()
                            }
                            path == "/app/export/api/ready/exp1/download" &&
                                headers.any { it.first == "range" && it.second == "bytes=999999999999-" } -> {
                                responseSink.onStatusAndHeaders(
                                    416,
                                    "",
                                    listOf(
                                        "Content-Range" to "bytes */300000000000",
                                        "Content-Length" to "0",
                                    ),
                                )
                                responseSink.onComplete()
                            }
                            path == "/app/export/api/ready/erased/download" -> {
                                val body = "{\"code\":\"export_invalidated_by_erasure\"}".encodeToByteArray()
                                responseSink.onStatusAndHeaders(
                                    410,
                                    "",
                                    listOf(
                                        "Content-Type" to "application/json",
                                        "Content-Length" to body.size.toString(),
                                    ),
                                )
                                responseSink.onBodyChunk(body, 0, body.size)
                                responseSink.onComplete()
                            }
                            path == "/media/20261126/photo.jpg" -> {
                                val body = byteArrayOf(1, 2, 3, 4)
                                responseSink.onStatusAndHeaders(
                                    200,
                                    "OK",
                                    listOf(
                                        "Content-Type" to "image/jpeg",
                                        "Content-Disposition" to "inline; filename=\"photo.jpg\"",
                                        "Accept-Ranges" to "bytes",
                                        "Content-Length" to body.size.toString(),
                                    ),
                                )
                                responseSink.onBodyChunk(body, 0, body.size)
                                responseSink.onComplete()
                            }
                            path == "/attachments/recording.flac" -> {
                                val body = byteArrayOf(5, 6, 7, 8)
                                responseSink.onStatusAndHeaders(
                                    200,
                                    "OK",
                                    listOf(
                                        "Content-Type" to "audio/flac",
                                        "Content-Disposition" to "attachment; filename=\"recording.flac\"",
                                        "Content-Length" to body.size.toString(),
                                    ),
                                )
                                responseSink.onBodyChunk(body, 0, body.size)
                                responseSink.onComplete()
                            }
                            else -> error("unexpected $method $path")
                        }
                    }

                    override fun close() {}
                }
            },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        try {
            val exportHead = sendRawHttpRequest(
                uri.port,
                "HEAD /app/export/api/ready/exp1/download HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n",
            )
            assertTrue(exportHead.startsWith("HTTP/1.1 200 OK"), exportHead)
            assertTrue(exportHead.contains("Content-Disposition: attachment; filename=\"solstone-journal-2026-11-26.zip\""), exportHead)
            assertTrue(exportHead.contains("Content-Length: 300000000000"), exportHead)
            assertTrue(exportHead.contains("Accept-Ranges: bytes"), exportHead)

            val outOfBounds = sendRawHttpRequest(
                uri.port,
                "GET /app/export/api/ready/exp1/download HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nRange: bytes=999999999999-\r\nIf-Range: \"sha256-deadbeef\"\r\n\r\n",
            )
            assertTrue(outOfBounds.startsWith("HTTP/1.1 416 Range Not Satisfiable"), outOfBounds)
            assertTrue(outOfBounds.contains("Content-Range: bytes */300000000000"), outOfBounds)

            val erased = sendRawHttpRequest(
                uri.port,
                "GET /app/export/api/ready/erased/download HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n",
            )
            assertTrue(erased.startsWith("HTTP/1.1 410 Gone"), erased)

            val inlineMedia = sendRawHttpRequest(
                uri.port,
                "GET /media/20261126/photo.jpg HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n",
            )
            assertTrue(inlineMedia.startsWith("HTTP/1.1 200 OK"), inlineMedia)
            assertTrue(inlineMedia.contains("Content-Disposition: inline; filename=\"photo.jpg\""), inlineMedia)

            val attachment = sendRawHttpRequest(
                uri.port,
                "GET /attachments/recording.flac HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n",
            )
            assertTrue(attachment.startsWith("HTTP/1.1 200 OK"), attachment)
            assertTrue(attachment.contains("Content-Disposition: attachment; filename=\"recording.flac\""), attachment)
        } finally {
            session.stop()
        }
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

    private fun assertNotNull(value: Any?) {
        assertTrue(value != null)
    }
}
