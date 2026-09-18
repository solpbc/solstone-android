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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalBrowserPairingHostTest {

    private val diagEvents = mutableListOf<DiagEvent>()

    @Test
    fun generationChangeTerminatesSessionAndInvalidatesToken() {
        var currentGen: PairingGeneration? = PairingGeneration("inst1", "sha256:cert1")

        val session = JournalBrowserSession(
            pairing = { currentGen },
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

        // 1. Initial request succeeds
        val res1 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res1.startsWith("HTTP/1.1 200 OK"))

        // 2. Rotate pairing generation
        currentGen = PairingGeneration("inst1", "sha256:cert2")

        // 3. Subsequent request encounters invalidated pairing -> returns 502 (NOT 503)
        val res2 = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res2.startsWith("HTTP/1.1 502 Bad Gateway") || res2.isEmpty())
        assertTrue(diagEvents.any { it is DiagEvent.JournalBrowser && it.eventClass == "pairing" && it.outcome == "invalidated" })

        // 4. Port is now closed
        var closed = false
        try {
            Socket("127.0.0.1", port).use {}
        } catch (_: Exception) {
            closed = true
        }
        assertTrue(closed)
    }

    @Test
    fun forgottenJournalTerminatesSession() {
        var currentGen: PairingGeneration? = PairingGeneration("inst1", "sha256:cert1")

        val session = JournalBrowserSession(
            pairing = { currentGen },
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

        // Forget journal (null pairing)
        currentGen = null

        val res = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res.startsWith("HTTP/1.1 502 Bad Gateway") || res.isEmpty())
        assertTrue(diagEvents.any { it is DiagEvent.JournalBrowser && it.eventClass == "pairing" && it.outcome == "invalidated" })
    }

    @Test
    fun accessRevocationTerminatesSession() {
        val currentGen = PairingGeneration("inst1", "sha256:cert1")
        var accessCurrent = true

        val session = JournalBrowserSession(
            pairing = { currentGen },
            accessStillCurrent = { accessCurrent },
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

        // Revoke access
        accessCurrent = false

        val res = sendRawHttpRequest(port, "GET / HTTP/1.1\r\nHost: $host:$port\r\n\r\n")
        assertTrue(res.startsWith("HTTP/1.1 502 Bad Gateway") || res.isEmpty())
        assertTrue(diagEvents.any { it is DiagEvent.JournalBrowser && it.eventClass == "pairing" && it.outcome == "invalidated" })
    }

    private fun sendRawHttpRequest(port: Int, rawHttp: String): String {
        try {
            Socket("127.0.0.1", port).use { socket ->
                val out = BufferedOutputStream(socket.getOutputStream())
                val input = BufferedInputStream(socket.getInputStream())
                out.write(rawHttp.toByteArray(Charsets.US_ASCII))
                out.flush()
                val buf = ByteArray(4096)
                val read = input.read(buf)
                return if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
            }
        } catch (_: Exception) {
            return ""
        }
    }
}
