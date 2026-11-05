// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.identity.PairingGeneration
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalBrowserPoolOpenHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")

    /** The page's first requests arrive together; none may wait for another's carrier to open. */
    @Test
    fun carriersOpenAtTheSameTime() {
        val allOpening = CountDownLatch(3)
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                allOpening.countDown()
                allOpening.await(TIMEOUT_S, TimeUnit.SECONDS)
                FakeUpstream()
            },
            diag = {},
        )
        val origin = URI(session.start().url)
        val answers = CopyOnWriteArrayList<String>()
        val clients = List(3) { thread { answers.add(get(origin)) } }

        val together = allOpening.await(TIMEOUT_S, TimeUnit.SECONDS)
        clients.forEach { it.join() }
        session.stop()

        assertTrue(together, "a carrier waited for another to finish opening")
        assertEquals(3, answers.count { it.startsWith("HTTP/1.1 200") }, "$answers")
    }

    /** Stopping does not wait for a carrier still opening, and that carrier is closed when it arrives. */
    @Test
    fun aCarrierThatOpensAfterStopIsClosed() {
        val opening = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                opening.countDown()
                // A socket connect doesn't stop for an interrupt, and neither does this.
                while (true) {
                    try {
                        mayFinish.await(TIMEOUT_S, TimeUnit.SECONDS)
                        break
                    } catch (_: InterruptedException) {
                    }
                }
                FakeUpstream(onClose = { closed.countDown() })
            },
            diag = {},
        )
        val origin = URI(session.start().url)
        val client = thread { runCatching { get(origin) } }
        assertTrue(opening.await(TIMEOUT_S, TimeUnit.SECONDS))

        session.stop()
        mayFinish.countDown()

        assertTrue(closed.await(TIMEOUT_S, TimeUnit.SECONDS), "the late carrier was left open")
        client.join()
    }

    private fun get(origin: URI): String {
        Socket("127.0.0.1", origin.port).use { socket ->
            socket.soTimeout = TimeUnit.SECONDS.toMillis(TIMEOUT_S).toInt()
            socket.getOutputStream().apply {
                write("GET / HTTP/1.1\r\nHost: ${origin.host}:${origin.port}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val out = ByteArrayOutputStream()
            socket.getInputStream().copyTo(out)
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private class FakeUpstream(
        private val onClose: () -> Unit = {},
    ) : JournalBrowserUpstream {
        override val isPoisoned: Boolean get() = false
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
            return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
        }
        override fun close() = onClose()
    }

    private companion object {
        const val TIMEOUT_S = 15L
    }
}
