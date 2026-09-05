// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JournalVersionRefreshCoordinatorTest {
    private class FakeStore : JournalVersionStore {
        var savedRecord: JournalVersionRecord? = null
        var onSave: (() -> Unit)? = null

        override fun load(): JournalVersionRecord? = savedRecord

        override fun save(record: JournalVersionRecord) {
            savedRecord = record
            onSave?.invoke()
        }

        override fun clear() {
            savedRecord = null
        }
    }

    private class FakeClient(private val version: String?) : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): HttpResponse {
            return if (version != null) {
                HttpResponse(200, emptyMap(), """{"version":{"current":"$version"}}""".toByteArray())
            } else {
                HttpResponse(500, emptyMap(), ByteArray(0))
            }
        }
    }

    @Test
    fun successfulFetchPersistsAndMarksCurrent() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            FakeClient("1.2.3")
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals(JournalVersionFreshness.CURRENT, reading.freshness)
        assertEquals(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3"), store.savedRecord)
    }

    @Test
    fun lateCompletionFromEarlierGenerationDoesNotClobberNewerGeneration() {
        val store = FakeStore()
        val secondSaved = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val firstLatch = CountDownLatch(1)
        val firstClientStarted = CountDownLatch(1)

        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            firstClientStarted.countDown()
            firstLatch.await(3, TimeUnit.SECONDS)
            FakeClient("1.0.0-stale")
        }

        firstClientStarted.await(3, TimeUnit.SECONDS)

        store.onSave = { secondSaved.countDown() }
        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            FakeClient("2.0.0-fresh")
        }

        secondSaved.await(3, TimeUnit.SECONDS)

        // Release first after second has completed
        firstLatch.countDown()
        Thread.sleep(100)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("2.0.0-fresh", reading.version)
        assertEquals(JournalVersionFreshness.CURRENT, reading.freshness)
        assertEquals(JournalVersionRecord("jid-1", "sha256:ca1", "2.0.0-fresh"), store.savedRecord)
    }

    @Test
    fun connectionLostDemotesFreshnessWithoutModifyingStore() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            FakeClient("1.2.3")
        }

        saved.await(3, TimeUnit.SECONDS)

        assertEquals(JournalVersionFreshness.CURRENT, coordinator.currentReading("jid-1", "sha256:ca1").freshness)

        coordinator.onConnectionLost()

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals(JournalVersionFreshness.LAST_KNOWN, reading.freshness)
        assertEquals(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3"), store.savedRecord)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun identityMismatchReadsAsNeverObserved() {
        val store = FakeStore()
        store.save(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3"))
        val coordinator = JournalVersionRefreshCoordinator(store)

        val mismatchJid = coordinator.currentReading("jid-other", "sha256:ca1")
        assertNull(mismatchJid.version)
        assertEquals(JournalVersionFreshness.NEVER_OBSERVED, mismatchJid.freshness)

        val mismatchCa = coordinator.currentReading("jid-1", "sha256:other")
        assertNull(mismatchCa.version)
        assertEquals(JournalVersionFreshness.NEVER_OBSERVED, mismatchCa.freshness)
    }

    @Test
    fun completionArrivingAfterConnectionLostDoesNotMarkCurrent() {
        val store = FakeStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val fetchBlocker = CountDownLatch(1)
        val clientStarted = CountDownLatch(1)

        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            clientStarted.countDown()
            fetchBlocker.await(3, TimeUnit.SECONDS)
            FakeClient("1.2.3")
        }

        clientStarted.await(3, TimeUnit.SECONDS)
        coordinator.onConnectionLost()
        fetchBlocker.countDown()
        Thread.sleep(100)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals(JournalVersionFreshness.NEVER_OBSERVED, reading.freshness)
        assertNull(store.savedRecord)
    }

    @Test
    fun inFlightFetchDroppedWhenIdentityChanged() {
        val store = FakeStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val fetchBlocker = CountDownLatch(1)
        val clientStarted = CountDownLatch(1)

        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            clientStarted.countDown()
            fetchBlocker.await(3, TimeUnit.SECONDS)
            FakeClient("1.2.3")
        }

        clientStarted.await(3, TimeUnit.SECONDS)
        coordinator.onIdentityChanged()
        fetchBlocker.countDown()
        Thread.sleep(100)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals(JournalVersionFreshness.NEVER_OBSERVED, reading.freshness)
        assertNull(store.savedRecord)
    }

    @Test
    fun missingStoreRecordReadsAsNeverObserved() {
        val store = FakeStore()
        val coordinator = JournalVersionRefreshCoordinator(store)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertNull(reading.version)
        assertEquals(JournalVersionFreshness.NEVER_OBSERVED, reading.freshness)
    }
}
