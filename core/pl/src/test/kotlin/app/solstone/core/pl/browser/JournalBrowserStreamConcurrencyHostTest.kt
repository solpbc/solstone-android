// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalBrowserStreamConcurrencyHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")

    @Test
    fun streamsEventStreamAndConcurrentGetRequestsUnderMaxActiveClients() {
        val streamEntered = CountDownLatch(1)
        val releaseStream = CountDownLatch(1)
        val fiveEntered = CountDownLatch(5)
        val releaseFive = CountDownLatch(1)

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
                        if (path == "/stream") {
                            responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Type" to "text/event-stream"))
                            streamEntered.countDown()
                            if (!releaseStream.await(15, TimeUnit.SECONDS)) {
                                throw IOException("releaseStream timed out")
                            }
                        } else {
                            fiveEntered.countDown()
                            if (!releaseFive.await(15, TimeUnit.SECONDS)) {
                                throw IOException("releaseFive timed out")
                            }
                            val body = "OK".toByteArray(Charsets.US_ASCII)
                            responseSink.onStatusAndHeaders(
                                200,
                                "OK",
                                listOf("Content-Type" to "text/plain", "Content-Length" to body.size.toString()),
                            )
                            responseSink.onBodyChunk(body, 0, body.size)
                            responseSink.onComplete()
                        }
                    }

                    override fun close() {}
                }
            },
            diag = {},
        )

        var streamSocket: Socket? = null
        val sockets = mutableListOf<Socket>()
        try {
            val origin = session.start()
            val uri = URI(origin.url)
            val port = uri.port
            val host = uri.host

            streamSocket = Socket("127.0.0.1", port).apply {
                soTimeout = 5000
                val out = getOutputStream()
                out.write("GET /stream HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            }
            assertTrue(streamEntered.await(5, TimeUnit.SECONDS), "stream did not enter")

            for (i in 1..5) {
                val s = Socket("127.0.0.1", port).apply {
                    soTimeout = 5000
                    val out = getOutputStream()
                    out.write("GET /get$i HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
                sockets.add(s)
            }

            assertTrue(fiveEntered.await(5, TimeUnit.SECONDS), "five GETs did not all enter requestStreaming")

            releaseFive.countDown()

            for (s in sockets) {
                val buf = ByteArray(1024)
                val read = s.getInputStream().read(buf)
                val resp = if (read > 0) String(buf, 0, read, Charsets.US_ASCII) else ""
                assertTrue(resp.startsWith("HTTP/1.1 200"), "expected 200 but was $resp")
            }
        } finally {
            releaseStream.countDown()
            releaseFive.countDown()
            runCatching { streamSocket?.close() }
            sockets.forEach { runCatching { it.close() } }
            session.stop()
        }
    }

    @Test
    fun enforcesActiveClientCapacityOfSixAndAdmitsSeventhAfterCompletion() {
        val releaseSix = Array(6) { CountDownLatch(1) }
        val entriesCount = AtomicInteger(0)
        val sevenAdmissions = CountDownLatch(7)

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
                        val idx = entriesCount.getAndIncrement()
                        if (idx < 6) {
                            responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Type" to "text/plain"))
                            if (!releaseSix[idx].await(15, TimeUnit.SECONDS)) {
                                throw IOException("releaseSix[$idx] timed out")
                            }
                            val body = "OK".toByteArray(Charsets.US_ASCII)
                            responseSink.onBodyChunk(body, 0, body.size)
                            responseSink.onComplete()
                        } else {
                            val body = "SEVENTH".toByteArray(Charsets.US_ASCII)
                            responseSink.onStatusAndHeaders(
                                200,
                                "OK",
                                listOf("Content-Type" to "text/plain", "Content-Length" to body.size.toString()),
                            )
                            responseSink.onBodyChunk(body, 0, body.size)
                            responseSink.onComplete()
                        }
                    }

                    override fun close() {}
                }
            },
            diag = {
                if (it is DiagEvent.JournalBrowser && it.eventClass == "admission" && it.outcome == "ok") {
                    sevenAdmissions.countDown()
                }
            },
        )

        val sockets = mutableListOf<Socket>()
        try {
            val origin = session.start()
            val uri = URI(origin.url)
            val port = uri.port
            val host = uri.host

            for (i in 1..6) {
                val s = Socket("127.0.0.1", port).apply {
                    soTimeout = 5000
                    val out = getOutputStream()
                    out.write("GET /get$i HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
                sockets.add(s)
            }

            val s7 = Socket("127.0.0.1", port).apply {
                soTimeout = 5000
                val out = getOutputStream()
                out.write("GET /get7 HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            }
            sockets.add(s7)

            assertTrue(sevenAdmissions.await(5, TimeUnit.SECONDS), "7 admissions did not occur")
            assertEquals(6, entriesCount.get(), "entry count should still be 6 before completing one")

            releaseSix[0].countDown()

            val buf = ByteArray(1024)
            val read = s7.getInputStream().read(buf)
            val resp = if (read > 0) String(buf, 0, read, Charsets.US_ASCII) else ""
            assertTrue(resp.startsWith("HTTP/1.1 200"), "expected 200 on socket7 but was $resp")
        } finally {
            releaseSix.forEach { it.countDown() }
            sockets.forEach { runCatching { it.close() } }
            session.stop()
        }
    }
}
