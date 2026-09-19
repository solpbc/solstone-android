// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.ProgressiveBrowserResponseParser
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryLedgerHostTest {

    private class TestRoot(
        override val category: LedgerCategory,
        override val capacity: Long,
        private var retained: Long = capacity,
    ) : LiveRoot {
        override fun retainedBytes(): Long = retained
        fun setRetained(bytes: Long) { retained = bytes }
    }

    private class NoOpSink : BrowserResponseSink {
        override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {}
        override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
        override fun onComplete() {}
    }

    @BeforeTest
    fun setUp() {
        MemoryLedger.reset()
    }

    @Test
    fun leakPlateauAcrossRequestCycles() {
        val initialBytes = MemoryLedger.measureRetainedBytes()
        assertEquals(0L, initialBytes)

        for (cycle in 1..10) {
            val parser = ProgressiveBrowserResponseParser(NoOpSink())
            val feedData = "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ntest".toByteArray(Charsets.US_ASCII)
            parser.feed(feedData, 0, feedData.size)
            parser.onMuxClose(successful = true)
        }

        // Retained bytes must plateau back to 0 (no monotonic leak across cycles)
        val finalBytes = MemoryLedger.measureRetainedBytes()
        assertEquals(0L, finalBytes)
    }

    @Test
    fun plantedUnregisteredProductionRootBreaksEqualityUntilRegistered() {
        val registeredRoot = TestRoot(LedgerCategory.MUX_HEADER_PAYLOAD, 1024 * 1024L)
        MemoryLedger.registerRoot(registeredRoot)

        // Create a production LiveRoot and feed 64 KiB
        val prodParser = ProgressiveBrowserResponseParser(NoOpSink())
        val chunk = "X-Header: ${"a".repeat(60 * 1024)}\r\n".toByteArray(Charsets.US_ASCII)
        prodParser.feed(chunk, 0, chunk.size)

        val systemWalkWithProductionRoot = registeredRoot.retainedBytes() + prodParser.capacity
        assertEquals(systemWalkWithProductionRoot, MemoryLedger.measureRetainedBytes())

        // If unregistering prodParser manually to simulate unregistered production root:
        MemoryLedger.unregisterRoot(prodParser)
        val unregisteredWalk = registeredRoot.retainedBytes() + prodParser.capacity
        assertTrue(unregisteredWalk != MemoryLedger.measureRetainedBytes())

        // Registering it restores equality
        MemoryLedger.registerRoot(prodParser)
        assertEquals(unregisteredWalk, MemoryLedger.measureRetainedBytes())
    }

    @Test
    fun fourCarrierEightWaiterHighWaterWithinEnvelope() {
        // 4 carriers * (TLS 4 MiB + Mux 4 MiB + Output 2 MiB + Parser 1 MiB) = 44 MiB
        // 8 waiters * 512 KiB per-flow queue = 4 MiB
        // Callback + WS queues = 2 MiB
        // Total = 50 MiB <= 52 MiB
        val roots = listOf(
            TestRoot(LedgerCategory.TLS, 16 * 1024 * 1024L),
            TestRoot(LedgerCategory.MUX_HEADER_PAYLOAD, 16 * 1024 * 1024L),
            TestRoot(LedgerCategory.DOWNSTREAM_OUTPUT, 8 * 1024 * 1024L),
            TestRoot(LedgerCategory.HTTP_PARSER_HEADER_TRAILER, 4 * 1024 * 1024L),
            TestRoot(LedgerCategory.PER_FLOW_QUEUE, 4 * 1024 * 1024L),
            TestRoot(LedgerCategory.WS_QUEUED_CURRENT, 1 * 1024 * 1024L),
            TestRoot(LedgerCategory.CALLBACK, 1 * 1024 * 1024L),
        )

        roots.forEach { MemoryLedger.registerRoot(it) }

        assertEquals(50 * 1024 * 1024L, MemoryLedger.totalRegisteredCapacity())
        assertEquals(50 * 1024 * 1024L, MemoryLedger.measureRetainedBytes())
        assertTrue(MemoryLedger.isWithinEnvelope())
    }

    @Test
    fun exceedingEnvelopeBy64KiBFailsBoundCheck() {
        val envelopeRoot = TestRoot(LedgerCategory.DOWNSTREAM_OUTPUT, 52 * 1024 * 1024L)
        MemoryLedger.registerRoot(envelopeRoot)
        assertTrue(MemoryLedger.isWithinEnvelope())

        val excessRoot = TestRoot(LedgerCategory.PER_FLOW_QUEUE, 64 * 1024L)
        MemoryLedger.registerRoot(excessRoot)
        assertFalse(MemoryLedger.isWithinEnvelope())
    }
}
