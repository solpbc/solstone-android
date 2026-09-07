// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    private class RoutingFakeClient(
        private val handler: (method: String, path: String, body: ByteArray?) -> HttpResponse,
    ) : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse = handler(method, path, body)
    }

    @Test
    fun successfulClientsSelfGetAndPutPersistsNameAndVersion() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val localDesc = ClientReportedDescription(name = "Pixel 8", platform = "android", appId = "app.solstone.phone")
        val getJson = """
        {
            "protocol_version": 1,
            "revision": 1,
            "journal": {"name": "Jer's Journal", "version": "1.2.3"},
            "reported": {"name": "Old Name"}
        }
        """.trimIndent()
        val putJson = """
        {
            "protocol_version": 1,
            "revision": 2,
            "reported": {"name": "Pixel 8"}
        }
        """.trimIndent()

        val putCalled = AtomicInteger(0)

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1", localDescriptionProvider = { localDesc }) {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> HttpResponse(200, emptyMap(), getJson.toByteArray())
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        putCalled.incrementAndGet()
                        HttpResponse(200, emptyMap(), putJson.toByteArray())
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals("Jer's Journal", reading.name)
        assertEquals(JournalVersionFreshness.CURRENT, reading.freshness)
        assertEquals(1, putCalled.get())
        assertEquals(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "Jer's Journal"), store.savedRecord)
    }

    @Test
    fun skipsPutWhenReportedMatchesLocalSnapshot() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val localDesc = ClientReportedDescription(name = "Pixel 8", platform = "android")
        val getJson = """
        {
            "protocol_version": 1,
            "revision": 1,
            "journal": {"name": "Jer's Journal", "version": "1.2.3"},
            "reported": {"name": "Pixel 8", "platform": "android"}
        }
        """.trimIndent()

        val putCalled = AtomicInteger(0)

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1", localDescriptionProvider = { localDesc }) {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> HttpResponse(200, emptyMap(), getJson.toByteArray())
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        putCalled.incrementAndGet()
                        HttpResponse(200, emptyMap(), ByteArray(0))
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(0, putCalled.get())
        assertEquals("Jer's Journal", coordinator.currentReading("jid-1", "sha256:ca1").name)
    }

    @Test
    fun putConflictRetriesGetAndResamplesSnapshotOnce() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val getCount = AtomicInteger(0)
        val putCount = AtomicInteger(0)
        val descCount = AtomicInteger(0)

        val descProvider = {
            val count = descCount.incrementAndGet()
            ClientReportedDescription(name = "Name-$count")
        }

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1", localDescriptionProvider = descProvider) {
            RoutingFakeClient { method, path, _ ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> {
                        getCount.incrementAndGet()
                        val json = """{"protocol_version":1,"revision":1,"journal":{"name":"J","version":"1.0"},"reported":{"name":"Other"}}"""
                        HttpResponse(200, emptyMap(), json.toByteArray())
                    }
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        val call = putCount.incrementAndGet()
                        if (call == 1) {
                            HttpResponse(409, emptyMap(), ByteArray(0))
                        } else {
                            HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":2,"reported":{"name":"Name-2"}}""".toByteArray())
                        }
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        assertEquals(2, getCount.get())
        assertEquals(2, putCount.get())
        assertEquals(2, descCount.get())
    }

    @Test
    fun clientsSelf404FallsBackToSystemStatus() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val statusJson = """{"version":{"current":"0.9.5"}}"""

        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            RoutingFakeClient { _, path, _ ->
                when {
                    path == "/app/network/api/clients/self" -> HttpResponse(404, emptyMap(), ByteArray(0))
                    path == "/api/system/status" -> HttpResponse(200, emptyMap(), statusJson.toByteArray())
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("0.9.5", reading.version)
        assertNull(reading.name)
        assertEquals(JournalVersionFreshness.CURRENT, reading.freshness)
    }

    @Test
    fun connectionLostDemotesFreshnessWithoutModifyingStore() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            RoutingFakeClient { _, _, _ ->
                HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":1,"journal":{"name":"J","version":"1.2.3"}}""".toByteArray())
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        assertEquals(JournalVersionFreshness.CURRENT, coordinator.currentReading("jid-1", "sha256:ca1").freshness)

        coordinator.onConnectionLost()

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals("J", reading.name)
        assertEquals(JournalVersionFreshness.LAST_KNOWN, reading.freshness)
        assertEquals(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "J"), store.savedRecord)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun identityMismatchReadsAsNeverObserved() {
        val store = FakeStore()
        store.save(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "J"))
        val coordinator = JournalVersionRefreshCoordinator(store)

        val mismatchJid = coordinator.currentReading("jid-other", "sha256:ca1")
        assertNull(mismatchJid.version)
        assertEquals(JournalVersionFreshness.NEVER_OBSERVED, mismatchJid.freshness)

        val mismatchCa = coordinator.currentReading("jid-1", "sha256:other")
        assertNull(mismatchCa.version)
        assertEquals(JournalVersionFreshness.NEVER_OBSERVED, mismatchCa.freshness)
    }

    @Test
    fun corruptGetRetainsLastKnown() {
        val store = FakeStore()
        store.save(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "Initial Journal"))
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val latch = CountDownLatch(1)
        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            latch.countDown()
            RoutingFakeClient { _, _, _ ->
                HttpResponse(200, emptyMap(), "not-valid-json".toByteArray())
            }
        }

        latch.await(3, TimeUnit.SECONDS)
        Thread.sleep(200)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals("Initial Journal", reading.name)
        assertEquals(JournalVersionFreshness.LAST_KNOWN, reading.freshness)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun timeoutOrErrorRetainsLastKnown() {
        val store = FakeStore()
        store.save(JournalVersionRecord("jid-1", "sha256:ca1", "1.2.3", "Initial Journal"))
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val latch = CountDownLatch(1)
        coordinator.onUsableConnection("jid-1", "sha256:ca1") {
            latch.countDown()
            RoutingFakeClient { _, _, _ ->
                throw java.io.IOException("network reset")
            }
        }

        latch.await(3, TimeUnit.SECONDS)
        Thread.sleep(200)

        val reading = coordinator.currentReading("jid-1", "sha256:ca1")
        assertEquals("1.2.3", reading.version)
        assertEquals("Initial Journal", reading.name)
        assertEquals(JournalVersionFreshness.LAST_KNOWN, reading.freshness)

        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    @Test
    fun ownerLabelNotSentOnPut() {
        val store = FakeStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalVersionRefreshCoordinator(store, executor)

        val localDesc = ClientReportedDescription(name = "Pixel 8", platform = "android")
        val getJson = """
        {
            "protocol_version": 1,
            "revision": 1,
            "journal": {"name": "Jer's Journal", "version": "1.2.3"},
            "reported": {"name": "Old"}
        }
        """.trimIndent()

        var capturedPutBody: String? = null

        coordinator.onUsableConnection("jid-1", "sha256:ca1", "sha256:cert1", localDescriptionProvider = { localDesc }) {
            RoutingFakeClient { method, path, body ->
                when {
                    method == "GET" && path == "/app/network/api/clients/self" -> HttpResponse(200, emptyMap(), getJson.toByteArray())
                    method == "PUT" && path == "/app/network/api/clients/self" -> {
                        capturedPutBody = body?.toString(Charsets.UTF_8)
                        HttpResponse(200, emptyMap(), """{"protocol_version":1,"revision":2,"reported":{"name":"Pixel 8"}}""".toByteArray())
                    }
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }

        saved.await(3, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)

        val body = capturedPutBody
        org.junit.Assert.assertNotNull(body)
        org.junit.Assert.assertFalse(body!!.contains("owner_label"))
        org.junit.Assert.assertTrue(body.contains("reported"))
    }
}
