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

data class IdentityRefreshSnapshot(
    val instanceId: String,
    val pairing: PairingGeneration?,
    val pairingMatches: () -> Boolean = { true },
    val openClient: () -> PlHttpClient,
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
    private val job = CoalescingBoundedJob<IdentityRefreshSnapshot>(
        name = "journal-identity-refresh",
        boundMillis = boundMillis,
        executor = executor,
    )

    private val listeners = CopyOnWriteArrayList<ListenerDelivery<JournalMarkPresentation>>()
    private val generationListeners =
        CopyOnWriteArrayList<ListenerDelivery<Pair<PairingGeneration?, JournalMarkPresentation>>>()

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

    fun addListener(listener: (JournalMarkPresentation) -> Unit): () -> Unit {
        val delivery = ListenerDelivery(listener)
        listeners.add(delivery)
        delivery.deliver(currentPresentation)
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
        generationListeners.add(delivery)
        delivery.deliver(currentPresentationGeneration to currentPresentation)
        return {
            generationListeners.remove(delivery)
            delivery.close()
        }
    }

    private fun updatePresentation(
        newPresentation: JournalMarkPresentation,
        generation: PairingGeneration? =
            (publisher?.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing,
    ) {
        currentPresentation = newPresentation
        currentPresentationGeneration = generation
        for (listener in listeners) {
            listener.deliver(newPresentation)
        }
        for (listener in generationListeners) {
            listener.deliver(generation to newPresentation)
        }
        runCatching { onMarkUpdated() }
    }

    open fun onIdentityChanged() {
        job.bumpGeneration()
        store.clear()
        updatePresentation(initialPresentation())
    }

    open fun onPairingChanged() {
        job.bumpGeneration()
        updatePresentation(initialPresentation())
    }

    open fun onUsableConnection(
        instanceId: String,
        pairingMatches: () -> Boolean = { true },
        openClient: () -> PlHttpClient,
    ) {
        val snapshot = IdentityRefreshSnapshot(
            instanceId = instanceId,
            pairing = (publisher?.currentSnapshot() as? PairingGraphSnapshot.Committed)
                ?.pairing
                ?.takeIf { it.instanceId == instanceId },
            pairingMatches = pairingMatches,
            openClient = openClient,
        )
        job.submit(snapshot) { snap, gen ->
            executeIdentityJob(snap, gen)
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
                        commitIfCurrent(snap, record, presentation)
                    }
                }
                is IdentityGetResult.NotFound,
                is IdentityGetResult.Failure -> {
                    if (gen == job.currentGeneration() && snap.pairingMatches()) {
                        updatePresentation(JournalMarkPresentation.Unavailable)
                    }
                }
            }
        } catch (_: Throwable) {
            if (gen == job.currentGeneration() && snap.pairingMatches()) {
                updatePresentation(JournalMarkPresentation.Unavailable)
            }
        } finally {
            if (client is Closeable) {
                runCatching { client.close() }
            }
        }
    }

    private fun commitIfCurrent(
        snapshot: IdentityRefreshSnapshot,
        record: JournalMarkRecord,
        presentation: JournalMarkPresentation,
    ) {
        val authority = publisher
        if (authority == null) {
            if (!snapshot.pairingMatches()) return
            store.save(record)
            updatePresentation(presentation, snapshot.pairing)
            return
        }
        var committed = false
        authority.withMutationBoundary {
            val current = (authority.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
            if (snapshot.pairing == null || current != snapshot.pairing || !snapshot.pairingMatches()) {
                return@withMutationBoundary
            }
            store.save(record)
            committed = true
        }
        if (committed) {
            val current = (authority.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
            if (current == snapshot.pairing) {
                updatePresentation(presentation, snapshot.pairing)
            }
        }
    }

    override fun close() {
        job.close()
        listeners.forEach { it.close() }
        generationListeners.forEach { it.close() }
        listeners.clear()
        generationListeners.clear()
    }
}
