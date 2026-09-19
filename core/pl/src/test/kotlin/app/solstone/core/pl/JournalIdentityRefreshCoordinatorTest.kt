// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalIdentityRefreshCoordinatorTest {

    private class FakeMarkStore : JournalMarkStore {
        var savedRecord: JournalMarkRecord? = null
        var onSave: (() -> Unit)? = null

        override fun load(): JournalMarkRecord? = savedRecord

        override fun save(record: JournalMarkRecord) {
            savedRecord = record
            onSave?.invoke()
        }

        override fun clear() {
            savedRecord = null
        }
    }

    private class RoutingFakeClient(
        private val handler: (method: String, path: String) -> HttpResponse,
    ) : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse = handler(method, path)
    }

    private val validMarkJson = """
        {
          "committed": true,
          "instance_id": "inst-1",
          "mark": {
            "icon1": { "name": "piano", "svg": "<path d=\"M0 0h24v24H0z\"/>", "color": { "name": "blue", "hex": "#3b82f6" }, "rot": 45 },
            "icon2": { "name": "key", "svg": "<path d=\"M0 0h24v24H0z\"/>", "color": { "name": "purple", "hex": "#a855f7" }, "rot": 0 },
            "words": ["liquefy", "smock"]
          }
        }
    """.trimIndent()

    @Test
    fun successfulIdentityFetchSavesMarkAndNotifiesIdentified() {
        val store = FakeMarkStore()
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }

        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor)

        val presentations = mutableListOf<JournalMarkPresentation>()
        coordinator.addListener { presentations.add(it) }

        val client = RoutingFakeClient { method, path ->
            assertEquals("GET", method)
            assertEquals("/app/network/api/identity", path)
            HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
        }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(saved.await(5, TimeUnit.SECONDS))
        val record = assertNotNull(store.savedRecord)
        assertEquals("inst-1", record.instanceId)
        val mark = assertNotNull(record.mark)
        assertEquals("piano", mark.icon1.name)
        assertEquals("liquefy", mark.words[0])

        assertIs<JournalMarkPresentation.Identified>(coordinator.currentPresentation())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun slowGetDoesNotBlockCallerAndSavesWhenComplete() {
        val store = FakeMarkStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor)

        val inRequest = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val getCount = AtomicInteger(0)
        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }

        val client = RoutingFakeClient { _, path ->
            assertEquals("/app/network/api/identity", path)
            getCount.incrementAndGet()
            inRequest.countDown()
            releaseRequest.await(5, TimeUnit.SECONDS)
            HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
        }

        // onUsableConnection returns before request finishes
        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(inRequest.await(5, TimeUnit.SECONDS))
        // Still pending
        assertNull(store.savedRecord)
        releaseRequest.countDown()

        assertTrue(saved.await(5, TimeUnit.SECONDS))
        assertEquals(1, getCount.get())
        assertNotNull(store.savedRecord?.mark)

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun http500OrThrownIOExceptionSetsPresentationUnavailableWithoutPersisting() {
        val store = FakeMarkStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor)

        val notifiedUnavailable = CountDownLatch(1)
        coordinator.addListener {
            if (it is JournalMarkPresentation.Unavailable) {
                notifiedUnavailable.countDown()
            }
        }

        val client = RoutingFakeClient { _, _ ->
            throw IOException("connection reset")
        }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(notifiedUnavailable.await(5, TimeUnit.SECONDS))
        assertNull(store.savedRecord)
        assertIs<JournalMarkPresentation.Unavailable>(coordinator.currentPresentation())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun uncommittedIdentitySetsPresentationGeneric() {
        val store = FakeMarkStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor)

        val saved = CountDownLatch(1)
        store.onSave = { saved.countDown() }

        val client = RoutingFakeClient { _, _ ->
            HttpResponse(200, emptyMap(), """{"committed":false,"instance_id":null,"mark":null}""".toByteArray())
        }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(saved.await(5, TimeUnit.SECONDS))
        val record = assertNotNull(store.savedRecord)
        assertNull(record.mark)
        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun identityChangeWhileDelayedGetInFlightStaysGenericAndIgnoresStaleResult() {
        val store = FakeMarkStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor)

        val inRequest = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)

        val client = RoutingFakeClient { _, _ ->
            inRequest.countDown()
            releaseRequest.await(5, TimeUnit.SECONDS)
            HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
        }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(inRequest.await(5, TimeUnit.SECONDS))

        // Identity changes while GET in flight
        coordinator.onIdentityChanged()
        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())
        assertNull(store.load())

        // Release stale response
        releaseRequest.countDown()
        Thread.sleep(100)

        // Stale GET must not save mark or change presentation
        assertNull(store.load())
        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun onIdentityChangedClearsStoreImmediatelyAndSetsGeneric() {
        val store = FakeMarkStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor)

        val initialMark = JournalMark(
            icon1 = JournalMarkIcon("piano", "<path d=\"M0 0\"/>", "blue", "#3b82f6", 45),
            icon2 = JournalMarkIcon("key", "<path d=\"M0 0\"/>", "purple", "#a855f7", 0),
            words = listOf("liquefy", "smock"),
        )
        store.save(JournalMarkRecord("inst-1", initialMark))
        assertEquals("inst-1", store.load()?.instanceId)

        coordinator.onIdentityChanged()
        assertNull(store.load())
        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun blockingListenerCannotStarveOrBlockOtherListeners() {
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(FakeMarkStore(), executor)
        val blockingEntered = CountDownLatch(1)
        val releaseBlocking = CountDownLatch(1)
        val blockingCalls = AtomicInteger(0)
        val removeBlocking = coordinator.addListener {
            blockingCalls.incrementAndGet()
            blockingEntered.countDown()
            releaseBlocking.await(5, TimeUnit.SECONDS)
        }
        assertTrue(blockingEntered.await(5, TimeUnit.SECONDS))

        val independentDeliveries = CountDownLatch(2)
        val removeIndependent = coordinator.addListener { independentDeliveries.countDown() }
        coordinator.onIdentityChanged()
        assertTrue(independentDeliveries.await(5, TimeUnit.SECONDS))

        removeBlocking()
        coordinator.onIdentityChanged()
        releaseBlocking.countDown()
        Thread.sleep(100)
        assertEquals(1, blockingCalls.get())

        removeIndependent()
        coordinator.close()
        executor.shutdown()
    }
}
