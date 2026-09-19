// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalBrowserRetryReplayHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")
    private val diagEvents = mutableListOf<DiagEvent>()

    @Test
    fun retriesIdempotentRequestWithFreshClientWhenFirstIsPoisoned() {
        var openCount = 0
        val createdClients = mutableListOf<FakePoisonableBrowserUpstream>()

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                openCount++
                val client = FakePoisonableBrowserUpstream(shouldFail = (openCount == 1))
                createdClients.add(client)
                client
            },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val port = uri.port
        val host = uri.host

        val response = sendRawHttpRequest(port, "GET /idempotent HTTP/1.1\r\nHost: $host:$port\r\n\r\n")

        assertEquals(2, openCount)
        assertTrue(createdClients[0].isPoisoned, "First client should be marked poisoned")
        assertFalse(createdClients[1].isPoisoned, "Second client should be fresh and active")
        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.contains("Success"))
        assertTrue(diagEvents.any { it is DiagEvent.JournalBrowser && it.eventClass == "retry" && it.outcome == "ok" })

        session.stop()
    }

    @Test
    fun identityExceptionTriggersTerminalWithoutRetryOrAmbiguousDelivery() {
        var openCount = 0

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                openCount++
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        throw JournalBrowserIdentityException()
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

        val response = sendRawHttpRequest(port, "GET /idempotent HTTP/1.1\r\nHost: $host:$port\r\n\r\n")

        assertEquals(1, openCount, "Identity exception must not retry")
        assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
        assertFalse(response.contains("AMBIGUOUS_DELIVERY"))
        assertTrue(diagEvents.any { it is DiagEvent.JournalBrowser && it.eventClass == "pairing" && it.outcome == "invalidated" })

        session.stop()
    }

    @Test
    fun unsafePostPutPatchDeleteDropYieldsAmbiguousDeliveryWithoutReplay() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            diagEvents.clear()
            var attempts = 0

            val session = JournalBrowserSession(
                pairing = { pairingGen },
                accessStillCurrent = { true },
                upstreamFactory = {
                    object : JournalBrowserUpstream {
                        override val isPoisoned: Boolean get() = false
                        override fun request(m: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                            attempts++
                            throw IOException("Connection dropped during $m in-flight")
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

            val request = "$method /action HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: 13\r\n" +
                "\r\n" +
                "{\"key\":\"val\"}"

            val response = sendRawHttpRequest(port, request)

            assertEquals(1, attempts, "Method $method must not be replayed")
            assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
            assertTrue(response.contains("AMBIGUOUS_DELIVERY"))
            // Check that Content-Length appears exactly once
            val contentLengthCount = "Content-Length:".toRegex().findAll(response).count()
            assertEquals(1, contentLengthCount, "Content-Length must appear exactly once in response")
            assertTrue(diagEvents.any { it is DiagEvent.JournalBrowser && it.eventClass == "proxy" && it.outcome == "ambiguous" })

            session.stop()
        }
    }

    @Test
    fun canaryInExceptionMessageNeverAppearsInSyntheticBody() {
        val secretCanary = "CANARY_SECRET_LEAK_TOKEN_ABC_123"

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(m: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        throw IOException("Upstream failure with sensitive secret: $secretCanary")
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

        val request = "POST /action HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Content-Length: 2\r\n" +
            "\r\n" +
            "{}"

        val response = sendRawHttpRequest(port, request)

        assertTrue(response.startsWith("HTTP/1.1 502 Bad Gateway"))
        assertTrue(response.contains("AMBIGUOUS_DELIVERY"))
        assertFalse(response.contains(secretCanary), "Canary secret must never appear in response")

        session.stop()
    }

    @Test
    fun headMethodEmitsNoBodyToBrowserClient() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        return BrowserHttpResponse(
                            status = 200,
                            headers = listOf("Content-Type" to "text/plain"),
                            body = "HELLO_WORLD".encodeToByteArray(),
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

        val response = sendRawHttpRequest(port, "HEAD /resource HTTP/1.1\r\nHost: $host:$port\r\n\r\n")

        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.contains("Content-Length: 11"))
        assertFalse(response.contains("HELLO_WORLD"), "HEAD response must not contain body bytes in socket stream")

        session.stop()
    }

    private fun sendRawHttpRequest(port: Int, rawHttp: String): String {
        Socket("127.0.0.1", port).use { socket ->
            val out = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            out.write(rawHttp.toByteArray(Charsets.US_ASCII))
            out.flush()
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                baos.write(buf, 0, read)
            }
            return baos.toString(Charsets.UTF_8.name())
        }
    }

    private class FakePoisonableBrowserUpstream(private val shouldFail: Boolean) : JournalBrowserUpstream {
        private var poisoned = false
        override val isPoisoned: Boolean get() = poisoned

        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
            if (shouldFail) {
                poisoned = true
                throw IOException("Dropped route and poisoned session")
            }
            return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "Success".encodeToByteArray())
        }

        override fun close() {
            poisoned = true
        }
    }
}
