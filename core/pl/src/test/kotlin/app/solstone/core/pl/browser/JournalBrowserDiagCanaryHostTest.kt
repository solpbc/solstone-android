// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.diagnostics.formatDiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalBrowserDiagCanaryHostTest {

    private val pairingGen = PairingGeneration("inst-secret-canary", "sha256:cert-secret-canary")
    private val diagEvents = mutableListOf<DiagEvent>()

    @Test
    fun diagEventsNeverContainSecretsOrUnclosedStrings() {
        val secretCanaries = listOf(
            "inst-secret-canary",
            "cert-secret-canary",
            "super-secret-cookie-val",
            "secret-bearer-token",
            "payload-canary-secret",
            "userinfo-secret",
            "query-param-secret-value",
            "thrown-exception-sensitive-message-12345",
            "malformed-header-secret-leak",
            "custom-status-text-secret",
        )

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        if (path.contains("throw")) {
                            throw IOException("thrown-exception-sensitive-message-12345")
                        }
                        return BrowserHttpResponse(
                            status = 200,
                            headers = listOf(
                                "Content-Type" to "text/plain",
                                "Set-Cookie" to "auth=super-secret-cookie-val; Path=/",
                            ),
                            body = "payload-canary-secret".encodeToByteArray(),
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

        val request = "GET /path/with/secret?q=query-param-secret-value HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Authorization: Bearer secret-bearer-token\r\n" +
            "Cookie: auth=super-secret-cookie-val\r\n" +
            "X-Malformed-Canary: malformed-header-secret-leak\r\n" +
            "\r\n"

        sendRawHttpRequest(port, request)

        // Also trigger failure path
        val postRequest = "POST /throw HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Content-Length: 5\r\n" +
            "\r\n" +
            "hello"
        sendRawHttpRequest(port, postRequest)

        session.stop()

        assertTrue(diagEvents.isNotEmpty())

        val validClasses = setOf("admission", "lifecycle", "proxy", "redirect", "retry", "pairing", "teardown", "limit")
        val validOutcomes = setOf("ok", "rejected", "timeout", "ambiguous", "invalidated", "bound", "stopped", "capacity")

        for (event in diagEvents) {
            val formatted = formatDiagEvent(event)
            assertTrue(formatted.startsWith("kind=journal-browser"))

            if (event is DiagEvent.JournalBrowser) {
                assertTrue(event.eventClass in validClasses, "Invalid class: ${event.eventClass}")
                assertTrue(event.outcome in validOutcomes, "Invalid outcome: ${event.outcome}")
            }

            for (canary in secretCanaries) {
                assertFalse(formatted.contains(canary), "Leaked canary '$canary' in '$formatted'")
            }
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
}
