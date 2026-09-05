// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class JournalVersionFreshness { NEVER_OBSERVED, LAST_KNOWN, CURRENT }

data class JournalVersionReading(val version: String?, val freshness: JournalVersionFreshness)

class JournalVersionRefreshCoordinator(
    private val store: JournalVersionStore,
    private val executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "journal-version-refresh").apply { isDaemon = true }
    },
    private val boundMillis: Long = 5000,
) {
    private val generation = AtomicLong(0)
    @Volatile private var freshForLatestGeneration = false

    fun onUsableConnection(
        instanceId: String,
        caChainFingerprint: String,
        openClient: () -> PlHttpClient,
    ) {
        val gen = synchronized(this) {
            val g = generation.incrementAndGet()
            freshForLatestGeneration = false
            g
        }
        executor.submit {
            val version = boundedFetch(openClient)
            synchronized(this) {
                if (gen == generation.get()) {
                    if (version != null) {
                        store.save(JournalVersionRecord(instanceId, caChainFingerprint, version))
                        freshForLatestGeneration = true
                    }
                }
            }
        }
    }

    fun onConnectionLost() {
        synchronized(this) {
            generation.incrementAndGet()
            freshForLatestGeneration = false
        }
    }

    fun onIdentityChanged() {
        synchronized(this) {
            generation.incrementAndGet()
            freshForLatestGeneration = false
            store.clear()
        }
    }

    fun currentReading(instanceId: String, caChainFingerprint: String): JournalVersionReading {
        val record = store.load()
        return when {
            record == null || record.instanceId != instanceId || record.caChainFingerprint != caChainFingerprint ->
                JournalVersionReading(null, JournalVersionFreshness.NEVER_OBSERVED)
            freshForLatestGeneration -> JournalVersionReading(record.version, JournalVersionFreshness.CURRENT)
            else -> JournalVersionReading(record.version, JournalVersionFreshness.LAST_KNOWN)
        }
    }

    private fun boundedFetch(openClient: () -> PlHttpClient): String? {
        val future = executor.submit(Callable<String?> {
            var client: PlHttpClient? = null
            try {
                client = openClient()
                fetchJournalVersion(client)
            } catch (_: Exception) {
                null
            } finally {
                try {
                    (client as? Closeable)?.close()
                } catch (_: Exception) {
                }
            }
        })
        return try {
            future.get(boundMillis, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }
}
