// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.identity.PairingGeneration
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A session that ends under a live page leaves the kept origin's port held and refusing, so another
 * app waiting on that port is never handed one of the page's requests, however long the page goes
 * on asking.
 *
 * The page here behaves like the journal's own: it holds `EventSource('/sse/events')` open and
 * reconnects by itself whenever the stream ends.
 */
class JournalBrowserListenerOutlivesPageHostTest {

    private companion object {
        /** A connection closed with no response. */
        const val CLOSED = -1
    }

    private val pairingA = PairingGeneration("inst1", "sha256:client-a")
    private val pairingB = PairingGeneration("inst1", "sha256:client-b")

    private class MemoryOriginStore : JournalBrowserOriginStore {
        @Volatile var record: JournalBrowserOriginRecord? = null
        override fun read() = record
        override fun write(record: JournalBrowserOriginRecord) { this.record = record }
        override fun clear() { record = null }
    }

    private val keeper = JournalBrowserOriginKeeper(MemoryOriginStore())

    private fun session(pairing: PairingGeneration = pairingA) = JournalBrowserSession(
        pairing = { pairing },
        accessStillCurrent = { true },
        upstreamFactory = { EventStreamUpstream() },
        diag = {},
        originKeeper = keeper,
    )

    @Test
    fun aSessionEndedFromTheProxySideHandsNothingToAnotherApp() {
        assertNoRequestReachesASquatter { s ->
            s.triggerTerminal(BrowserTerminalReason(BrowserTerminalClass.SESSION_CARRIER_LOSS))
        }
    }

    @Test
    fun aSheetClosedUnderALivePageHandsNothingToAnotherApp() {
        // What the pane does when the sheet closes, whatever Compose destroys first.
        assertNoRequestReachesASquatter { s -> s.stop() }
    }

    @Test
    fun theNextOpenServesFromThePortTheProcessKept() {
        val first = session()
        val origin = first.start().url
        first.stop()
        val second = session()
        assertEquals(origin, second.start().url)
        val uri = URI(origin)
        val page = ReconnectingEventSource(uri.host, uri.port)
        try {
            page.start()
            assertTrue(page.awaitStatus(200), "the reopened journal is served: ${page.statuses}")
        } finally {
            page.destroy()
            second.stop()
            keeper.clear()
        }
    }

    @Test
    fun aConnectionTheBrowserOpenedAheadIsClosedWhenTheSessionStops() {
        // The browser opens connections before it needs them and pools them by host and port, so a
        // reopened journal on the same origin may send its first request down one the last session
        // accepted. That one must be closed, which the browser replaces, never answered with a refusal.
        val first = session()
        val uri = URI(first.start().url)
        Socket("127.0.0.1", uri.port).use { early ->
            early.soTimeout = 3_000
            Thread.sleep(200)
            first.stop()
            val second = session()
            assertEquals(uri.toString(), second.start().url)
            val answer = try {
                early.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n\r\n".toByteArray(Charsets.US_ASCII),
                )
                early.getOutputStream().flush()
                early.getInputStream().read()
            } catch (_: SocketTimeoutException) {
                -2
            } catch (_: IOException) {
                -1
            }
            // -2 would be a connection left open and never answered: the page hangs.
            assertEquals(-1, answer, "the stopped session's connection is closed, not answered or left hanging")
            second.stop()
        }
        keeper.clear()
    }

    @Test
    fun aConnectionOpenedBetweenSessionsIsClosedUnanswered() {
        // A refusal written before any request would wait in the browser's pool for the next
        // open's document request, which would read it as the answer.
        val s = session()
        val uri = URI(s.start().url)
        s.stop()
        Socket("127.0.0.1", uri.port).use { early ->
            early.soTimeout = 3_000
            val first = try {
                early.getInputStream().read()
            } catch (_: SocketTimeoutException) {
                -2
            }
            assertEquals(-1, first, "closed without a byte written")
        }
        keeper.clear()
    }

    @Test
    fun aRetiredOriginLetsItsPortGoAndIsNeverServedAgain() {
        val underA = session(pairingA)
        val a = URI(underA.start().url)
        underA.stop()
        // Unpairing deletes the origin and lets its port go.
        keeper.clear()
        ServerSocket().use { other ->
            other.reuseAddress = true
            other.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), a.port))
        }
        // A different pairing never gets the old token.
        val underB = session(pairingB)
        val b = URI(underB.start().url)
        assertNotEquals(a.host, b.host)
        underB.stop()
        keeper.clear()
    }

    private fun assertNoRequestReachesASquatter(end: (JournalBrowserSession) -> Unit) {
        val s = session()
        val uri = URI(s.start().url)
        val page = ReconnectingEventSource(uri.host, uri.port)
        val squatter = Squatter(uri.port)
        try {
            page.start()
            assertTrue(page.awaitStatus(200), "the page's live channel should open: ${page.statuses}")
            squatter.start()

            end(s)
            val endedAt = page.statuses.size
            // The page keeps reconnecting, and each attempt reaches our listener, which closes it.
            assertTrue(page.awaitStatus(CLOSED, after = endedAt), "a reconnect should be refused: ${page.statuses}")
            // The page is destroyed, but nothing says its last request has been sent.
            page.destroy()
            Thread.sleep(500)

            assertTrue(!squatter.bound.get(), "nothing else may bind the port while the origin is in use")
            assertEquals(0, squatter.received.get(), "no request may reach another app's listener")
            assertTrue(
                page.statuses.drop(endedAt).all { it == CLOSED },
                "after the end every request is refused: ${page.statuses}",
            )
        } finally {
            page.destroy()
            squatter.stop()
            s.stop()
            keeper.clear()
        }
    }

    /** The journal's live channel: an event stream that stays open until the carrier closes. */
    private class EventStreamUpstream : JournalBrowserUpstream {
        private val closed = CountDownLatch(1)
        override val isPoisoned: Boolean get() = false

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): BrowserHttpResponse = error("streaming only")

        override fun requestStreaming(
            method: String,
            path: String,
            headers: List<Pair<String, String>>,
            bodySource: BrowserRequestBodySource?,
            responseSink: BrowserResponseSink,
        ) {
            responseSink.onStatusAndHeaders(
                200,
                "OK",
                listOf("Content-Type" to "text/event-stream", "Transfer-Encoding" to "chunked"),
            )
            val event = "retry: 10\n\ndata: hello\n\n".toByteArray(Charsets.UTF_8)
            responseSink.onBodyChunk(event, 0, event.size)
            closed.await(30, TimeUnit.SECONDS)
            throw IOException("carrier closed")
        }

        override fun close() {
            closed.countDown()
        }
    }

    /** Reconnects to its origin every time the stream ends, as `EventSource` does. */
    private class ReconnectingEventSource(private val host: String, private val port: Int) {
        val statuses = CopyOnWriteArrayList<Int>()
        private val alive = AtomicBoolean(true)
        private val thread = Thread({ run() }, "page-event-source").apply { isDaemon = true }
        @Volatile private var socket: Socket? = null

        fun start() = thread.start()

        private fun run() {
            while (alive.get()) {
                try {
                    Socket("127.0.0.1", port).use { s ->
                        socket = s
                        s.soTimeout = 5_000
                        val request = "GET /sse/events HTTP/1.1\r\nHost: $host:$port\r\n" +
                            "Accept: text/event-stream\r\n\r\n"
                        s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                        s.getOutputStream().flush()
                        val input = s.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n")) {
                            val b = input.read()
                            if (b < 0) break
                            head.append(b.toChar())
                        }
                        // No status line: the connection was closed unanswered.
                        statuses.add(head.split(" ").getOrNull(1)?.toIntOrNull() ?: CLOSED)
                        val buf = ByteArray(1024)
                        while (input.read(buf) >= 0) Unit
                    }
                } catch (_: Exception) {
                }
                socket = null
                Thread.sleep(10)
            }
        }

        fun awaitStatus(status: Int, after: Int = 0): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (statuses.drop(after).contains(status)) return true
                Thread.sleep(10)
            }
            return false
        }

        /** The web view is gone: nothing more is asked of the origin. */
        fun destroy() {
            alive.set(false)
            runCatching { socket?.close() }
            if (thread.isAlive) thread.join(5_000)
        }
    }

    /** Another app spinning on `bind()` for the port, counting every connection it is handed. */
    private class Squatter(private val port: Int) {
        val bound = AtomicBoolean(false)
        val received = AtomicInteger(0)
        private val running = AtomicBoolean(true)
        private val thread = Thread({ run() }, "squatter").apply { isDaemon = true }

        fun start() = thread.start()

        private fun run() {
            var server: ServerSocket? = null
            while (running.get() && server == null) {
                server = try {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                    }
                } catch (_: IOException) {
                    null
                }
            }
            server ?: return
            server.use { ss ->
                bound.set(true)
                ss.soTimeout = 50
                while (running.get()) {
                    try {
                        ss.accept().use { received.incrementAndGet() }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        }

        fun stop() {
            running.set(false)
            if (thread.isAlive) thread.join(5_000)
        }
    }
}
