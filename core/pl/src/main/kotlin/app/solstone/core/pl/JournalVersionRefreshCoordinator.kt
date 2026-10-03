// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalVersionRecord
import app.solstone.core.identity.JournalVersionStore
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class JournalVersionFreshness { NEVER_OBSERVED, LAST_KNOWN, CURRENT }

data class JournalVersionReading(
    val version: String?,
    val freshness: JournalVersionFreshness,
    val name: String? = null,
    val os: String? = null,
    val osVersion: String? = null,
    val arch: String? = null,
    val build: String? = null,
    val versionSeenAt: Long? = null,
)

data class MetadataSnapshot(
    val instanceId: String,
    val caChainFingerprint: String,
    val clientCertFingerprint: String,
    val localDescriptionProvider: (() -> ClientReportedDescription)? = null,
    val pairingMatches: () -> Boolean = { true },
    val openClient: () -> PlHttpClient,
)

private data class PendingAbout(
    val aboutGeneration: Long,
    val metadataGeneration: Long,
    val instanceId: String,
    val caChainFingerprint: String,
    val pairingMatches: () -> Boolean,
    val about: JournalAbout,
)

class JournalVersionRefreshCoordinator(
    private val store: JournalVersionStore,
    executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "journal-version-refresh").apply { isDaemon = true }
    },
    boundMillis: Long = 15_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val job = CoalescingBoundedJob<MetadataSnapshot>(
        name = "journal-version-refresh",
        boundMillis = boundMillis,
        executor = executor,
    )
    private val aboutJob = CoalescingBoundedJob<MetadataSnapshot>(
        name = "journal-about-refresh",
        boundMillis = boundMillis,
        executor = executor,
    )
    @Volatile private var freshForLatestGeneration = false
    private var acceptedMetadataGeneration: Long? = null
    private var pendingAbout: PendingAbout? = null

    fun onUsableConnection(
        instanceId: String,
        caChainFingerprint: String,
        clientCertFingerprint: String = "",
        localDescriptionProvider: (() -> ClientReportedDescription)? = null,
        pairingMatches: () -> Boolean = { true },
        openClient: () -> PlHttpClient,
    ) {
        val snapshot = MetadataSnapshot(
            instanceId = instanceId,
            caChainFingerprint = caChainFingerprint,
            clientCertFingerprint = clientCertFingerprint,
            localDescriptionProvider = localDescriptionProvider,
            pairingMatches = pairingMatches,
            openClient = openClient,
        )
        synchronized(this) {
            acceptedMetadataGeneration = null
            pendingAbout = null
        }
        job.submit(snapshot) { snap, gen ->
            executeMetadataJob(snap, gen)
        }
        aboutJob.submit(snapshot) { snap, gen ->
            executeAboutJob(snap, gen)
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
                    val provider = snap.localDescriptionProvider

                    if (provider != null) {
                        val localDesc = sanitizeReportedDescription(provider())
                        if (getResp.reported == null || getResp.reported != localDesc) {
                            if (gen == job.currentGeneration() && snap.pairingMatches()) {
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
                                            val freshLocal = sanitizeReportedDescription(provider())
                                            if (retryGet.response.reported != freshLocal) {
                                                if (gen == job.currentGeneration() && snap.pairingMatches()) {
                                                    val retryPut = putClientsSelf(
                                                        client,
                                                        ClientsSelfPutRequest(1, retryGet.response.revision, freshLocal),
                                                    )
                                                    if (retryPut is ClientsSelfPutResult.Success && retryPut.response.journalVersion != null) {
                                                        finalVersion = retryPut.response.journalVersion
                                                        finalName = retryPut.response.journalName
                                                    }
                                                }
                                            } else if (retryGet.response.journalVersion != null) {
                                                finalVersion = retryGet.response.journalVersion
                                                finalName = retryGet.response.journalName
                                            }
                                        }
                                    }
                                    is ClientsSelfPutResult.Failure -> {
                                        // Keep GET response journal name/version
                                    }
                                }
                            }
                        }
                    }

                    if (finalVersion != null) {
                        synchronized(this) {
                            if (gen == job.currentGeneration() && snap.pairingMatches()) {
                                saveAcceptedMetadata(snap, gen, finalVersion, finalName)
                            }
                        }
                    }
                }
                is ClientsSelfGetResult.NotFound -> {
                    // Fallback to legacy GET /api/system/status
                    val legacyVersion = fetchJournalVersion(client)
                    if (legacyVersion != null) {
                        synchronized(this) {
                            if (gen == job.currentGeneration() && snap.pairingMatches()) {
                                val existingName = store.load()?.takeIf { it.instanceId == snap.instanceId }?.name
                                saveAcceptedMetadata(snap, gen, legacyVersion, existingName)
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
            closeClient(client)
        }
    }

    private fun saveAcceptedMetadata(snap: MetadataSnapshot, gen: Long, version: String, name: String?) {
        val existing = store.load()?.takeIf {
            it.instanceId == snap.instanceId && it.caChainFingerprint == snap.caChainFingerprint
        }
        val sameVersion = existing != null && stripVersion(existing.version) == stripVersion(version)
        store.save(
            JournalVersionRecord(
                instanceId = snap.instanceId,
                caChainFingerprint = snap.caChainFingerprint,
                version = version,
                name = name,
                os = existing?.takeIf { sameVersion }?.os,
                osVersion = existing?.takeIf { sameVersion }?.osVersion,
                arch = existing?.takeIf { sameVersion }?.arch,
                build = existing?.takeIf { sameVersion }?.build,
                versionSeenAt = clock(),
                hostFactsAt = existing?.takeIf { sameVersion }?.hostFactsAt,
            ),
        )
        freshForLatestGeneration = true
        acceptedMetadataGeneration = gen
        applyPendingAbout(gen)
    }

    private fun executeAboutJob(snap: MetadataSnapshot, aboutGen: Long) {
        var client: PlHttpClient? = null
        try {
            client = snap.openClient()
            val result = fetchJournalAbout(client)
            if (result is JournalAboutResult.Accepted) {
                synchronized(this) {
                    if (aboutGen != aboutJob.currentGeneration()) return
                    val metadataGen = job.currentGeneration()
                    val pending = PendingAbout(
                        aboutGeneration = aboutGen,
                        metadataGeneration = metadataGen,
                        instanceId = snap.instanceId,
                        caChainFingerprint = snap.caChainFingerprint,
                        pairingMatches = snap.pairingMatches,
                        about = result.about,
                    )
                    if (acceptedMetadataGeneration == metadataGen) {
                        commitAbout(pending)
                    } else {
                        pendingAbout = pending
                    }
                }
            }
        } catch (_: Exception) {
            // About is optional and never changes metadata freshness.
        } finally {
            closeClient(client)
        }
    }

    private fun applyPendingAbout(metadataGen: Long) {
        val pending = pendingAbout ?: return
        pendingAbout = null
        if (pending.metadataGeneration != metadataGen || pending.aboutGeneration != aboutJob.currentGeneration()) return
        commitAbout(pending)
    }

    private fun commitAbout(pending: PendingAbout) {
        val metadataGen = pending.metadataGeneration
        if (pending.aboutGeneration != aboutJob.currentGeneration() ||
            metadataGen != job.currentGeneration() ||
            acceptedMetadataGeneration != metadataGen ||
            !pending.pairingMatches()
        ) return

        val current = store.load() ?: return
        if (current.instanceId != pending.instanceId ||
            current.caChainFingerprint != pending.caChainFingerprint ||
            stripVersion(pending.about.version) != stripVersion(current.version)
        ) return

        store.save(
            current.copy(
                os = pending.about.os,
                osVersion = pending.about.osVersion,
                arch = pending.about.arch,
                build = pending.about.build,
                hostFactsAt = clock(),
            ),
        )
    }

    private fun stripVersion(version: String): String = version.trimStart { it == 'v' }

    private fun closeClient(client: PlHttpClient?) {
        try {
            (client as? Closeable)?.close()
        } catch (_: Exception) {
        }
    }

    fun onConnectionLost() {
        synchronized(this) {
            job.bumpGeneration()
            aboutJob.bumpGeneration()
            acceptedMetadataGeneration = null
            pendingAbout = null
            freshForLatestGeneration = false
        }
    }

    fun onIdentityChanged() {
        synchronized(this) {
            job.bumpGeneration()
            aboutJob.bumpGeneration()
            acceptedMetadataGeneration = null
            pendingAbout = null
            freshForLatestGeneration = false
            store.clear()
        }
    }

    fun onPairingChanged() {
        synchronized(this) {
            job.bumpGeneration()
            aboutJob.bumpGeneration()
            acceptedMetadataGeneration = null
            pendingAbout = null
            freshForLatestGeneration = false
        }
    }

    fun currentReading(instanceId: String, caChainFingerprint: String): JournalVersionReading {
        val record = store.load()
        return when {
            record == null || record.instanceId != instanceId || record.caChainFingerprint != caChainFingerprint ->
                JournalVersionReading(null, JournalVersionFreshness.NEVER_OBSERVED, null)
            freshForLatestGeneration -> JournalVersionReading(
                version = record.version,
                freshness = JournalVersionFreshness.CURRENT,
                name = record.name,
                os = record.os,
                osVersion = record.osVersion,
                arch = record.arch,
                build = record.build,
                versionSeenAt = record.versionSeenAt,
            )
            else -> JournalVersionReading(
                version = record.version,
                freshness = JournalVersionFreshness.LAST_KNOWN,
                name = record.name,
                os = record.os,
                osVersion = record.osVersion,
                arch = record.arch,
                build = record.build,
                versionSeenAt = record.versionSeenAt,
            )
        }
    }

    override fun close() {
        job.close()
        aboutJob.close()
    }
}
