// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class IdentityRefreshSnapshot(
    val instanceId: String,
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
) : Closeable {
    private val job = CoalescingBoundedJob<IdentityRefreshSnapshot>(
        name = "journal-identity-refresh",
        boundMillis = boundMillis,
        executor = executor,
    )

    private val listeners = CopyOnWriteArrayList<(JournalMarkPresentation) -> Unit>()

    @Volatile
    private var currentPresentation: JournalMarkPresentation =
        store.load()?.mark?.let { JournalMarkPresentation.Identified(it) } ?: JournalMarkPresentation.Generic

    fun currentPresentation(): JournalMarkPresentation = currentPresentation

    fun addListener(listener: (JournalMarkPresentation) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(currentPresentation)
        return { listeners.remove(listener) }
    }

    private fun updatePresentation(newPresentation: JournalMarkPresentation) {
        currentPresentation = newPresentation
        for (listener in listeners) {
            listener(newPresentation)
        }
        onMarkUpdated()
    }

    open fun onIdentityChanged() {
        job.bumpGeneration()
        store.clear()
        updatePresentation(JournalMarkPresentation.Generic)
    }

    open fun onPairingChanged() {
        job.bumpGeneration()
    }

    open fun onUsableConnection(
        instanceId: String,
        pairingMatches: () -> Boolean = { true },
        openClient: () -> PlHttpClient,
    ) {
        val snapshot = IdentityRefreshSnapshot(
            instanceId = instanceId,
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
                        if (resp.committed && mark != null) {
                            val record = JournalMarkRecord(
                                instanceId = snap.instanceId,
                                mark = mark,
                            )
                            store.save(record)
                            updatePresentation(JournalMarkPresentation.Identified(mark))
                        } else {
                            val record = JournalMarkRecord(
                                instanceId = snap.instanceId,
                                mark = null,
                            )
                            store.save(record)
                            updatePresentation(JournalMarkPresentation.Generic)
                        }
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

    override fun close() {
        job.close()
    }
}
