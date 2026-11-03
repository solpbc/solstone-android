// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.identity.SubscriptionHandle
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private class ListenerDelivery<T>(
    private val listener: (T) -> Unit,
) : Closeable {
    private val active = AtomicBoolean(true)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "journal-mark-listener").apply { isDaemon = true }
    }

    fun deliver(value: T) {
        if (!active.get()) return
        runCatching {
            executor.execute {
                if (active.get()) runCatching { listener(value) }
            }
        }
    }

    override fun close() {
        active.set(false)
        executor.shutdownNow()
    }
}

sealed class JournalMarkEvent {
    data class Presented(val generation: PairingGeneration?, val presentation: JournalMarkPresentation) : JournalMarkEvent()
    data class Answered(val through: Long) : JournalMarkEvent()
}

data class IdentityRefreshSnapshot(
    val pairing: PairingGeneration,
    val requestSeq: Long,
)

/** The mark of the journal whose CA [pairing] pinned, computed from that stored CA. */
private fun markFromPinnedCa(publisher: PairingPublisher?, pairing: PairingGeneration): JournalMark {
    val caPem = checkNotNull(publisher?.pinnedCaPem(pairing)) { "no stored CA for this pairing" }
    return journalMarkFromCaPem(caPem)
}

/**
 * Keeps the mark of the committed pairing: computed here from the CA that pairing pinned, once per
 * pairing, off the main thread, and stored. Nothing the journal serves reaches it. A forged link
 * pins the forger's key, so the owner asked about this mark sees the forger's own.
 *
 * Whatever has no mark yet (a new pairing, a replaced one, or a pairing stored before marks were
 * computed here) is picked up from the pairing authority, so there is no read to depend on and
 * nothing a restart can lose. A failed derivation presents unavailable, and is tried again when the
 * prompt asks and on the next start.
 */
open class JournalIdentityRefreshCoordinator(
    private val store: JournalMarkStore,
    executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "journal-identity-refresh").apply { isDaemon = true }
    },
    boundMillis: Long = 60_000L,
    private val onMarkUpdated: () -> Unit = {},
    private val publisher: PairingPublisher? = null,
    private val deriveMark: (PairingGeneration) -> JournalMark = { markFromPinnedCa(publisher, it) },
) : Closeable {
    private val submitLock = Any()
    private val publishLock = Any()
    private var requestCounter: Long = 0
    private var answeredThrough: Long = 0

    private val job: CoalescingBoundedJob<IdentityRefreshSnapshot> = CoalescingBoundedJob(
        name = "journal-identity-refresh",
        boundMillis = boundMillis,
        executor = executor,
        onGiveUp = { snap, targetGen -> onJobGiveUp(snap, targetGen) },
        onJobEnded = { snap, _ -> answer(snap.requestSeq) },
    )

    private fun onJobGiveUp(snap: IdentityRefreshSnapshot, targetGen: Long) {
        presentUnavailableIfCurrent(snap, targetGen + 1)
    }

    private val listeners = CopyOnWriteArrayList<ListenerDelivery<JournalMarkPresentation>>()
    private val generationListeners =
        CopyOnWriteArrayList<ListenerDelivery<Pair<PairingGeneration?, JournalMarkPresentation>>>()
    private val markListeners = CopyOnWriteArrayList<ListenerDelivery<JournalMarkEvent>>()

    @Volatile
    private var currentPresentation: JournalMarkPresentation = initialPresentation(publisher?.currentSnapshot())
    @Volatile
    private var currentPresentationGeneration: PairingGeneration? = committedPairing()

    private val subscription: SubscriptionHandle?

    init {
        submitIfPending()
        subscription = publisher?.subscribe { onPairingPublished() }
    }

    private fun committedPairing(): PairingGeneration? =
        (publisher?.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing

    // Generic means no journal: an owner who has never paired, or who has forgotten theirs. A
    // committed pairing is never generic. It has its computed mark, or one is on its way. A pairing
    // record that can't be read is a journal whose mark can't be shown, not an absent one.
    private fun initialPresentation(snapshot: PairingGraphSnapshot?): JournalMarkPresentation = when (snapshot) {
        null -> JournalMarkPresentation.Loading
        is PairingGraphSnapshot.Absent -> JournalMarkPresentation.Generic
        is PairingGraphSnapshot.Uncertain -> JournalMarkPresentation.Unavailable
        is PairingGraphSnapshot.Committed ->
            keptMark(snapshot.pairing)?.let(JournalMarkPresentation::Identified) ?: JournalMarkPresentation.Loading
    }

    /** A mark computed for exactly this pairing. A record from when marks were served never counts. */
    private fun keptMark(pairing: PairingGeneration): JournalMark? =
        (store.inspect() as? StoreInspectResult.Ready)?.value
            ?.takeIf { it.derived && it.pairing == pairing }
            ?.mark

    private fun submitIfPending() {
        synchronized(submitLock) {
            val pairing = committedPairing() ?: return
            if (currentPresentation !is JournalMarkPresentation.Loading) return
            submitDerivation(pairing, requestCounter)
        }
    }

    private fun submitDerivation(pairing: PairingGeneration, seq: Long) {
        job.submit(IdentityRefreshSnapshot(pairing, seq)) { snap, gen -> executeDerivation(snap, gen) }
    }

    // Reads the authority rather than the published value: delivery is queued, and a stale snapshot
    // must not put a replaced pairing's state back.
    private fun onPairingPublished() {
        var updated = false
        synchronized(submitLock) {
            val snapshot = publisher?.currentSnapshot() ?: return
            val pairing = (snapshot as? PairingGraphSnapshot.Committed)?.pairing
            val next = synchronized(publishLock) {
                val samePairing = pairing == currentPresentationGeneration
                // The same pairing keeps what it has: a mark, a derivation in flight, or a failure
                // that waits to be asked again.
                if (samePairing && pairing != null) return
                val next = initialPresentation(snapshot)
                if (samePairing && next == currentPresentation) return
                job.bumpGeneration()
                updatePresentationLocked(next, pairing)
                answer(requestCounter)
                next
            }
            updated = true
            if (pairing != null && next is JournalMarkPresentation.Loading) {
                submitDerivation(pairing, requestCounter)
            }
        }
        if (updated) {
            runCatching { onMarkUpdated() }
        }
    }

    fun currentPresentation(): JournalMarkPresentation = currentPresentation

    fun currentPresentationGeneration(): PairingGeneration? = currentPresentationGeneration

    fun answeredThrough(): Long = synchronized(publishLock) { answeredThrough }

    fun addListener(listener: (JournalMarkPresentation) -> Unit): () -> Unit {
        val delivery = ListenerDelivery(listener)
        synchronized(publishLock) {
            listeners.add(delivery)
            delivery.deliver(currentPresentation)
        }
        return {
            listeners.remove(delivery)
            delivery.close()
        }
    }

    fun addGenerationListener(
        listener: (PairingGeneration?, JournalMarkPresentation) -> Unit,
    ): () -> Unit {
        val delivery = ListenerDelivery<Pair<PairingGeneration?, JournalMarkPresentation>> { event ->
            listener(event.first, event.second)
        }
        synchronized(publishLock) {
            generationListeners.add(delivery)
            delivery.deliver(currentPresentationGeneration to currentPresentation)
        }
        return {
            generationListeners.remove(delivery)
            delivery.close()
        }
    }

    fun addMarkListener(
        listener: (JournalMarkEvent) -> Unit,
    ): () -> Unit {
        val delivery = ListenerDelivery(listener)
        synchronized(publishLock) {
            markListeners.add(delivery)
            delivery.deliver(JournalMarkEvent.Presented(currentPresentationGeneration, currentPresentation))
            delivery.deliver(JournalMarkEvent.Answered(answeredThrough))
        }
        return {
            markListeners.remove(delivery)
            delivery.close()
        }
    }

    private fun updatePresentationLocked(
        newPresentation: JournalMarkPresentation,
        generation: PairingGeneration?,
    ) {
        currentPresentation = newPresentation
        currentPresentationGeneration = generation
        for (listener in listeners) {
            listener.deliver(newPresentation)
        }
        for (listener in generationListeners) {
            listener.deliver(generation to newPresentation)
        }
        for (listener in markListeners) {
            listener.deliver(JournalMarkEvent.Presented(generation, newPresentation))
        }
    }

    private fun answer(seq: Long) {
        synchronized(publishLock) {
            if (seq > answeredThrough) {
                answeredThrough = seq
                val event = JournalMarkEvent.Answered(answeredThrough)
                for (listener in markListeners) {
                    listener.deliver(event)
                }
            }
        }
    }

    open fun onIdentityChanged() {
        var updated = false
        synchronized(submitLock) {
            job.bumpGeneration()
            store.clear()
            synchronized(publishLock) {
                updatePresentationLocked(initialPresentation(publisher?.currentSnapshot()), committedPairing())
                answer(requestCounter)
            }
            submitIfPending()
            updated = true
        }
        if (updated) {
            runCatching { onMarkUpdated() }
        }
    }

    open fun onPairingChanged() {
        var updated = false
        synchronized(submitLock) {
            job.bumpGeneration()
            synchronized(publishLock) {
                updatePresentationLocked(initialPresentation(publisher?.currentSnapshot()), committedPairing())
                answer(requestCounter)
            }
            submitIfPending()
            updated = true
        }
        if (updated) {
            runCatching { onMarkUpdated() }
        }
    }

    /** The prompt asking: answers with a ticket once the committed pairing's mark has settled. */
    fun onMarkRequested(): Long? {
        synchronized(submitLock) {
            val pairing = committedPairing() ?: return null
            val seq = ++requestCounter
            submitDerivation(pairing, seq)
            return seq
        }
    }

    private fun executeDerivation(snap: IdentityRefreshSnapshot, gen: Long) {
        if (gen != job.currentGeneration() || committedPairing() != snap.pairing) return
        try {
            val kept = keptMark(snap.pairing)
            val mark = kept ?: deriveMark(snap.pairing)
            val record = JournalMarkRecord(snap.pairing.instanceId, mark, snap.pairing, derived = true)
            commitIfCurrent(snap, record, JournalMarkPresentation.Identified(mark), gen, save = kept == null)
        } catch (_: Throwable) {
            presentUnavailableIfCurrent(snap, gen)
        }
    }

    private fun presentUnavailableIfCurrent(
        snap: IdentityRefreshSnapshot,
        gen: Long,
    ) {
        var updated = false
        synchronized(publishLock) {
            if (gen == job.currentGeneration() && committedPairing() == snap.pairing) {
                updatePresentationLocked(JournalMarkPresentation.Unavailable, snap.pairing)
                updated = true
            }
        }
        if (updated) {
            runCatching { onMarkUpdated() }
        }
    }

    private fun commitIfCurrent(
        snapshot: IdentityRefreshSnapshot,
        record: JournalMarkRecord,
        presentation: JournalMarkPresentation,
        gen: Long,
        save: Boolean,
    ) {
        val authority = publisher ?: return
        var committed = false
        authority.withMutationBoundary {
            if (gen != job.currentGeneration() || committedPairing() != snapshot.pairing) {
                return@withMutationBoundary
            }
            // A mark that couldn't be kept is still the right mark. The next start computes it again.
            if (save) runCatching { store.save(record) }
            committed = true
        }
        if (committed) {
            var updated = false
            synchronized(publishLock) {
                if (gen == job.currentGeneration() && committedPairing() == snapshot.pairing) {
                    updatePresentationLocked(presentation, snapshot.pairing)
                    updated = true
                }
            }
            if (updated) {
                runCatching { onMarkUpdated() }
            }
        }
    }

    override fun close() {
        subscription?.cancel()
        job.close()
        listeners.forEach { it.close() }
        generationListeners.forEach { it.close() }
        markListeners.forEach { it.close() }
        listeners.clear()
        generationListeners.clear()
        markListeners.clear()
    }
}
