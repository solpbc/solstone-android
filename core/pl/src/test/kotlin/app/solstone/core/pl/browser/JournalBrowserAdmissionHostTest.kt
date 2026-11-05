// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.net.URI
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalBrowserAdmissionHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")
    private val diagEvents = mutableListOf<DiagEvent>()

    @Test
    fun rejectsInvalidOrForeignHostHeaders() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { FakeBrowserUpstream() },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val port = uri.port
        val validHost = uri.host

        // 1. Valid host passes
        val res1 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $validHost:$port\r\n\r\n")
        assertTrue(res1.startsWith("HTTP/1.1 200 OK"))
        assertNoMarker(res1)

        // 2. Localhost without token is rejected
        val res2 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: localhost:$port\r\n\r\n")
        assertRefused(res2, "mismatch")

        // 3. 127.0.0.1 is rejected
        val res3 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
        assertRefused(res3, "mismatch")

        // 4. Extra labels (e.g. sub.token.localhost) are rejected
        val res4 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: sub.$validHost:$port\r\n\r\n")
        assertRefused(res4, "mismatch")

        // 5. Wrong token is rejected
        val wrongTokenHost = "00000000000000000000000000000000.localhost"
        val res5 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $wrongTokenHost:$port\r\n\r\n")
        assertRefused(res5, "mismatch")

        // 6. Userinfo in Host is rejected
        val res6 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: user@$validHost:$port\r\n\r\n")
        assertRefused(res6, "mismatch")

        // 7. Missing Host is rejected
        val res7 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nUser-Agent: test\r\n\r\n")
        assertRefused(res7, "missing")

        // 8. Wrong port in Host is rejected
        val res8 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $validHost:${port + 1}\r\n\r\n")
        assertRefused(res8, "mismatch")

        // 9. Foreign absolute URI request target is rejected without upstream dispatch
        val res9 = sendRawHttpRequest(port, "GET http://evil.example/malicious HTTP/1.1\r\nHost: $validHost:$port\r\n\r\n")
        assertRefused(res9, "mismatch")

        // 10. Null host in absolute URI (http:///x)
        val resNullHost = sendRawHttpRequest(port, "GET http:///x HTTP/1.1\r\nHost: $validHost:$port\r\n\r\n")
        assertRefused(resNullHost, "mismatch")

        // 11. Foreign absolute URI with no Host header (still mismatch, returns before Host header is read)
        val resAbsNoHost = sendRawHttpRequest(port, "GET http://evil.example/x HTTP/1.1\r\n\r\n")
        assertRefused(resAbsNoHost, "mismatch")

        // 12. Admitted absolute URI pin: session host, different port on URI target, valid Host header -> 200, no marker
        val resAbsOtherPort = sendRawHttpRequest(port, "GET http://$validHost:${port + 10}/abs HTTP/1.1\r\nHost: $validHost:$port\r\n\r\n")
        assertTrue(resAbsOtherPort.startsWith("HTTP/1.1 200 OK"))
        assertNoMarker(resAbsOtherPort)

        // 13. Admitted absolute URI pin with userinfo, valid Host header -> 200, no marker
        val resAbsUser = sendRawHttpRequest(port, "GET http://user:pass@$validHost/abs HTTP/1.1\r\nHost: $validHost:$port\r\n\r\n")
        assertTrue(resAbsUser.startsWith("HTTP/1.1 200 OK"))
        assertNoMarker(resAbsUser)

        // 14. Ordinary session-origin GET -> 200, no marker
        val resOrigin = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $validHost:$port\r\n\r\n")
        assertTrue(resOrigin.startsWith("HTTP/1.1 200 OK"))
        assertNoMarker(resOrigin)

        // 15. Host with no port -> 200, no marker
        val resNoPort = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $validHost\r\n\r\n")
        assertTrue(resNoPort.startsWith("HTTP/1.1 200 OK"))
        assertNoMarker(resNoPort)

        // 16. Mixed-case Host header -> 200, no marker
        val mixedHost = validHost.uppercase(Locale.US)
        val resMixed = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $mixedHost:$port\r\n\r\n")
        assertTrue(resMixed.startsWith("HTTP/1.1 200 OK"))
        assertNoMarker(resMixed)

        // 17. Request line that is not three tokens -> 400 Bad Request, no marker
        val res400 = sendRawHttpRequest(port, "GET /no-version\r\nHost: $validHost:$port\r\n\r\n")
        assertTrue(res400.startsWith("HTTP/1.1 400 Bad Request"))
        assertNoMarker(res400)

        // 18. Header block over 64 KiB -> 431 Request Header Fields Too Large, no marker
        val largeHeaderVal = "a".repeat(70 * 1024)
        val res431Size = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $validHost:$port\r\nX-Large: $largeHeaderVal\r\n\r\n")
        assertTrue(res431Size.startsWith("HTTP/1.1 431 Request Header Fields Too Large"))
        assertNoMarker(res431Size)

        // 19. More than 100 headers -> 431 Request Header Fields Too Large, no marker
        val manyHeaders = (1..105).joinToString("") { "X-Header-$it: value\r\n" }
        val res431Count = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $validHost:$port\r\n$manyHeaders\r\n")
        assertTrue(res431Count.startsWith("HTTP/1.1 431 Request Header Fields Too Large"))
        assertNoMarker(res431Count)

        // 20. Upstream itself returns 403 with body UPSTREAM_FORBIDDEN -> forwarded 403, body preserved, no marker
        val resUpstream403 = sendRawHttpRequest(port, "GET /upstream-forbidden HTTP/1.1\r\nHost: $validHost:$port\r\n\r\n")
        assertTrue(resUpstream403.startsWith("HTTP/1.1 403"))
        assertTrue(resUpstream403.contains("UPSTREAM_FORBIDDEN"))
        assertNoMarker(resUpstream403)

        session.stop()
    }

    private fun assertRefused(response: String, expectedReason: String) {
        assertTrue(response.startsWith("HTTP/1.1 403 Forbidden"), "expected 403 Forbidden: $response")
        assertTrue(response.contains("X-SPL-Loopback-Refused: $expectedReason"), "expected marker $expectedReason: $response")
        assertTrue(response.contains("Content-Type: text/plain; charset=utf-8"), "expected content-type: $response")
        assertTrue(response.contains("Connection: close"), "expected connection close: $response")
        assertTrue(response.contains("Referrer-Policy: no-referrer"), "expected referrer-policy: $response")
        val body = response.substringAfter("\r\n\r\n")
        val bodyLines = body.lines().filter { it.isNotEmpty() }
        assertTrue(bodyLines.size == 1 && bodyLines[0].isNotBlank(), "expected one non-empty body line: $body")
    }

    private fun assertNoMarker(response: String) {
        assertFalse(response.contains("X-SPL-Loopback-Refused"), "marker must be absent: $response")
    }

    private fun sendRawHttpRequest(port: Int, rawHttp: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val out = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            out.write(rawHttp.toByteArray(Charsets.US_ASCII))
            out.flush()
            val baos = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                baos.write(buf, 0, read)
            }
            return baos.toString(Charsets.UTF_8.name())
        }
    }

    private class FakeBrowserUpstream : JournalBrowserUpstream {
        override val isPoisoned: Boolean get() = false
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
            if (path == "/upstream-forbidden") {
                return BrowserHttpResponse(403, listOf("Content-Type" to "text/plain"), "UPSTREAM_FORBIDDEN".encodeToByteArray())
            }
            return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
        }
        override fun close() {}
    }
}
