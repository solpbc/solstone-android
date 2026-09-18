// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.net.URI
import kotlin.test.Test
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

        // 2. Localhost without token is rejected
        val res2 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: localhost:$port\r\n\r\n")
        assertTrue(res2.startsWith("HTTP/1.1 400 Bad Request"))

        // 3. 127.0.0.1 is rejected
        val res3 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
        assertTrue(res3.startsWith("HTTP/1.1 400 Bad Request"))

        // 4. Extra labels (e.g. sub.token.localhost) are rejected
        val res4 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: sub.$validHost:$port\r\n\r\n")
        assertTrue(res4.startsWith("HTTP/1.1 400 Bad Request"))

        // 5. Wrong token is rejected
        val wrongTokenHost = "00000000000000000000000000000000.localhost"
        val res5 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $wrongTokenHost:$port\r\n\r\n")
        assertTrue(res5.startsWith("HTTP/1.1 400 Bad Request"))

        // 6. Userinfo in Host is rejected (AC6)
        val res6 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: user@$validHost:$port\r\n\r\n")
        assertTrue(res6.startsWith("HTTP/1.1 400 Bad Request"))

        // 7. Missing Host is rejected (AC6)
        val res7 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nUser-Agent: test\r\n\r\n")
        assertTrue(res7.startsWith("HTTP/1.1 400 Bad Request"))

        // 8. Wrong port in Host is rejected (AC6)
        val res8 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $validHost:${port + 1}\r\n\r\n")
        assertTrue(res8.startsWith("HTTP/1.1 400 Bad Request"))

        // 9. Foreign absolute URI request target is rejected without upstream dispatch
        val res9 = sendRawHttpRequest(port, "GET http://evil.example/malicious HTTP/1.1\r\nHost: $validHost:$port\r\n\r\n")
        assertTrue(res9.startsWith("HTTP/1.1 400 Bad Request"))

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

    private class FakeBrowserUpstream : JournalBrowserUpstream {
        override val isPoisoned: Boolean get() = false
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
            return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
        }
        override fun close() {}
    }
}
