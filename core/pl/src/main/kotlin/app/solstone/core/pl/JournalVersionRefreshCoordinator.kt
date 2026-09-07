// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class JournalVersionFreshness { NEVER_OBSERVED, LAST_KNOWN, CURRENT }

data class JournalVersionReading(val version: String?, val freshness: JournalVersionFreshness, val name: String? = null)

data class MetadataSnapshot(
    val instanceId: String,
    val caChainFingerprint: String,
    val clientCertFingerprint: String,
    val localDescriptionProvider: () -> ClientReportedDescription,
    val openClient: () -> PlHttpClient,
)

class JournalVersionRefreshCoordinator(
    private val store: JournalVersionStore,
    executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "journal-version-refresh").apply { isDaemon = true }
    },
    boundMillis: Long = 15_000L,
) : Closeable {
    private val job = CoalescingBoundedJob<MetadataSnapshot>(
        name = "journal-version-refresh",
        boundMillis = boundMillis,
        executor = executor,
    )
    @Volatile private var freshForLatestGeneration = false

    fun onUsableConnection(
        instanceId: String,
        caChainFingerprint: String,
        clientCertFingerprint: String = "",
        localDescriptionProvider: () -> ClientReportedDescription = { ClientReportedDescription() },
        openClient: () -> PlHttpClient,
    ) {
        val snapshot = MetadataSnapshot(
            instanceId = instanceId,
            caChainFingerprint = caChainFingerprint,
            clientCertFingerprint = clientCertFingerprint,
            localDescriptionProvider = localDescriptionProvider,
            openClient = openClient,
        )
        job.submit(snapshot) { snap, gen ->
            executeMetadataJob(snap, gen)
        }
    }

    private fun executeMetadataJob(snap: MetadataSnapshot, gen: Long) {
        var client: PlHttpClient? = null
        try {
            client = snap.openClient()
            when (val getResult = fetchClientsSelf(client)) {
                is ClientsSelfGetResult.Success -> {
                    val getResp = getResult.response
                    var finalName = getResp.journalName
                    var finalVersion = getResp.journalVersion
                    val localDesc = sanitizeReportedDescription(snap.localDescriptionProvider())

                    if (getResp.reported == null || getResp.reported != localDesc) {
                        val putReq = ClientsSelfPutRequest(
                            protocolVersion = 1,
                            expectedRevision = getResp.revision,
                            reported = localDesc,
                        )
                        when (val putResult = putClientsSelf(client, putReq)) {
                            is ClientsSelfPutResult.Success -> {
                                if (putResult.response.journalVersion != null) {
                                    finalVersion = putResult.response.journalVersion
                                    finalName = putResult.response.journalName
                                }
                            }
                            is ClientsSelfPutResult.Conflict -> {
                                // HTTP 409 CAS conflict: reread GET, resample now, retry at most once
                                val retryGet = fetchClientsSelf(client)
                                if (retryGet is ClientsSelfGetResult.Success) {
                                    val freshLocal = sanitizeReportedDescription(snap.localDescriptionProvider())
                                    if (retryGet.response.reported != freshLocal) {
                                        val retryPut = putClientsSelf(
                                            client,
                                            ClientsSelfPutRequest(1, retryGet.response.revision, freshLocal),
                                        )
                                        if (retryPut is ClientsSelfPutResult.Success && retryPut.response.journalVersion != null) {
                                            finalVersion = retryPut.response.journalVersion
                                            finalName = retryPut.response.journalName
                                        }
                                    } else {
                                        if (retryGet.response.journalVersion != null) {
                                            finalVersion = retryGet.response.journalVersion
                                            finalName = retryGet.response.journalName
                                        }
                                    }
                                }
                            }
                            is ClientsSelfPutResult.Failure -> {
                                // Keep GET response journal name/version
                            }
                        }
                    }

                    if (finalVersion != null) {
                        synchronized(this) {
                            if (gen == job.currentGeneration()) {
                                store.save(
                                    JournalVersionRecord(
                                        instanceId = snap.instanceId,
                                        caChainFingerprint = snap.caChainFingerprint,
                                        version = finalVersion,
                                        name = finalName,
                                    ),
                                )
                                freshForLatestGeneration = true
                            }
                        }
                    }
                }
                is ClientsSelfGetResult.NotFound -> {
                    // Fallback to legacy GET /api/system/status
                    val legacyVersion = fetchJournalVersion(client)
                    if (legacyVersion != null) {
                        synchronized(this) {
                            if (gen == job.currentGeneration()) {
                                store.save(
                                    JournalVersionRecord(
                                        instanceId = snap.instanceId,
                                        caChainFingerprint = snap.caChainFingerprint,
                                        version = legacyVersion,
                                        name = null,
                                    ),
                                )
                                freshForLatestGeneration = true
                            }
                        }
                    }
                }
                is ClientsSelfGetResult.Failure -> {
                    // Retain last-known metadata on failure/timeout
                }
            }
        } catch (_: Exception) {
            // Retain last-known metadata
        } finally {
            try {
                (client as? Closeable)?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun onConnectionLost() {
        synchronized(this) {
            job.bumpGeneration()
            freshForLatestGeneration = false
        }
    }

    fun onIdentityChanged() {
        synchronized(this) {
            job.bumpGeneration()
            freshForLatestGeneration = false
            store.clear()
        }
    }

    fun onPairingChanged() {
        synchronized(this) {
            job.bumpGeneration()
            freshForLatestGeneration = false
        }
    }

    fun currentReading(instanceId: String, caChainFingerprint: String): JournalVersionReading {
        val record = store.load()
        return when {
            record == null || record.instanceId != instanceId || record.caChainFingerprint != caChainFingerprint ->
                JournalVersionReading(null, JournalVersionFreshness.NEVER_OBSERVED, null)
            freshForLatestGeneration -> JournalVersionReading(record.version, JournalVersionFreshness.CURRENT, record.name)
            else -> JournalVersionReading(record.version, JournalVersionFreshness.LAST_KNOWN, record.name)
        }
    }

    override fun close() {
        job.close()
    }
}
