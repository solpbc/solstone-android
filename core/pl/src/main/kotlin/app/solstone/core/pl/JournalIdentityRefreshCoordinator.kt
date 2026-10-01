// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.StoreInspectResult
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
    val instanceId: String,
    val pairing: PairingGeneration?,
    val pairingMatches: () -> Boolean = { true },
    val openClient: () -> PlHttpClient,
    val requestSeq: Long,
)

open class JournalIdentityRefreshCoordinator(
    private val store: JournalMarkStore,
    executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "journal-identity-refresh").apply { isDaemon = true }
    },
    boundMillis: Long = 15_000L,
    private val onMarkUpdated: () -> Unit = {},
    private val publisher: PairingPublisher? = null,
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
    private var currentPresentation: JournalMarkPresentation = initialPresentation()
    @Volatile
    private var currentPresentationGeneration: PairingGeneration? =
        (publisher?.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing

    // Loading means a load is pending. With a pairing authority and no committed pairing there is
    // no journal to load a mark from, so an owner who has never paired, or who has forgotten their
    // journal, keeps the generic mark instead of one that reads as loading forever. Without an
    // authority the caller owns that fact, and an empty store still reads as a pending load.
    private fun initialPresentation(): JournalMarkPresentation {
        val current = (publisher?.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
        if (publisher != null && current == null) return JournalMarkPresentation.Generic
        return when (val inspected = store.inspect()) {
            StoreInspectResult.Missing -> JournalMarkPresentation.Loading
            is StoreInspectResult.Unreadable -> JournalMarkPresentation.Unavailable
            is StoreInspectResult.Ready -> {
                val record = inspected.value
                if (publisher != null && (record.pairing == null || record.pairing != current)) {
                    JournalMarkPresentation.Loading
                } else {
                    record.mark?.let(JournalMarkPresentation::Identified)
                        ?: JournalMarkPresentation.Generic
                }
            }
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
                val init = initialPresentation()
                val gen = (publisher?.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
                updatePresentationLocked(init, gen)
                answer(requestCounter)
            }
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
                val init = initialPresentation()
                val gen = (publisher?.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
                updatePresentationLocked(init, gen)
                answer(requestCounter)
            }
            updated = true
        }
        if (updated) {
            runCatching { onMarkUpdated() }
        }
    }

    open fun onUsableConnection(
        instanceId: String,
        pairingMatches: () -> Boolean = { true },
        openClient: () -> PlHttpClient,
    ) {
        synchronized(submitLock) {
            val snapshot = IdentityRefreshSnapshot(
                instanceId = instanceId,
                pairing = (publisher?.currentSnapshot() as? PairingGraphSnapshot.Committed)
                    ?.pairing
                    ?.takeIf { it.instanceId == instanceId },
                pairingMatches = pairingMatches,
                openClient = openClient,
                requestSeq = requestCounter,
            )
            job.submit(snapshot) { snap, gen ->
                executeIdentityJob(snap, gen)
            }
        }
    }

    fun onMarkRequested(
        pairingMatches: () -> Boolean,
        openClient: (() -> PlHttpClient)?,
    ): Long? {
        synchronized(submitLock) {
            val currentCommitted = (publisher?.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
                ?: return null
            val seq = ++requestCounter
            val snapshot = IdentityRefreshSnapshot(
                instanceId = currentCommitted.instanceId,
                pairing = currentCommitted,
                pairingMatches = pairingMatches,
                openClient = openClient ?: { error("no client for parked mark request") },
                requestSeq = seq,
            )
            job.submit(snapshot) { snap, gen ->
                if (openClient != null) {
                    executeIdentityJob(snap, gen)
                } else {
                    try {
                        Thread.sleep(Long.MAX_VALUE)
                    } catch (_: InterruptedException) {
                    }
                }
            }
            return seq
        }
    }

    private fun executeIdentityJob(snap: IdentityRefreshSnapshot, gen: Long) {
        var client: PlHttpClient? = null
        try {
            client = snap.openClient()
            when (val getResult = fetchJournalIdentity(client)) {
                is IdentityGetResult.Success -> {
                    val resp = getResult.response
                    if (gen == job.currentGeneration() && snap.pairingMatches()) {
                        if (resp.instanceId != null && resp.instanceId != snap.instanceId) {
                            presentUnavailableIfCurrent(snap, gen)
                        } else {
                            val mark = resp.mark
                            val record = JournalMarkRecord(
                                instanceId = snap.instanceId,
                                mark = if (resp.committed) mark else null,
                                pairing = snap.pairing,
                            )
                            val presentation = if (resp.committed && mark != null) {
                                JournalMarkPresentation.Identified(mark)
                            } else {
                                JournalMarkPresentation.Generic
                            }
                            commitIfCurrent(snap, record, presentation, gen)
                        }
                    }
                }
                is IdentityGetResult.NotFound,
                is IdentityGetResult.Failure -> {
                    presentUnavailableIfCurrent(snap, gen)
                }
            }
        } catch (_: Throwable) {
            presentUnavailableIfCurrent(snap, gen)
        } finally {
            if (client is Closeable) {
                runCatching { client.close() }
            }
        }
    }

    private fun presentUnavailableIfCurrent(
        snap: IdentityRefreshSnapshot,
        gen: Long,
    ) {
        presentUnavailable(snap, gen == job.currentGeneration()) { gen == job.currentGeneration() }
    }

    private fun presentUnavailable(
        snap: IdentityRefreshSnapshot,
        generationAllows: Boolean,
        generationAllowsNow: () -> Boolean = { generationAllows },
    ) {
        if (!generationAllows || !snap.pairingMatches()) return
        val authority = publisher
        if (authority == null) {
            var updated = false
            synchronized(publishLock) {
                if (generationAllowsNow() && snap.pairingMatches()) {
                    updatePresentationLocked(JournalMarkPresentation.Unavailable, snap.pairing)
                    updated = true
                }
            }
            if (updated) {
                runCatching { onMarkUpdated() }
            }
            return
        }
        var matches = false
        authority.withMutationBoundary {
            val current = (authority.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
            if (generationAllows && snap.pairing != null && current == snap.pairing && snap.pairingMatches()) {
                matches = true
            }
        }
        if (matches) {
            var updated = false
            synchronized(publishLock) {
                val current = (authority.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
                if (generationAllowsNow() && current == snap.pairing && snap.pairingMatches()) {
                    updatePresentationLocked(JournalMarkPresentation.Unavailable, snap.pairing)
                    updated = true
                }
            }
            if (updated) {
                runCatching { onMarkUpdated() }
            }
        }
    }

    private fun commitIfCurrent(
        snapshot: IdentityRefreshSnapshot,
        record: JournalMarkRecord,
        presentation: JournalMarkPresentation,
        gen: Long,
    ) {
        val authority = publisher
        if (authority == null) {
            if (gen != job.currentGeneration() || !snapshot.pairingMatches()) return
            store.save(record)
            var committed = false
            synchronized(publishLock) {
                if (gen == job.currentGeneration() && snapshot.pairingMatches()) {
                    updatePresentationLocked(presentation, snapshot.pairing)
                    committed = true
                }
            }
            if (committed) {
                runCatching { onMarkUpdated() }
            }
            return
        }
        var committed = false
        authority.withMutationBoundary {
            val current = (authority.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
            if (gen != job.currentGeneration() || snapshot.pairing == null || current != snapshot.pairing || !snapshot.pairingMatches()) {
                return@withMutationBoundary
            }
            store.save(record)
            committed = true
        }
        if (committed) {
            var updated = false
            synchronized(publishLock) {
                val current = (authority.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
                if (gen == job.currentGeneration() && current == snapshot.pairing && snapshot.pairingMatches()) {
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
        job.close()
        listeners.forEach { it.close() }
        generationListeners.forEach { it.close() }
        markListeners.forEach { it.close() }
        listeners.clear()
        generationListeners.clear()
        markListeners.clear()
    }
}
