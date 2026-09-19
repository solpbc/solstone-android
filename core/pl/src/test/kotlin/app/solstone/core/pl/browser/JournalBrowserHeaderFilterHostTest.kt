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
        assertTrue(response.contains("connect-src 'self'"))
        assertTrue(response.contains("child-src 'none'"))
        assertTrue(response.contains("worker-src 'none'"))
        assertTrue(response.contains("Set-Cookie: id=1; Path=/; Secure; HttpOnly"))
        assertTrue(response.contains("Set-Cookie: __Host-auth=xyz; Path=/; Secure"))
        assertFalse(response.contains("Domain=localhost"))
        assertFalse(response.contains("Domain=spl.local"))
        assertFalse(response.contains("X-Extra-Secret"))

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

    private fun assertNotNull(value: Any?) {
        assertTrue(value != null)
    }
}
