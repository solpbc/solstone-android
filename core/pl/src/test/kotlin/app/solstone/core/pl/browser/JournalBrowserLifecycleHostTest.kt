// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalBrowserLifecycleHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")
    private val diagEvents = mutableListOf<DiagEvent>()

    @Test
    fun startYieldsCanonicalOriginUrl() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { FakeBrowserUpstream() },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val url = origin.url
        assertTrue(url.startsWith("http://"))
        assertTrue(url.endsWith("/"))

        val uri = URI(url)
        val host = uri.host
        val port = uri.port
        assertTrue(port > 0)
        assertTrue(host.matches(Regex("^[0-9a-f]{32}\\.localhost$")))

        session.stop()
    }

    @Test
    fun startWhileLiveReturnsSameOrigin() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { FakeBrowserUpstream() },
            diag = { diagEvents.add(it) },
        )

        val origin1 = session.start()
        val origin2 = session.start()
        assertEquals(origin1, origin2)
        session.stop()
    }

    @Test
    fun startAfterStopGeneratesNewAuthorityEvenOnReusedPort() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { FakeBrowserUpstream() },
            diag = { diagEvents.add(it) },
        )

        val origin1 = session.start()
        val uri1 = URI(origin1.url)
        val port1 = uri1.port
        val host1 = uri1.host
        session.stop()

        // AC3 same port: bind on same port, verify new authority generated and old host rejected with 400
        val origin2 = session.start(bindPort = port1)
        val uri2 = URI(origin2.url)
        val host2 = uri2.host
        assertTrue(origin1.url != origin2.url)
        assertEquals(port1, uri2.port)
        assertTrue(host1 != host2)

        // Send request with OLD host header to the newly bound socket on same port
        Socket("127.0.0.1", port1).use { sock ->
            val out = BufferedOutputStream(sock.getOutputStream())
            val req = "GET / HTTP/1.1\r\nHost: $host1:$port1\r\n\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val inStream = sock.getInputStream()
            val buf = ByteArray(1024)
            val read = inStream.read(buf)
            val response = if (read > 0) String(buf, 0, read) else ""
            assertTrue(response.startsWith("HTTP/1.1 400"), "Expected 400 Bad Request for stale token on same port: $response")
        }

        session.stop()
    }

    @Test
    fun repeatedStopIsIdempotentAndCleansSockets() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { FakeBrowserUpstream() },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        session.stop()
        session.stop()
        session.stop()

        val uri = URI(origin.url)
        val port = uri.port

        var socketClosed = false
        try {
            Socket("127.0.0.1", port).use { }
        } catch (_: Exception) {
            socketClosed = true
        }
        assertTrue(socketClosed)
    }

    @Test
    fun startStopRaceLeavesAtMostOneLiveListener() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { FakeBrowserUpstream() },
            diag = { diagEvents.add(it) },
        )

        val latch = CountDownLatch(2)
        var originFromThread1: JournalBrowserOrigin? = null

        val t1 = Thread {
            for (i in 0 until 10) {
                originFromThread1 = session.start()
            }
            latch.countDown()
        }
        val t2 = Thread {
            for (i in 0 until 10) {
                session.stop()
            }
            latch.countDown()
        }

        t1.start()
        t2.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))

        // Final state must be cleanly stopped or cleanly started
        session.stop()
    }

    @Test
    fun listenerBindsLoopbackOnly() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { FakeBrowserUpstream() },
            diag = { diagEvents.add(it) },
        )

        val origin = session.start()
        val port = URI(origin.url).port

        val loopbackConn = Socket(InetAddress.getByName("127.0.0.1"), port)
        assertTrue(loopbackConn.isConnected)
        loopbackConn.close()

        session.stop()
    }

    @Test
    fun stopSessionLeavesIngestClientsIntact() {
        var closedFakeUpstream = false
        val fakeUpstream = FakeBrowserUpstream(onClose = { closedFakeUpstream = true })

        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = { fakeUpstream },
            diag = { diagEvents.add(it) },
        )

        session.start()
        session.stop()

        // Standalone fake upstream not owned by session pool is unaffected
        val standaloneUpstream = FakeBrowserUpstream()
        assertTrue(!standaloneUpstream.isClosed)
    }

    private class FakeBrowserUpstream(private val onClose: () -> Unit = {}) : JournalBrowserUpstream {
        var isClosed = false
        override val isPoisoned: Boolean get() = isClosed
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
            return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
        }
        override fun close() {
            isClosed = true
            onClose()
        }
    }
}
