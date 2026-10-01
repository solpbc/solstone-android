// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingLease
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.SubscriptionHandle
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
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

    // The pairing authority reduced to the one fact these tests vary: is a pairing committed.
    private class FakePairingPublisher(var home: PairedHome?) : PairingPublisher {
        private var seq = 0L

        override fun currentSnapshot(): PairingGraphSnapshot {
            seq += 1
            val committed = home ?: return PairingGraphSnapshot.Absent(seq)
            return PairingGraphSnapshot.Committed(
                sequenceNumber = seq,
                revisions = GraphRevisions(1L, 1L, 1L),
                home = committed,
                hasDirectEndpoint = false,
                directAssociated = false,
                relayLiveEligible = false,
            )
        }

        override fun subscribe(observer: (PairingGraphSnapshot) -> Unit): SubscriptionHandle =
            SubscriptionHandle {}

        override fun <T> withMutationBoundary(block: () -> T): T = block()
        override fun acquireDirectLease(): PairingLease.Direct? = null
        override fun acquireRelayLease(): PairingLease.Relay? = null
        override fun validateLease(lease: PairingLease): Boolean = true

        override fun installOrReplace(
            home: PairedHome,
            credential: ClientCredential,
            directEndpoint: app.solstone.core.model.DirectEndpoint?,
            isDirectAssociated: Boolean,
        ): GraphMutationResult = GraphMutationResult.Conflict("unused")

        override fun updateRelayAccess(
            expectedPairing: PairingGeneration,
            relayOrigin: String,
            deviceToken: String,
            expiresAt: String?,
        ): GraphMutationResult = GraphMutationResult.Conflict("unused")

        override fun revokeRelayAccess(expectedPairing: PairingGeneration): GraphMutationResult =
            GraphMutationResult.Conflict("unused")

        override fun forget(): GraphMutationResult {
            home = null
            return GraphMutationResult.Cleared(PairingGraphSnapshot.Absent(++seq))
        }

        override fun associateDirectIfProven(
            expectedPairing: PairingGeneration,
            endpoint: app.solstone.core.model.DirectEndpoint,
            proof: () -> Boolean,
        ): Boolean = false
    }

    private fun pairedHome() = PairedHome(
        instanceId = "inst-1",
        homeLabel = "Home",
        relayOrigin = null,
        caChainFingerprint = "sha256:ca1",
        clientCertFingerprint = "sha256:cert1",
        observerHandle = null,
        deviceToken = null,
        expiresAt = null,
        state = IdentityState.PAIRED,
    )

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

        val identified = CountDownLatch(1)
        coordinator.addListener {
            if (it is JournalMarkPresentation.Identified) identified.countDown()
        }

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

        // `save()` is deliberately before `updatePresentation()`. Waiting on the store proves the
        // persistence half only; wait for the presentation notification before observing it.
        assertTrue(identified.await(5, TimeUnit.SECONDS))
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
    fun identityChangeWhileDelayedGetInFlightStaysLoadingAndIgnoresStaleResult() {
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
    fun onIdentityChangedClearsStoreImmediatelyAndSetsLoading() {
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
    fun neverPairedOwnerKeepsTheGenericMark() {
        // Nothing is loading for an owner with no journal: the card must read as the generic
        // "your journal, not set up yet", never as a mark that is loading.
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = FakeMarkStore(),
            executor = executor,
            publisher = FakePairingPublisher(home = null),
        )

        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())
        assertNull(coordinator.currentPresentationGeneration())

        coordinator.onPairingChanged()
        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun pairedOwnerWithAnEmptyStoreIsLoading() {
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = FakeMarkStore(),
            executor = executor,
            publisher = FakePairingPublisher(home = pairedHome()),
        )

        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun forgettingTheJournalReturnsToGenericNotLoading() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor, publisher = publisher)
        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())

        publisher.forget()
        coordinator.onIdentityChanged()

        assertNull(store.load())
        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())

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

    @Test
    fun staleJobFailureDoesNotOverwriteNewerPairingPresentation() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor, publisher = publisher)

        val inRequest = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)

        val client = RoutingFakeClient { _, _ ->
            inRequest.countDown()
            releaseRequest.await(5, TimeUnit.SECONDS)
            throw IOException("failed")
        }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(inRequest.await(5, TimeUnit.SECONDS))

        // Pairing changes to B before request completes
        val homeB = PairedHome(
            instanceId = "inst-2",
            homeLabel = "Home B",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca2",
            clientCertFingerprint = "sha256:cert2",
            observerHandle = null,
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        publisher.home = homeB
        val recordB = JournalMarkRecord("inst-2", null, PairingGeneration("inst-2", "sha256:cert2"))
        store.save(recordB)
        coordinator.onPairingChanged()

        // Release stale throwing request
        releaseRequest.countDown()
        Thread.sleep(150)

        // Presentation for B should remain Generic (from store save), not overwritten to Unavailable
        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())
        assertEquals(PairingGeneration("inst-2", "sha256:cert2"), coordinator.currentPresentationGeneration())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun onMarkRequestedWithNullOpenClientParksUntilBound() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 400L,
            publisher = publisher,
        )

        val pairingA = PairingGeneration("inst-1", "sha256:cert1")
        coordinator.onMarkRequested(
            pairingMatches = { (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing == pairingA },
            openClient = null,
        )

        // Sleep ~100ms. Assert presentation is still not Unavailable (still Loading)
        Thread.sleep(100)
        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())

        // Sleep until past the bound (total > 400ms)
        Thread.sleep(450)
        assertIs<JournalMarkPresentation.Unavailable>(coordinator.currentPresentation())
        assertEquals(pairingA, coordinator.currentPresentationGeneration())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun onMarkRequestedNoLeaseLeavesNewerPairingUnchanged() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 400L,
            publisher = publisher,
        )

        val pairingA = PairingGeneration("inst-1", "sha256:cert1")
        coordinator.onMarkRequested(
            pairingMatches = { (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing == pairingA },
            openClient = null,
        )

        // Move publisher to B with no onPairingChanged and no second bumpGeneration
        val homeB = PairedHome(
            instanceId = "inst-2",
            homeLabel = "Home B",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca2",
            clientCertFingerprint = "sha256:cert2",
            observerHandle = null,
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        publisher.home = homeB
        val recordB = JournalMarkRecord("inst-2", null, PairingGeneration("inst-2", "sha256:cert2"))
        store.save(recordB)

        val presentationAfterMove = coordinator.currentPresentation()
        val genAfterMove = coordinator.currentPresentationGeneration()

        // Wait past the bound
        Thread.sleep(550)

        // Assert both are unchanged
        assertEquals(presentationAfterMove, coordinator.currentPresentation())
        assertEquals(genAfterMove, coordinator.currentPresentationGeneration())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun staleFetchBetweenBumpAndInstallDoesNotWriteUnavailableOnB() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 400L,
            publisher = publisher,
        )

        // Publisher is A. Call onPairingChanged while still A (this is the bump).
        coordinator.onPairingChanged()

        val inRequest = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val client = RoutingFakeClient { _, _ ->
            inRequest.countDown()
            releaseRequest.await(5, TimeUnit.SECONDS)
            throw IOException("failed")
        }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(inRequest.await(5, TimeUnit.SECONDS))

        // Move publisher to B. Do not call onPairingChanged again.
        val homeB = PairedHome(
            instanceId = "inst-2",
            homeLabel = "Home B",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca2",
            clientCertFingerprint = "sha256:cert2",
            observerHandle = null,
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        publisher.home = homeB
        val recordB = JournalMarkRecord("inst-2", null, PairingGeneration("inst-2", "sha256:cert2"))
        store.save(recordB)

        val presentationAfterMove = coordinator.currentPresentation()
        val genAfterMove = coordinator.currentPresentationGeneration()

        // Release client so it fails
        releaseRequest.countDown()
        Thread.sleep(150)

        // Assert both recorded values are unchanged
        assertEquals(presentationAfterMove, coordinator.currentPresentation())
        assertEquals(genAfterMove, coordinator.currentPresentationGeneration())

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun onMarkRequestedWithNewerPairingBCommittedLeavesBUnchanged() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor, publisher = publisher)

        var pairingAIsCurrent = true
        coordinator.onMarkRequested(
            pairingMatches = { pairingAIsCurrent },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), validMarkJson.toByteArray()) } },
        )

        // Before job executes, pairing flips to B
        pairingAIsCurrent = false
        val homeB = PairedHome(
            instanceId = "inst-2",
            homeLabel = "Home B",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca2",
            clientCertFingerprint = "sha256:cert2",
            observerHandle = null,
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        publisher.home = homeB
        val recordB = JournalMarkRecord("inst-2", null, PairingGeneration("inst-2", "sha256:cert2"))
        store.save(recordB)
        coordinator.onPairingChanged()

        Thread.sleep(150)
        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())
        assertEquals(PairingGeneration("inst-2", "sha256:cert2"), coordinator.currentPresentationGeneration())

        coordinator.close()
        executor.shutdown()
    }
}
