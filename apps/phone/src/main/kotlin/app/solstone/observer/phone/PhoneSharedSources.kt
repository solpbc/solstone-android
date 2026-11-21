// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import android.util.Log
import app.solstone.core.identity.*
import app.solstone.core.model.IdentityState
import app.solstone.core.model.SourceState
import app.solstone.core.pl.*
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import app.solstone.platform.calendar.CalendarRuntimes
import app.solstone.platform.obsidian.ObsidianRuntimes
import app.solstone.platform.screenshots.ScreenshotsRuntimes
import app.solstone.platform.pl.transport.conscrypt.openAuthenticatedLeaseClient
import app.solstone.platform.work.syncStores
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.util.concurrent.Executors

internal fun declaration(
    model: SourcesReadModel,
    watchState: String?,
    obsidian: app.solstone.platform.obsidian.ObsidianUi? = ObsidianRuntimes.current?.ui,
    calendar: app.solstone.platform.calendar.CalendarUiState? = CalendarRuntimes.current?.ui,
    shots: app.solstone.platform.screenshots.ScreenshotUi? = ScreenshotsRuntimes.current?.ui,
): String {
    val rows = model.sources.mapNotNull { source ->
        if (source.wish != SourceWish.On) return@mapNotNull null
        when (source.sourceId) {
            "obsidian" -> if (obsidian?.configured != true || obsidian.settingUp || obsidian.warning != null) return@mapNotNull null
            "calendar" -> if (calendar?.enabled != true && calendar?.resume != true) return@mapNotNull null
            "screenshots" -> if (shots?.confirmed != true || shots.reconfirm) return@mapNotNull null
            "audio", "location", "camera", "screen" -> Unit
            else -> return@mapNotNull null
        }
        val state = when (source.state) {
            SourceState.ON -> "on"
            SourceState.PAUSED -> "paused"
            SourceState.NEEDS_ATTENTION -> "needs_attention"
            else -> return@mapNotNull null
        }
        buildMap<String, String> {
            put("source", source.sourceId)
            put("state", state)
            if (source.sourceId == "obsidian") obsidian?.vault?.let { put("detail", it) }
        }
    }.toMutableList()
    if (watchState != null) rows += mapOf("source" to "watch", "state" to watchState)
    return toJson(rows.sortedBy { it["source"] })
}

/** Only edge events queue network work. A single writer samples the latest complete declaration. */
internal class PhoneSharedSources(
    private val disk: java.io.File,
    private val authorized: () -> PairingGeneration?,
    private val open: (PairingGeneration) -> PlHttpClient,
    private val refused: (PairingGeneration) -> Unit,
    private val worker: java.util.concurrent.Executor = Executors.newSingleThreadExecutor(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.d("SharedSources", it) },
) {
    val known = MutableStateFlow<Pair<String, KnownSources>?>(null)
    private var pairing: PairingGeneration? = null
    private var epoch = 0L
    private var online = false
    private var desired: String? = null
    private var acknowledged: String? = null
    private var rejected: String? = null
    private var retryAt: Long? = null
    private var retryDelay = 5_000L
    private var supported: Boolean? = null
    private var fetched = 0L
    private var discover = false
    private var forceList = false
    private var queued = false
    private var running = false

    fun followPairing() {
        synchronized(this) {
            val current = authorized()
            if (pairing == current && epoch != 0L) return
            if (epoch != 0L) {
                desired = null
                online = false
            }
            epoch++
            pairing = current
            known.value = null
            acknowledged = null
            supported = null
            rejected = null
            clearRetry()
            fetched = 0L
            // Only process startup may recover a snapshot. Every later pairing transition is a wipe.
            if (epoch == 1L && current != null && disk.exists()) {
                runCatching {
                    val record = parseJson(disk.readText()) as Map<*, *>
                    require(record["pairing"] == current.clientCertFingerprint)
                    val restored = KnownSources.parse(record["snapshot"] as String)
                    fetched = (record["fetched"] as Number).toLong()
                    known.value = current.clientCertFingerprint to restored
                }.onFailure { disk.delete() }
            } else disk.delete()
            discover = true
            forceList = false
            schedule()
        }
    }

    @Synchronized fun update(connected: Boolean, declaration: String?) {
        val reconnect = connected && !online
        val changed = declaration != null && desired != declaration
        online = connected
        if (declaration != null) desired = declaration
        if (changed) rejected = null
        if (reconnect) {
            acknowledged = null
            discover = true
        }
        // Existing status notifications can retry a failed exchange without a new polling job.
        val retryDue = retryAt?.let { connected && clock() >= it } == true
        if (reconnect || changed || retryDue) schedule()
    }

    @Synchronized fun refresh(detail: Boolean) {
        if (!online) {
            if (!detail && clock() - fetched !in 0..300_000L) forceList = true
            return
        }
        if (!detail && clock() - fetched in 0..300_000L && (desired == acknowledged || desired == rejected) && retryAt == null) return
        discover = true
        forceList = true
        schedule()
    }

    private fun clearRetry() {
        retryAt = null
        retryDelay = 5_000L
    }

    @Synchronized private fun defer(work: Work) {
        if (epoch != work.epoch) return
        discover = true
        forceList = forceList || work.force
        retryAt = clock() + retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(300_000L)
    }

    private fun schedule() {
        if (!online || pairing == null || desired == null) return
        queued = true
        if (running) return
        running = true
        worker.execute(::drain)
    }

    private data class Work(val pairing: PairingGeneration, val epoch: Long, val body: String, val status: Boolean, val force: Boolean)

    private fun drain() {
        while (true) {
            val work = synchronized(this) {
                val id = pairing
                val body = desired
                if (!queued || !online || id == null || body == null) {
                    running = false
                    return
                }
                queued = false
                retryAt = null
                Work(id, epoch, body, discover || supported == null, forceList).also {
                    discover = false
                    forceList = false
                }
            }
            try {
                exchange(work)
            } catch (e: Exception) {
                defer(work)
                log("refresh deferred (${e.javaClass.simpleName})")
            }
        }
    }

    private fun exchange(w: Work) {
        fun credentialCurrent() = authorized() == w.pairing && synchronized(this) { epoch == w.epoch }
        fun current() = credentialCurrent() && synchronized(this) { online }
        fun accept(action: () -> Unit) {
            if (current()) synchronized(this) { if (epoch == w.epoch) action() }
        }
        fun handle(status: Int): Boolean {
            when (status) {
                403 -> if (credentialCurrent()) {
                    refused(w.pairing)
                    followPairing()
                }
                404 -> accept { unsupported() }
                400 -> log("journal rejected the source request (400)")
                in 500..599 -> defer(w)
            }
            return status in 200..299 && current()
        }
        if (!current() || synchronized(this) { !w.status && (supported == false || acknowledged == w.body || rejected == w.body) }) return
        val client = open(w.pairing)
        try {
            if (!current()) return
            var revision: String? = null
            if (w.status) {
                val status = readSystemStatus(client, w.pairing.clientCertFingerprint)
                if (!handle(status.status)) return
                when (parseJournalCapability(status.status, status.bodyText(), "shared_sources")) {
                    false -> { accept { unsupported() }; return }
                    null -> { defer(w); return }
                    true -> accept { supported = true }
                }
                revision = (parseJson(status.bodyText()) as Map<*, *>)["sources_rev"] as? String
            }
            if (!current()) return
            if (synchronized(this) { acknowledged != w.body && rejected != w.body }) {
                val put = client.request("PUT", "/app/network/api/clients/self/sources", mapOf("Content-Type" to "application/json"), w.body.encodeToByteArray())
                if (put.status == 400) {
                    handle(put.status)
                    accept { rejected = w.body }
                } else {
                    if (!handle(put.status)) return
                    accept { acknowledged = w.body }
                }
            }
            if (!w.status || !current() || (!w.force && revision != null && known.value?.second?.revision == revision)) {
                accept { clearRetry() }
                return
            }
            val reply = client.request("GET", "/app/network/api/sources", emptyMap(), null)
            if (!handle(reply.status)) return
            val raw = reply.bodyText()
            val parsed = KnownSources.parse(raw)
            accept {
                val now = clock()
                val data = toJson(mapOf("pairing" to w.pairing.clientCertFingerprint, "fetched" to now, "snapshot" to raw))
                atomicWriteOwnerOnly(disk, data.encodeToByteArray())
                known.value = w.pairing.clientCertFingerprint to parsed
                fetched = now
                clearRetry()
            }
        } finally {
            (client as? java.io.Closeable)?.close()
        }
    }

    private fun unsupported() {
        clearRetry()
        supported = false
        acknowledged = null
        known.value = null
        fetched = 0
        disk.delete()
    }
}

/** A refused or unconfirmed credential cannot open a connection or restore a cached snapshot. */
internal fun phoneSharedSources(context: Context): PhoneSharedSources {
    val stores = syncStores(context)
    fun authorized(): PairingGeneration? {
        val snapshot = stores.publisher.currentSnapshot() as? PairingGraphSnapshot.Committed ?: return null
        return snapshot.pairing.takeIf {
            snapshot.home.state == IdentityState.PAIRED &&
                confirmedFor(JournalConfirmationPolicy.consults, stores.journalConfirmationStore, it.clientCertFingerprint) &&
                stores.pairingRefusalStore?.refusedFor(it) != true
        }
    }
    val registry = PhoneSharedSources(
        disk = context.noBackupFilesDir.resolve("shared-sources.json"),
        authorized = ::authorized,
        open = { expected ->
            openPairingClient(stores.publisher) { lease ->
                if (lease.snapshot.pairing != expected || authorized() != expected) throw IOException("pairing changed")
                openAuthenticatedLeaseClient(lease)
            }
        },
        refused = { stores.pairingRefusalStore?.markRefused(it) },
    )
    stores.publisher.subscribe { registry.followPairing() }
    stores.journalConfirmationStore.addListener(registry::followPairing)
    stores.pairingRefusalStore?.addListener(registry::followPairing)
    registry.followPairing()
    return registry
}
