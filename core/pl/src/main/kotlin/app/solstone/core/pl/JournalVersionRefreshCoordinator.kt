// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import java.io.Closeable
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
        val gen = generation.incrementAndGet()
        freshForLatestGeneration = false
        executor.submit {
            val version = boundedFetch(openClient)
            synchronized(this) {
                if (gen != generation.get()) return@synchronized
                if (version != null) {
                    store.save(JournalVersionRecord(instanceId, caChainFingerprint, version))
                    freshForLatestGeneration = true
                }
            }
        }
    }

    fun onConnectionLost() {
        freshForLatestGeneration = false
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
        var client: PlHttpClient? = null
        val timer = Timer("journal-version-timeout", true)
        return try {
            client = openClient()
            val c = client
            val watchdog = object : TimerTask() {
                override fun run() {
                    try {
                        (c as? Closeable)?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
            timer.schedule(watchdog, boundMillis)
            try {
                val version = fetchJournalVersion(c)
                watchdog.cancel()
                version
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            timer.cancel()
            try {
                (client as? Closeable)?.close()
            } catch (_: Exception) {
            }
        }
    }
}
