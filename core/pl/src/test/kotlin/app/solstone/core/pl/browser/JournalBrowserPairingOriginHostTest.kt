// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.PairingGeneration
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One origin per pairing: kept across opens and relaunches, replaced when its port is taken or its pairing ends. */
class JournalBrowserPairingOriginHostTest {

    private val pairingA = PairingGeneration("inst1", "sha256:client-a")
    private val pairingB = PairingGeneration("inst1", "sha256:client-b")
    private val keepers = mutableListOf<JournalBrowserOriginKeeper>()

    private class MemoryOriginStore : JournalBrowserOriginStore {
        @Volatile var record: JournalBrowserOriginRecord? = null
        override fun read() = record
        override fun write(record: JournalBrowserOriginRecord) { this.record = record }
        override fun clear() { record = null }
    }

    /** One app process: its own keeper over the phone's one store. */
    private fun process(store: JournalBrowserOriginStore) = JournalBrowserOriginKeeper(store).also { keepers.add(it) }

    /** Lets every port these processes hold go. */
    @AfterTest
    fun endProcesses() {
        keepers.forEach { it.clear() }
    }

    /** Opens the journal once and closes the sheet. */
    private fun openAndClose(
        keeper: JournalBrowserOriginKeeper,
        pairing: PairingGeneration = pairingA,
        diag: (DiagEvent) -> Unit = {},
    ): String {
        val s = JournalBrowserSession(
            pairing = { pairing },
            accessStillCurrent = { true },
            upstreamFactory = { error("no upstream in these tests") },
            diag = diag,
            originKeeper = keeper,
        )
        val url = s.start().url
        s.stop()
        return url
    }

    /** Ends a process's hold on its port the way process death does: the stored record stays. */
    private fun die(keeper: JournalBrowserOriginKeeper, store: MemoryOriginStore) {
        val record = store.record
        keeper.clear()
        store.record = record
    }

    @Test
    fun theOriginIsKeptFromOneOpenToTheNext() {
        val keeper = process(MemoryOriginStore())
        val first = openAndClose(keeper)
        assertEquals(first, openAndClose(keeper))
        assertEquals(first, openAndClose(keeper))
        assertTrue(URI(first).host.matches(Regex("^[0-9a-f]{32}\\.localhost$")))
    }

    @Test
    fun theOriginIsKeptAcrossARelaunch() {
        val store = MemoryOriginStore()
        val first = process(store)
        val before = openAndClose(first)
        die(first, store)
        val diag = mutableListOf<DiagEvent>()
        assertEquals(before, openAndClose(process(store), diag = { diag.add(it) }))
        assertTrue(diag.contains(DiagEvent.JournalBrowser("origin", "kept")))
    }

    @Test
    fun aTakenPortStartsAFreshOrigin() {
        val store = MemoryOriginStore()
        val first = process(store)
        val kept = URI(openAndClose(first))
        die(first, store)

        ServerSocket().use { other ->
            other.reuseAddress = true
            other.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), kept.port))

            val diag = mutableListOf<DiagEvent>()
            val keeper = process(store)
            val fresh = URI(openAndClose(keeper, diag = { diag.add(it) }))
            assertNotEquals(kept.port, fresh.port)
            assertNotEquals(kept.host, fresh.host, "a fresh port takes a fresh token too")
            assertEquals(fresh.port, store.record?.port)
            assertTrue(diag.contains(DiagEvent.JournalBrowser("origin", "fallback")))
            // The fresh origin is the one kept from now on.
            assertEquals(fresh.toString(), openAndClose(keeper))
        }
    }

    @Test
    fun anotherPairingNeverUsesTheOrigin() {
        val store = MemoryOriginStore()
        val keeper = process(store)
        val underA = URI(openAndClose(keeper, pairingA))
        val underB = URI(openAndClose(keeper, pairingB))
        assertNotEquals(underA.host, underB.host)
        assertEquals(pairingB, store.record?.pairing)
        // Returning to the first pairing does not bring its origin back.
        assertNotEquals(underA.host, URI(openAndClose(keeper, pairingA)).host)
    }

    @Test
    fun unpairingDeletesTheOrigin() {
        val store = MemoryOriginStore()
        val keeper = process(store)
        val before = URI(openAndClose(keeper))
        keeper.clear()
        assertNull(store.record)
        assertNotEquals(before.host, URI(openAndClose(keeper)).host)
    }

    @Test
    fun withNoKeeperEverySessionHasItsOwnOrigin() {
        val s = JournalBrowserSession(
            pairing = { pairingA },
            accessStillCurrent = { true },
            upstreamFactory = { error("no upstream in these tests") },
            diag = {},
        )
        val first = s.start().url
        s.stop()
        assertNotEquals(first, s.start().url)
        s.stop()
    }

    @Test
    fun theFileStoreKeepsOneRecordAndReadsNothingFromAnUnreadableFile() {
        val dir = Files.createTempDirectory("origin-store").toFile()
        try {
            val file = File(dir, "journal_browser_origin.json")
            val store = FileJournalBrowserOriginStore(file)
            assertNull(store.read())
            val record = JournalBrowserOriginRecord(pairingA, "0123456789abcdef0123456789abcdef", 41234)
            store.write(record)
            assertEquals(record, FileJournalBrowserOriginStore(file).read())
            assertEquals("JournalBrowserOriginRecord(REDACTED)", record.toString())

            file.writeText("{not json")
            assertNull(store.read())

            store.write(record)
            store.clear()
            assertNull(store.read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
