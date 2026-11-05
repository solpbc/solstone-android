// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.identity.PairingGeneration
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class JournalBrowserRetryPoolHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")

    @Test
    fun idempotentRetryDiscardsFailedFreshClientAndPreventsPoolExhaustion() {
        val shouldSucceed = AtomicBoolean(false)
        val concurrentLatch = CountDownLatch(6)

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
                        if (!shouldSucceed.get()) {
                            throw IOException("simulated upstream failure before headers")
                        }
                        concurrentLatch.countDown()
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

        val concurrentSockets = mutableListOf<Socket>()
        try {
            val origin = session.start()
            val uri = URI(origin.url)
            val port = uri.port
            val host = uri.host

            // Phase 1: 6 sequential GETs that fail both initial and retry
            for (i in 1..6) {
                Socket("127.0.0.1", port).use { s ->
                    s.soTimeout = 5000
                    val out = s.getOutputStream()
                    out.write("GET /fail$i HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()

                    val buf = ByteArray(1024)
                    val read = s.getInputStream().read(buf)
                    val resp = if (read > 0) String(buf, 0, read, Charsets.US_ASCII) else ""
                    assertTrue(resp.startsWith("HTTP/1.1 502"), "expected 502 on sequential run $i, but was $resp")
                }
            }

            // Phase 2: set flag, 6 concurrent GETs must succeed without pool exhaustion
            shouldSucceed.set(true)
            for (i in 1..6) {
                val s = Socket("127.0.0.1", port).apply {
                    soTimeout = 5000
                    val out = getOutputStream()
                    out.write("GET /success$i HTTP/1.1\r\nHost: $host:$port\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
                concurrentSockets.add(s)
            }

            assertTrue(concurrentLatch.await(5, TimeUnit.SECONDS), "all 6 concurrent requests did not enter requestStreaming")

            for (s in concurrentSockets) {
                val buf = ByteArray(1024)
                val read = s.getInputStream().read(buf)
                val resp = if (read > 0) String(buf, 0, read, Charsets.US_ASCII) else ""
                assertTrue(resp.startsWith("HTTP/1.1 200"), "expected 200 on concurrent socket but was $resp")
            }
        } finally {
            concurrentSockets.forEach { runCatching { it.close() } }
            session.stop()
        }
    }
}
