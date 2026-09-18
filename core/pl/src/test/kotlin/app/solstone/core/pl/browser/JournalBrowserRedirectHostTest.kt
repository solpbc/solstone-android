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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalBrowserRedirectHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")
    private val diagEvents = mutableListOf<DiagEvent>()

    @Test
    fun rebasesSameJournalRedirectToLocalOrigin() {
        var returnLocation = "/journal/entry/1"

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        return BrowserHttpResponse(
                            status = 302,
                            headers = listOf("Location" to returnLocation),
                            body = ByteArray(0),
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

        // 1. Relative path redirect
        returnLocation = "/journal/entry/1?filter=true#anchor"
        val res1 = sendRawHttpRequest(port, "GET /redirect HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res1.startsWith("HTTP/1.1 302 Found"))
        assertTrue(res1.contains("Location: http://$host:$port/journal/entry/1?filter=true#anchor"))

        // 2. Absolute spl.local redirect
        returnLocation = "https://spl.local/assets/bundle.js"
        val res2 = sendRawHttpRequest(port, "GET /redirect HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res2.startsWith("HTTP/1.1 302 Found"))
        assertTrue(res2.contains("Location: http://$host:$port/assets/bundle.js"))

        session.stop()
    }

    @Test
    fun rejectsForeignRedirectWithBadGateway() {
        var returnLocation = "https://evil.example.com/steal"

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        return BrowserHttpResponse(
                            status = 302,
                            headers = listOf("Location" to returnLocation),
                            body = ByteArray(0),
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

        // 1. Foreign domain
        returnLocation = "https://evil.example.com/steal"
        val res1 = sendRawHttpRequest(port, "GET /redirect HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res1.startsWith("HTTP/1.1 502 Bad Gateway"))
        assertFalse(res1.contains("Location:"))

        // 2. Javascript scheme
        returnLocation = "javascript:alert(1)"
        val res2 = sendRawHttpRequest(port, "GET /redirect HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res2.startsWith("HTTP/1.1 502 Bad Gateway"))
        assertFalse(res2.contains("Location:"))

        // 3. Userinfo in redirect
        returnLocation = "http://user:pass@spl.local/dashboard"
        val res3 = sendRawHttpRequest(port, "GET /redirect HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res3.startsWith("HTTP/1.1 502 Bad Gateway"))
        assertFalse(res3.contains("Location:"))

        // 4. Data URI scheme (AC10)
        returnLocation = "data:text/html,<script>alert(1)</script>"
        val res4 = sendRawHttpRequest(port, "GET /redirect HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res4.startsWith("HTTP/1.1 502 Bad Gateway"))
        assertFalse(res4.contains("Location:"))

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
