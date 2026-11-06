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
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.identity.SubscriptionHandle
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalIdentityRefreshCoordinatorTest {

    private class FakeMarkStore : JournalMarkStore {
        var savedRecord: JournalMarkRecord? = null
        var saves = 0
        var failSave = false
        var unreadable = false

        override fun load(): JournalMarkRecord? = savedRecord

        override fun inspect(): StoreInspectResult<JournalMarkRecord> = when {
            unreadable -> StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "corrupt")
            else -> savedRecord?.let { StoreInspectResult.Ready(it) } ?: StoreInspectResult.Missing
        }

        override fun save(record: JournalMarkRecord) {
            if (failSave) throw IOException("disk")
            savedRecord = record
            unreadable = false
            saves++
        }

        override fun clear() {
            savedRecord = null
        }
    }

    private class FakePairingPublisher(home: PairedHome?) : PairingPublisher {
        @Volatile
        var home: PairedHome? = home
        var uncertain = false
        private var seq = 0L
        private val observers = CopyOnWriteArrayList<(PairingGraphSnapshot) -> Unit>()

        fun publish() {
            val snapshot = currentSnapshot()
            observers.forEach { it(snapshot) }
        }

        override fun currentSnapshot(): PairingGraphSnapshot {
            seq += 1
            if (uncertain) return PairingGraphSnapshot.Uncertain(seq, PersistenceIssue.PERSISTENCE_FAILED)
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

        override fun subscribe(observer: (PairingGraphSnapshot) -> Unit): SubscriptionHandle {
            observers += observer
            return SubscriptionHandle { observers -= observer }
        }

        override fun <T> withMutationBoundary(block: () -> T): T = block()
        override fun acquireDirectLease(): PairingLease.Direct? = null
        override fun acquireRelayLease(): PairingLease.Relay? = null
        override fun validateLease(lease: PairingLease): Boolean = true

        override fun installOrReplace(
            home: PairedHome,
            credential: ClientCredential,
            directEndpoint: app.solstone.core.model.DirectEndpoint?,
            isDirectAssociated: Boolean,
            provenance: app.solstone.core.identity.PairingProvenance,
            directEndpoints: List<app.solstone.core.model.DirectEndpoint>,
        ): GraphMutationResult = GraphMutationResult.Conflict("unused")

        override fun replaceDirectEndpoints(
            expected: PairingGraphSnapshot.Committed,
            endpoints: List<app.solstone.core.model.DirectEndpoint>,
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

    private val pairingA = PairingGeneration("inst-1", "sha256:cert1")
    private val pairingB = PairingGeneration("inst-2", "sha256:cert2")

    private fun home(pairing: PairingGeneration = pairingA) = PairedHome(
        instanceId = pairing.instanceId,
        homeLabel = "Home",
        relayOrigin = null,
        caChainFingerprint = "sha256:ca",
        clientCertFingerprint = pairing.clientCertFingerprint,
        observerHandle = null,
        deviceToken = null,
        expiresAt = null,
        state = IdentityState.PAIRED,
    )

    private fun mark(word: String) = JournalMark(
        icon1 = JournalMarkIcon("piano", "<path d=\"M0 0\"/>", "blue", "#3b82f6", 45),
        icon2 = JournalMarkIcon("key", "<path d=\"M0 0\"/>", "purple", "#a855f7", 0),
        words = listOf(word, "smock"),
    )

    private val executor = Executors.newCachedThreadPool()
    private val coordinators = mutableListOf<JournalIdentityRefreshCoordinator>()

    private fun coordinator(
        store: JournalMarkStore,
        publisher: PairingPublisher,
        boundMillis: Long = 60_000L,
        derive: (PairingGeneration) -> JournalMark,
    ) = JournalIdentityRefreshCoordinator(
        store = store,
        executor = executor,
        boundMillis = boundMillis,
        publisher = publisher,
        deriveMark = derive,
    ).also { coordinators += it }

    @AfterTest
    fun tearDown() {
        coordinators.forEach { it.close() }
        executor.shutdownNow()
    }

    private fun JournalIdentityRefreshCoordinator.awaitPresentation(
        wanted: (JournalMarkPresentation) -> Boolean,
    ) {
        val latch = CountDownLatch(1)
        val remove = addListener { if (wanted(it)) latch.countDown() }
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "presentation is ${currentPresentation()}")
        } finally {
            remove()
        }
    }

    private fun JournalIdentityRefreshCoordinator.awaitAnswered(ticket: Long) {
        val latch = CountDownLatch(1)
        val remove = addMarkListener {
            if (it is JournalMarkEvent.Answered && it.through >= ticket) latch.countDown()
        }
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "ticket $ticket unanswered")
        } finally {
            remove()
        }
    }

    @Test
    fun aCommittedPairingWithNoMarkComputesItKeepsItAndNamesThePairing() {
        val store = FakeMarkStore()
        val derived = mark("liquefy")
        val notified = AtomicInteger(0)
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            publisher = FakePairingPublisher(home()),
            onMarkUpdated = { notified.incrementAndGet() },
            deriveMark = { derived },
        ).also { coordinators += it }

        coordinator.awaitPresentation { it is JournalMarkPresentation.Identified }

        assertEquals(JournalMarkPresentation.Identified(derived), coordinator.currentPresentation())
        assertEquals(pairingA, coordinator.currentPresentationGeneration())
        assertEquals(JournalMarkRecord(pairingA.instanceId, derived, pairingA, derived = true), store.savedRecord)
        assertEquals(1, notified.get())
    }

    @Test
    fun derivingNeverHoldsUpTheCallerAndReadsAsPendingUntilItEnds() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = coordinator(FakeMarkStore(), FakePairingPublisher(home())) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            mark("liquefy")
        }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())
        assertEquals(pairingA, coordinator.currentPresentationGeneration())

        release.countDown()
        coordinator.awaitPresentation { it is JournalMarkPresentation.Identified }
    }

    @Test
    fun aFailedDerivationIsUnavailableAndIsNotKept() {
        val store = FakeMarkStore()
        val coordinator = coordinator(store, FakePairingPublisher(home())) { throw IllegalStateException("no CA") }

        coordinator.awaitPresentation { it is JournalMarkPresentation.Unavailable }

        assertEquals(pairingA, coordinator.currentPresentationGeneration())
        assertNull(store.savedRecord)
    }

    @Test
    fun runningOutOfMemoryWhileDerivingIsUnavailableToo() {
        val coordinator = coordinator(FakeMarkStore(), FakePairingPublisher(home())) { throw OutOfMemoryError("argon2") }

        coordinator.awaitPresentation { it is JournalMarkPresentation.Unavailable }
    }

    @Test
    fun aRestartPresentsTheKeptMarkWithoutDerivingAgain() {
        val store = FakeMarkStore()
        val kept = mark("liquefy")
        store.savedRecord = JournalMarkRecord(pairingA.instanceId, kept, pairingA, derived = true)
        val derivations = AtomicInteger(0)

        val coordinator = coordinator(store, FakePairingPublisher(home())) {
            derivations.incrementAndGet()
            mark("other")
        }

        assertEquals(JournalMarkPresentation.Identified(kept), coordinator.currentPresentation())
        Thread.sleep(50)
        assertEquals(0, derivations.get())
    }

    @Test
    fun aRestartAfterAFailureTriesAgainInsteadOfStaying() {
        val store = FakeMarkStore()
        val first = coordinator(store, FakePairingPublisher(home())) { throw IllegalStateException("no CA") }
        first.awaitPresentation { it is JournalMarkPresentation.Unavailable }

        val second = coordinator(store, FakePairingPublisher(home())) { mark("liquefy") }

        second.awaitPresentation { it == JournalMarkPresentation.Identified(mark("liquefy")) }
    }

    // A record from when marks were served is a claim the peer made. A stored pairing gets its mark
    // from its stored CA, whichever record it had.
    @Test
    fun aStoredServedMarkIsReplacedByTheComputedOneAndNeverShown() {
        val store = FakeMarkStore()
        store.savedRecord = JournalMarkRecord(pairingA.instanceId, mark("served"), pairingA)
        val seen = CopyOnWriteArrayList<JournalMarkPresentation>()
        val coordinator = coordinator(store, FakePairingPublisher(home())) { mark("computed") }
        coordinator.addListener { seen += it }

        coordinator.awaitPresentation { it == JournalMarkPresentation.Identified(mark("computed")) }

        assertFalse(JournalMarkPresentation.Identified(mark("served")) in seen)
        assertEquals(true, store.savedRecord?.derived)
    }

    @Test
    fun aStoredPairingThatWasGenericBecomesIdentified() {
        val store = FakeMarkStore()
        store.savedRecord = JournalMarkRecord(pairingA.instanceId, null, pairingA)
        val release = CountDownLatch(1)
        val coordinator = coordinator(store, FakePairingPublisher(home())) {
            release.await()
            mark("computed")
        }

        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())
        release.countDown()
        coordinator.awaitPresentation { it == JournalMarkPresentation.Identified(mark("computed")) }
    }

    @Test
    fun anUnreadableStoreIsComputedAgain() {
        val store = FakeMarkStore().apply { unreadable = true }
        val coordinator = coordinator(store, FakePairingPublisher(home())) { mark("computed") }

        coordinator.awaitPresentation { it == JournalMarkPresentation.Identified(mark("computed")) }
        assertEquals(mark("computed"), store.savedRecord?.mark)
    }

    @Test
    fun aMarkThatCannotBeSavedIsStillShown() {
        val store = FakeMarkStore().apply { failSave = true }
        val coordinator = coordinator(store, FakePairingPublisher(home())) { mark("computed") }

        coordinator.awaitPresentation { it == JournalMarkPresentation.Identified(mark("computed")) }
        assertNull(store.savedRecord)
    }

    @Test
    fun aRecordForAnotherPairingIsNotThisPairingsMark() {
        val store = FakeMarkStore()
        store.savedRecord = JournalMarkRecord(pairingB.instanceId, mark("other"), pairingB, derived = true)
        val coordinator = coordinator(store, FakePairingPublisher(home(pairingA))) { mark("computed") }

        coordinator.awaitPresentation { it == JournalMarkPresentation.Identified(mark("computed")) }
    }

    @Test
    fun neverPairedKeepsTheGenericMark() {
        val coordinator = coordinator(FakeMarkStore(), FakePairingPublisher(null)) { error("nothing to derive") }

        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())
        assertNull(coordinator.currentPresentationGeneration())
    }

    @Test
    fun forgettingTheJournalReturnsToGeneric() {
        val store = FakeMarkStore()
        store.savedRecord = JournalMarkRecord(pairingA.instanceId, mark("kept"), pairingA, derived = true)
        val publisher = FakePairingPublisher(home())
        val coordinator = coordinator(store, publisher) { error("kept") }

        publisher.forget()
        coordinator.onIdentityChanged()

        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())
        assertNull(coordinator.currentPresentationGeneration())
        assertNull(store.savedRecord)
    }

    // Absent means no journal. A pairing record that can't be read isn't that.
    @Test
    fun aPairingThatCannotBeReadIsUnavailableNotGeneric() {
        val publisher = FakePairingPublisher(home()).apply { uncertain = true }
        val coordinator = coordinator(FakeMarkStore(), publisher) { error("nothing to derive") }

        assertIs<JournalMarkPresentation.Unavailable>(coordinator.currentPresentation())

        publisher.uncertain = false
        publisher.home = null
        publisher.publish()
        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())
    }

    @Test
    fun noCommittedPairingEverPresentsGeneric() {
        val seen = CopyOnWriteArrayList<JournalMarkPresentation>()
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home())
        val failing = AtomicInteger(0)
        val coordinator = coordinator(store, publisher) {
            if (failing.getAndIncrement() == 0) throw IllegalStateException("first") else mark("computed")
        }
        coordinator.addListener { seen += it }

        coordinator.awaitPresentation { it is JournalMarkPresentation.Unavailable }
        val ticket = assertNotNull(coordinator.onMarkRequested())
        coordinator.awaitAnswered(ticket)
        coordinator.onPairingChanged()
        coordinator.awaitPresentation { it is JournalMarkPresentation.Identified }

        assertFalse(seen.any { it is JournalMarkPresentation.Generic }, "saw $seen")
    }

    @Test
    fun aNewPairingFromTheAuthorityIsPickedUpWithoutAnyoneAsking() {
        val publisher = FakePairingPublisher(null)
        val coordinator = coordinator(FakeMarkStore(), publisher) { pairing -> mark(pairing.instanceId.filter(Char::isLetter)) }
        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())

        publisher.home = home(pairingA)
        publisher.publish()
        coordinator.awaitPresentation { it == JournalMarkPresentation.Identified(mark("inst")) }
        assertEquals(pairingA, coordinator.currentPresentationGeneration())
    }

    @Test
    fun aDerivationForAReplacedPairingCannotBecomeTheNewPairingsMark() {
        val store = FakeMarkStore()
        val publisher = FakePairingPublisher(home(pairingA))
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val coordinator = coordinator(store, publisher) { pairing ->
            if (pairing == pairingA) {
                enteredA.countDown()
                releaseA.await(5, TimeUnit.SECONDS)
                mark("forA")
            } else {
                mark("forB")
            }
        }
        assertTrue(enteredA.await(5, TimeUnit.SECONDS))

        publisher.home = home(pairingB)
        publisher.publish()
        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())
        assertEquals(pairingB, coordinator.currentPresentationGeneration())
        releaseA.countDown()

        coordinator.awaitPresentation { it == JournalMarkPresentation.Identified(mark("forB")) }
        assertEquals(pairingB, store.savedRecord?.pairing)
        assertEquals(mark("forB"), store.savedRecord?.mark)
    }

    @Test
    fun aFailureForAReplacedPairingCannotMarkTheNewPairingUnavailable() {
        val publisher = FakePairingPublisher(home(pairingA))
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val seen = CopyOnWriteArrayList<JournalMarkPresentation>()
        val coordinator = coordinator(FakeMarkStore(), publisher) { pairing ->
            if (pairing == pairingA) {
                enteredA.countDown()
                releaseA.await(5, TimeUnit.SECONDS)
                throw IllegalStateException("late failure")
            }
            mark("forB")
        }
        coordinator.addListener { seen += it }
        assertTrue(enteredA.await(5, TimeUnit.SECONDS))

        publisher.home = home(pairingB)
        publisher.publish()
        releaseA.countDown()

        coordinator.awaitPresentation { it == JournalMarkPresentation.Identified(mark("forB")) }
        assertFalse(seen.any { it is JournalMarkPresentation.Unavailable }, "saw $seen")
    }

    // Other mutations of a pairing (its addresses, its relay access) publish too. Argon2 is costly.
    @Test
    fun theSamePairingPublishedAgainDoesNotDeriveAgain() {
        val derivations = AtomicInteger(0)
        val publisher = FakePairingPublisher(home())
        val coordinator = coordinator(FakeMarkStore(), publisher) {
            derivations.incrementAndGet()
            mark("computed")
        }
        coordinator.awaitPresentation { it is JournalMarkPresentation.Identified }

        publisher.publish()
        publisher.publish()
        val ticket = assertNotNull(coordinator.onMarkRequested())
        coordinator.awaitAnswered(ticket)

        assertEquals(1, derivations.get())
    }

    @Test
    fun aPromptAskingWhileOneIsInFlightDoesNotDeriveTwice() {
        val derivations = AtomicInteger(0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = coordinator(FakeMarkStore(), FakePairingPublisher(home())) {
            derivations.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            mark("computed")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val ticket = assertNotNull(coordinator.onMarkRequested())
        release.countDown()
        coordinator.awaitAnswered(ticket)

        assertEquals(1, derivations.get())
        assertEquals(JournalMarkPresentation.Identified(mark("computed")), coordinator.currentPresentation())
    }

    @Test
    fun askingAfterAFailureTriesAgain() {
        val failing = AtomicInteger(0)
        val coordinator = coordinator(FakeMarkStore(), FakePairingPublisher(home())) {
            if (failing.getAndIncrement() == 0) throw IllegalStateException("first") else mark("computed")
        }
        coordinator.awaitPresentation { it is JournalMarkPresentation.Unavailable }

        val ticket = assertNotNull(coordinator.onMarkRequested())
        coordinator.awaitAnswered(ticket)

        assertEquals(JournalMarkPresentation.Identified(mark("computed")), coordinator.currentPresentation())
    }

    @Test
    fun aDerivationPastItsBoundIsUnavailableAndIsNotTakenLate() {
        val store = FakeMarkStore()
        val release = CountDownLatch(1)
        val coordinator = coordinator(store, FakePairingPublisher(home()), boundMillis = 100L) {
            // Argon2 doesn't stop for an interrupt, so neither does this.
            var interrupted = false
            while (release.count > 0) {
                try {
                    release.await(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            mark("late")
        }

        coordinator.awaitPresentation { it is JournalMarkPresentation.Unavailable }
        release.countDown()
        Thread.sleep(100)

        assertIs<JournalMarkPresentation.Unavailable>(coordinator.currentPresentation())
        assertNull(store.savedRecord)
    }

    @Test
    fun noCommittedPairingAnswersNoTicketAndTicketsStrictlyIncrease() {
        val publisher = FakePairingPublisher(null)
        val coordinator = coordinator(FakeMarkStore(), publisher) { mark("computed") }
        assertNull(coordinator.onMarkRequested())

        publisher.home = home()
        val first = assertNotNull(coordinator.onMarkRequested())
        val second = assertNotNull(coordinator.onMarkRequested())
        assertTrue(second > first)
    }

    @Test
    fun aListenerJoiningMidWaitHearsThatItsTicketIsNotAnsweredYet() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = coordinator(FakeMarkStore(), FakePairingPublisher(home())) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            mark("computed")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val ticket = assertNotNull(coordinator.onMarkRequested())

        val events = CopyOnWriteArrayList<JournalMarkEvent>()
        val replay = CountDownLatch(2)
        val remove = coordinator.addMarkListener {
            events += it
            replay.countDown()
        }
        assertTrue(replay.await(5, TimeUnit.SECONDS))

        assertIs<JournalMarkEvent.Presented>(events[0])
        assertTrue(assertIs<JournalMarkEvent.Answered>(events[1]).through < ticket)

        release.countDown()
        remove()
    }

    @Test
    fun aResetAnswersTheTicketItDropped() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = coordinator(FakeMarkStore(), FakePairingPublisher(home())) {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            mark("computed")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val ticket = assertNotNull(coordinator.onMarkRequested())

        coordinator.onPairingChanged()

        assertTrue(coordinator.answeredThrough() >= ticket)
        val next = assertNotNull(coordinator.onMarkRequested())
        assertTrue(next > coordinator.answeredThrough())
        release.countDown()
        coordinator.awaitAnswered(next)
    }

    @Test
    fun anUnfinishedDerivationDoesNotEndALaterTicket() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = coordinator(FakeMarkStore(), FakePairingPublisher(home()), boundMillis = 200L) {
            entered.countDown()
            var waiting = true
            while (waiting) {
                try {
                    release.await(5, TimeUnit.SECONDS)
                    waiting = false
                } catch (_: InterruptedException) {
                }
            }
            mark("computed")
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val ticket = assertNotNull(coordinator.onMarkRequested())
        coordinator.awaitPresentation { it is JournalMarkPresentation.Unavailable }
        assertTrue(coordinator.answeredThrough() < ticket)

        release.countDown()
        coordinator.awaitAnswered(ticket)
    }

    @Test
    fun aBlockingListenerCannotStarveTheOthers() {
        val blockListener = CountDownLatch(1)
        val other = CountDownLatch(1)
        val store = FakeMarkStore()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            publisher = FakePairingPublisher(null),
            deriveMark = { mark("computed") },
        ).also { coordinators += it }

        coordinator.addListener { blockListener.await(5, TimeUnit.SECONDS) }
        coordinator.addListener { other.countDown() }

        assertTrue(other.await(5, TimeUnit.SECONDS))
        blockListener.countDown()
    }
}
