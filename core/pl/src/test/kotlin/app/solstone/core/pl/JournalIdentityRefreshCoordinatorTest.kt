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
import java.util.concurrent.CopyOnWriteArrayList
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
        var mutationBoundaryHook: (() -> Unit)? = null
        var mutationBoundaryAfterHook: (() -> Unit)? = null

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

        override fun <T> withMutationBoundary(block: () -> T): T {
            mutationBoundaryHook?.invoke()
            try {
                return block()
            } finally {
                mutationBoundaryAfterHook?.invoke()
            }
        }
        override fun acquireDirectLease(): PairingLease.Direct? = null
        override fun acquireRelayLease(): PairingLease.Relay? = null
        override fun validateLease(lease: PairingLease): Boolean = true

        override fun installOrReplace(
            home: PairedHome,
            credential: ClientCredential,
            directEndpoint: app.solstone.core.model.DirectEndpoint?,
            isDirectAssociated: Boolean,
            provenance: app.solstone.core.identity.PairingProvenance,
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

    @Test
    fun noCommittedPairingReturnsNullAndTicketsStrictlyIncrease() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = null)
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor, publisher = publisher)

        val ticketNoPairing = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), validMarkJson.toByteArray()) } },
        )
        assertNull(ticketNoPairing)

        publisher.home = pairedHome()
        val ticket1 = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), validMarkJson.toByteArray()) } },
        )
        assertNotNull(ticket1)
        assertEquals(1L, ticket1)

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), validMarkJson.toByteArray()) } },
        )

        val ticket2 = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), validMarkJson.toByteArray()) } },
        )
        assertNotNull(ticket2)
        assertEquals(2L, ticket2)

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun replayDoesNotEndWaitWhenBelowTicket() {
        val store = FakeMarkStore()
        store.save(JournalMarkRecord("inst-1", null, PairingGeneration("inst-1", "sha256:cert1")))
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor, publisher = publisher)

        val inTicketed = CountDownLatch(1)
        val releaseTicketed = CountDownLatch(1)
        val ticket = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = {
                RoutingFakeClient { _, _ ->
                    inTicketed.countDown()
                    releaseTicketed.await(5, TimeUnit.SECONDS)
                    HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
                }
            },
        )
        assertNotNull(ticket)
        assertTrue(inTicketed.await(5, TimeUnit.SECONDS))

        val events = CopyOnWriteArrayList<JournalMarkEvent>()
        val replayLatch = CountDownLatch(2)
        val removeListener = coordinator.addMarkListener {
            events.add(it)
            replayLatch.countDown()
        }
        assertTrue(replayLatch.await(5, TimeUnit.SECONDS))

        assertEquals(2, events.size)
        assertIs<JournalMarkEvent.Presented>(events[0])
        val answered = assertIs<JournalMarkEvent.Answered>(events[1])
        assertTrue(answered.through < ticket)

        releaseTicketed.countDown()
        removeListener()
        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun preOpenGiveUpDoesNotEndTicket() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 50L,
            publisher = publisher,
        )

        val firstStarted = CountDownLatch(1)
        val firstBlocker = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondBlocker = CountDownLatch(1)
        val secondSaved = CountDownLatch(1)
        store.onSave = { secondSaved.countDown() }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = {
                RoutingFakeClient { _, _ ->
                    firstStarted.countDown()
                    firstBlocker.await(5, TimeUnit.SECONDS)
                    HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
                }
            },
        )
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

        val events = CopyOnWriteArrayList<JournalMarkEvent>()
        val removeListener = coordinator.addMarkListener { events.add(it) }

        val ticket = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = {
                RoutingFakeClient { _, _ ->
                    secondStarted.countDown()
                    secondBlocker.await(5, TimeUnit.SECONDS)
                    HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
                }
            },
        )
        assertNotNull(ticket)

        // Wait for first usableConnection to hit 50ms bound
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))

        // Ensure before second completes, no Answered >= ticket has arrived
        val answeredPrior = events.filterIsInstance<JournalMarkEvent.Answered>().map { it.through }
        assertTrue(answeredPrior.all { it < ticket })

        firstBlocker.countDown()
        secondBlocker.countDown()
        assertTrue(secondSaved.await(5, TimeUnit.SECONDS))

        // Wait until answered >= ticket arrives
        val finalAnsweredLatch = CountDownLatch(1)
        val removeFollower = coordinator.addMarkListener {
            if (it is JournalMarkEvent.Answered && it.through >= ticket) {
                finalAnsweredLatch.countDown()
            }
        }
        assertTrue(finalAnsweredLatch.await(5, TimeUnit.SECONDS))

        // Verify Presented preceded Answered for the ticketed result
        val identifiedIdx = events.indexOfLast { it is JournalMarkEvent.Presented && it.presentation is JournalMarkPresentation.Identified }
        val answeredIdx = events.indexOfLast { it is JournalMarkEvent.Answered && it.through >= ticket }
        assertTrue(identifiedIdx != -1 && answeredIdx != -1 && identifiedIdx < answeredIdx)

        removeListener()
        removeFollower()
        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun preOpenSuccessDoesNotEndTicket() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 15_000L,
            publisher = publisher,
        )

        val firstStarted = CountDownLatch(1)
        val firstBlocker = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondBlocker = CountDownLatch(1)

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = {
                RoutingFakeClient { _, _ ->
                    firstStarted.countDown()
                    firstBlocker.await(5, TimeUnit.SECONDS)
                    HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
                }
            },
        )
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

        val events = CopyOnWriteArrayList<JournalMarkEvent>()
        val removeListener = coordinator.addMarkListener { events.add(it) }

        val ticket = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = {
                RoutingFakeClient { _, _ ->
                    secondStarted.countDown()
                    secondBlocker.await(5, TimeUnit.SECONDS)
                    HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
                }
            },
        )
        assertNotNull(ticket)

        // Release first task (returns Identified)
        firstBlocker.countDown()
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))

        // Before second finishes, no Answered >= ticket has arrived
        val answeredPrior = events.filterIsInstance<JournalMarkEvent.Answered>().map { it.through }
        assertTrue(answeredPrior.all { it < ticket })

        secondBlocker.countDown()

        val finalAnsweredLatch = CountDownLatch(1)
        val removeFollower = coordinator.addMarkListener {
            if (it is JournalMarkEvent.Answered && it.through >= ticket) {
                finalAnsweredLatch.countDown()
            }
        }
        assertTrue(finalAnsweredLatch.await(5, TimeUnit.SECONDS))

        removeListener()
        removeFollower()
        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun freshUnavailableEndsTicketTwiceViaThrowAndParkedTimeout() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 50L,
            publisher = publisher,
        )

        // 1. Client throws
        val events1 = CopyOnWriteArrayList<JournalMarkEvent>()
        val answered1 = CountDownLatch(1)
        val remove1 = coordinator.addMarkListener {
            events1.add(it)
            if (it is JournalMarkEvent.Answered && it.through >= 1L) answered1.countDown()
        }

        val ticket1 = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = { RoutingFakeClient { _, _ -> throw IOException("door closed") } },
        )
        assertNotNull(ticket1)
        assertTrue(answered1.await(5, TimeUnit.SECONDS))

        val unavailIdx1 = events1.indexOfLast { it is JournalMarkEvent.Presented && it.presentation is JournalMarkPresentation.Unavailable }
        val ansIdx1 = events1.indexOfLast { it is JournalMarkEvent.Answered && it.through >= ticket1 }
        assertTrue(unavailIdx1 != -1 && ansIdx1 != -1 && unavailIdx1 < ansIdx1)
        remove1()

        // 2. Parked no-client request hits timeout
        val events2 = CopyOnWriteArrayList<JournalMarkEvent>()
        val answered2 = CountDownLatch(1)
        val remove2 = coordinator.addMarkListener {
            events2.add(it)
            if (it is JournalMarkEvent.Answered && it.through >= 2L) answered2.countDown()
        }

        val ticket2 = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = null,
        )
        assertNotNull(ticket2)
        assertTrue(answered2.await(5, TimeUnit.SECONDS))

        val unavailIdx2 = events2.indexOfLast { it is JournalMarkEvent.Presented && it.presentation is JournalMarkPresentation.Unavailable }
        val ansIdx2 = events2.indexOfLast { it is JournalMarkEvent.Answered && it.through >= ticket2 }
        assertTrue(unavailIdx2 != -1 && ansIdx2 != -1 && unavailIdx2 < ansIdx2)
        remove2()

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun suppressedChecksStillAnswerWithNothingForShellSentinel() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor, publisher = publisher)

        val shellPresList = CopyOnWriteArrayList<JournalMarkPresentation>()
        val shellGenList = CopyOnWriteArrayList<Pair<PairingGeneration?, JournalMarkPresentation>>()
        val markEvents = CopyOnWriteArrayList<JournalMarkEvent>()

        val presLatch = CountDownLatch(2)
        val genLatch = CountDownLatch(2)

        val removeAddListener = coordinator.addListener {
            shellPresList.add(it)
            presLatch.countDown()
        }
        val removeAddGen = coordinator.addGenerationListener { gen, pres ->
            shellGenList.add(gen to pres)
            genLatch.countDown()
        }
        val removeMark = coordinator.addMarkListener { markEvents.add(it) }

        // (a) pairingMatches() returns false
        val answeredA = CountDownLatch(1)
        val removeWatcherA = coordinator.addMarkListener {
            if (it is JournalMarkEvent.Answered && it.through >= 1L) answeredA.countDown()
        }
        val ticketA = coordinator.onMarkRequested(
            pairingMatches = { false },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), validMarkJson.toByteArray()) } },
        )
        assertNotNull(ticketA)
        assertTrue(answeredA.await(5, TimeUnit.SECONDS))
        removeWatcherA()

        // (b) pairingMatches() throws on every call from second onward
        var callCount = 0
        val answeredB = CountDownLatch(1)
        val removeWatcherB = coordinator.addMarkListener {
            if (it is JournalMarkEvent.Answered && it.through >= 2L) answeredB.countDown()
        }
        val ticketB = coordinator.onMarkRequested(
            pairingMatches = {
                callCount++
                if (callCount > 1) throw RuntimeException("pairingMatches throw")
                true
            },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), validMarkJson.toByteArray()) } },
        )
        assertNotNull(ticketB)
        assertTrue(answeredB.await(5, TimeUnit.SECONDS))
        removeWatcherB()

        // Verify that after tickets A and B, Answered arrived with NO Presented between
        val presentedEventsAfterReplay = markEvents.drop(1).filterIsInstance<JournalMarkEvent.Presented>()
        assertEquals(0, presentedEventsAfterReplay.size)

        // Then onPairingChanged is the sentinel
        coordinator.onPairingChanged()
        assertTrue(presLatch.await(5, TimeUnit.SECONDS))
        assertTrue(genLatch.await(5, TimeUnit.SECONDS))

        // Pre-registered addGenerationListener and addListener each saw exactly [replay, sentinel]
        assertEquals(2, shellPresList.size)
        assertEquals(2, shellGenList.size)

        removeAddListener()
        removeAddGen()
        removeMark()
        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun resetsAnswerWhatTheyDropAndDoNotAnswerSubsequentTicketEarly() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor, publisher = publisher)

        val firstStarted = CountDownLatch(1)
        val firstBlocker = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondBlocker = CountDownLatch(1)

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = {
                RoutingFakeClient { _, _ ->
                    firstStarted.countDown()
                    firstBlocker.await(5, TimeUnit.SECONDS)
                    HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
                }
            },
        )
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

        val events = CopyOnWriteArrayList<JournalMarkEvent>()
        val ticket1AnsweredLatch = CountDownLatch(1)
        val removeListener = coordinator.addMarkListener {
            events.add(it)
            if (it is JournalMarkEvent.Answered && it.through >= 1L) ticket1AnsweredLatch.countDown()
        }

        val ticket1 = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), validMarkJson.toByteArray()) } },
        )
        assertNotNull(ticket1)

        // onPairingChanged bumps generation, answers dropped ticket1
        coordinator.onPairingChanged()

        // Release first in-flight task
        firstBlocker.countDown()

        // Wait for ticket1 to be answered
        assertTrue(ticket1AnsweredLatch.await(5, TimeUnit.SECONDS))
        val answered1 = events.filterIsInstance<JournalMarkEvent.Answered>().map { it.through }
        assertTrue(answered1.any { it >= ticket1 })

        // Take a new ticket whose client is held on a second latch
        val ticket2 = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = {
                RoutingFakeClient { _, _ ->
                    secondStarted.countDown()
                    secondBlocker.await(5, TimeUnit.SECONDS)
                    HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
                }
            },
        )
        assertNotNull(ticket2)
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))

        // No Answered at or above ticket2 until second latch is released
        val answeredPrior2 = events.filterIsInstance<JournalMarkEvent.Answered>().map { it.through }
        assertTrue(answeredPrior2.all { it < ticket2 })

        secondBlocker.countDown()

        val finalAnsweredLatch = CountDownLatch(1)
        val removeFollower = coordinator.addMarkListener {
            if (it is JournalMarkEvent.Answered && it.through >= ticket2) {
                finalAnsweredLatch.countDown()
            }
        }
        assertTrue(finalAnsweredLatch.await(5, TimeUnit.SECONDS))

        removeListener()
        removeFollower()
        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun stalePublishRejectedByGenerationCheck() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 50L,
            publisher = publisher,
        )

        val firstBoundaryEntered = CountDownLatch(1)
        val firstBoundaryRelease = CountDownLatch(1)
        var firstBoundaryIntercepted = false
        var heldThreadId: Long? = null
        val staleBoundaryFinished = CountDownLatch(1)

        publisher.mutationBoundaryHook = {
            if (!firstBoundaryIntercepted) {
                firstBoundaryIntercepted = true
                heldThreadId = Thread.currentThread().id
                firstBoundaryEntered.countDown()
                while (firstBoundaryRelease.count > 0) {
                    try {
                        firstBoundaryRelease.await(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        publisher.mutationBoundaryAfterHook = {
            if (heldThreadId != null && Thread.currentThread().id == heldThreadId) {
                staleBoundaryFinished.countDown()
            }
        }

        val uncommittedJson = """{"committed":false,"instance_id":null,"mark":null}"""
        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), uncommittedJson.toByteArray()) } },
        )

        // Wait until task 1 enters boundary
        assertTrue(firstBoundaryEntered.await(5, TimeUnit.SECONDS))

        val events = CopyOnWriteArrayList<JournalMarkEvent>()
        val identifiedPublished = CountDownLatch(1)
        val removeListener = coordinator.addMarkListener {
            events.add(it)
            if (it is JournalMarkEvent.Presented && it.presentation is JournalMarkPresentation.Identified) {
                identifiedPublished.countDown()
            }
        }

        // Submit ticketed check which returns Identified
        val ticket = coordinator.onMarkRequested(
            pairingMatches = { true },
            openClient = { RoutingFakeClient { _, _ -> HttpResponse(200, emptyMap(), validMarkJson.toByteArray()) } },
        )
        assertNotNull(ticket)

        // Wait until ticketed check has published Identified (held past the 50ms bound)
        assertTrue(identifiedPublished.await(5, TimeUnit.SECONDS))

        // Release stale boundary
        firstBoundaryRelease.countDown()
        assertTrue(staleBoundaryFinished.await(5, TimeUnit.SECONDS))

        // Presentation, last Presented, and store must be Identified, never Generic
        assertIs<JournalMarkPresentation.Identified>(coordinator.currentPresentation())
        val lastPresented = events.filterIsInstance<JournalMarkEvent.Presented>().lastOrNull()
        assertNotNull(lastPresented)
        assertIs<JournalMarkPresentation.Identified>(lastPresented.presentation)
        assertNotNull(store.load()?.mark)

        removeListener()
        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun staleGiveUpRejectedByGenerationCheck() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home = pairedHome())
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 50L,
            publisher = publisher,
        )

        val firstBoundaryEntered = CountDownLatch(1)
        val firstBoundaryRelease = CountDownLatch(1)
        var firstBoundaryIntercepted = false
        val giveUpBoundaryFinished = CountDownLatch(1)

        publisher.mutationBoundaryHook = {
            if (!firstBoundaryIntercepted) {
                firstBoundaryIntercepted = true
                firstBoundaryEntered.countDown()
                while (firstBoundaryRelease.count > 0) {
                    try {
                        firstBoundaryRelease.await(5, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        publisher.mutationBoundaryAfterHook = {
            if (firstBoundaryIntercepted && giveUpBoundaryFinished.count > 0) {
                giveUpBoundaryFinished.countDown()
            }
        }

        // Task stays inside HTTP call swallowing interruption until released, so it does not enter withMutationBoundary itself
        val taskRelease = CountDownLatch(1)
        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = {
                RoutingFakeClient { _, _ ->
                    while (taskRelease.count > 0) {
                        try {
                            taskRelease.await(5, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {
                        }
                    }
                    HttpResponse(200, emptyMap(), validMarkJson.toByteArray())
                }
            },
        )

        // Wait for give-up to enter boundary
        assertTrue(firstBoundaryEntered.await(5, TimeUnit.SECONDS))

        // Reset via onPairingChanged
        coordinator.onPairingChanged()

        val events = CopyOnWriteArrayList<JournalMarkEvent>()
        val replayLatch = CountDownLatch(1)
        val removeListener = coordinator.addMarkListener {
            events.add(it)
            if (it is JournalMarkEvent.Presented) replayLatch.countDown()
        }
        assertTrue(replayLatch.await(5, TimeUnit.SECONDS))

        // Release boundary, then release task
        firstBoundaryRelease.countDown()
        assertTrue(giveUpBoundaryFinished.await(5, TimeUnit.SECONDS))
        taskRelease.countDown()

        val lastPresented = events.filterIsInstance<JournalMarkEvent.Presented>().lastOrNull()
        assertNotNull(lastPresented)
        // Initial presentation for paired home with empty store is Loading
        assertIs<JournalMarkPresentation.Loading>(lastPresented.presentation)
        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())

        removeListener()
        coordinator.close()
        executor.shutdown()
    }
}
