// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.obsidian

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.work.*
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.obsidian.*
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.PayloadRef
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealState
import app.solstone.platform.persistence.room.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.*

const val OBSIDIAN_WORK_NAME = "solstone-obsidian-check"
object ObsidianRuntimes { @Volatile var current: ObsidianSession? = null }
interface ObsidianWorkerHost { fun obsidianSessionForWork(): ObsidianSession? }

class ObsidianCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val session = ObsidianRuntimes.current ?: (applicationContext as? ObsidianWorkerHost)?.obsidianSessionForWork()
            ?: return Result.retry()
        return if (session.runCheckBlocking { isStopped }) Result.success() else Result.retry()
    }
}

data class ObsidianWarning(val vault: String, val device: String?, val lastSynced: String, val token: String)
data class ObsidianUi(
    val configured: Boolean = false, val enabled: Boolean = false, val paused: Boolean = false,
    val settingUp: Boolean = false, val vault: String? = null, val lastChecked: Long? = null,
    val pending: Int = 0, val initialDone: Int = 0, val initialTotal: Int = 0,
    val tooLarge: Int = 0, val unavailable: Int = 0, val fault: String? = null,
    val capability: Boolean? = null, val warning: ObsidianWarning? = null,
    val soFar: Long = 0, val started: Long? = null, val lastTaken: Long? = null,
)

/** One actor owns the private ledger, provider scans and the handoff to the normal spool. */
class ObsidianSession(
    context: Context, private val database: SolstonePersistenceDatabase, private val spoolDir: Path,
    private val wishOn: () -> Boolean, private val setEnabled: (Boolean) -> Boolean,
    private val ownerPaused: () -> Boolean, private val onSealed: () -> Unit,
    private val onChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val ledger = SqliteObsidianLedger(appContext)
    private val reader = SafObsidianVault(appContext.contentResolver)
    private val engine = ObsidianEngine(ledger, ZoneId.systemDefault())
    private val actor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "obsidian-read") }.apply { removeOnCancelPolicy = true }
    private var foreground = false
    private var timer: ScheduledFuture<*>? = null
    @Volatile private var operation: CancellationSignal? = null
    @Volatile private var listener: () -> Unit = {}
    @Volatile private var pauseRequested = false
    @Volatile private var offRequested = false
    @Volatile var unspooledPending = 0; private set
    @Volatile var ui = ObsidianUi(); private set
    private var capability: Boolean? = null
    private var lastPairing: String? = null
    private var warning: ObsidianWarning? = null
    private var fault: String? = null
    private var lastDuration = 0L
    private var globalPause = false
    private val global = SqliteObsidianLedger.GLOBAL_ROOT
    init { ObsidianRuntimes.current = this; submit { refresh() } }
    fun setListener(next: () -> Unit) { listener = next }
    fun hasAccess(): Boolean = ui.configured && ui.fault != "access_lost"
    fun isPaused(): Boolean = ui.paused
    fun allowsDelivery(): Boolean = !pauseRequested && !offRequested && !ui.paused && !ownerPaused() && wishOn()
    fun onProcessStart() = submit { schedule(); refresh() }
    fun onForeground(value: Boolean) = submit {
        foreground = value
        timer?.cancel(false); timer = null
        if (value) checkNow()
        scheduleTimer()
    }
    fun onPauseChanged() = submit { globalPause = ownerPaused(); refresh() }
    fun check() = submit { checkNow(); scheduleTimer() }
    fun choose(uri: Uri) {
        operation?.cancel()
        submit {
            ui = ui.copy(settingUp = true, fault = null); changed()
            val alreadyGranted = reader.hasReadGrant(uri)
            var published = false
            try {
                val chosen = reader.validate(uri, takeGrant = true)
                val current = ledger.get(global, "settings", "root")
                if (wishOn() && current != null && current != chosen.root) throw VaultAccessException("turn_off_first")
                ledger.transaction {
                    ledger.put(chosen.root, "meta", "uri", uri.toString())
                    ledger.put(chosen.root, "meta", "vault", chosen.name)
                    ledger.put(global, "settings", "root", chosen.root)
                    if (ledger.get(global, "settings", "started") == null) ledger.put(global, "settings", "started", System.currentTimeMillis().toString())
                    ledger.remove(global, "settings", "paused")
                }
                published = true
                if (!setEnabled(true)) throw VaultAccessException("wish_not_saved")
                if (current != null && current != chosen.root) {
                    ledger.get(current, "meta", "uri")?.let { old ->
                        runCatching { appContext.contentResolver.releasePersistableUriPermission(Uri.parse(old), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    }
                }
                offRequested = false; pauseRequested = false
                fault = null; warning = null; schedule(); checkNow(); scheduleTimer()
            } catch (e: Exception) {
                if (!alreadyGranted && !published) runCatching { appContext.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                recordFailure(e)
            }
        }
    }
    fun turnOn() = submit {
        val root = ledger.get(global, "settings", "root") ?: return@submit
        val uri = ledger.get(root, "meta", "uri") ?: return@submit
        reader.validate(Uri.parse(uri))
        if (!setEnabled(true)) throw VaultAccessException("wish_not_saved")
        offRequested = false; pauseRequested = false
        fault = null; warning = null; schedule(); checkNow(); scheduleTimer()
    }
    fun turnOff() {
        offRequested = true
        operation?.cancel()
        submit {
            if (!setEnabled(false)) { fault = "wish_not_saved"; refresh(); return@submit }
            warning = null; fault = null; timer?.cancel(false); timer = null
            WorkManager.getInstance(appContext).cancelUniqueWork(OBSIDIAN_WORK_NAME)
            refresh()
        }
    }
    fun pause() {
        pauseRequested = true
        submit { ledger.put(global, "settings", "paused", "1"); refresh() }
    }
    fun resume() = submit {
        ledger.remove(global, "settings", "paused"); pauseRequested = false; globalPause = ownerPaused()
        checkNow(); onSealed(); scheduleTimer()
    }
    fun continueWarning() = submit {
        warning?.let { ledger.put(global, "authorizations", it.token, "1") }
        warning = null; refresh(); onSealed()
    }
    fun cancelWarning() = turnOff()

    /** Invoked by the normal authenticated sender, only after mark confirmation. */
    fun prepareDelivery(client: PlHttpClient, pairingKey: String, pairingCurrent: () -> Boolean): Boolean = blocking {
        if (lastPairing != pairingKey) { lastPairing = pairingKey; warning = null }
        capability = null
        refresh()
        if (!allowsDelivery() || !pairingCurrent()) return@blocking false
        val status = client.request("GET", "/api/system/status", mapOf("Cache-Control" to "no-cache"), null, maxResponseBytes = 64 * 1024)
        if (status.status != 200) { capability = null; refresh(); return@blocking false }
        capability = parseObsidianCapability(status.status, status.bodyText())
        if (capability != true || !pairingCurrent()) { refresh(); return@blocking false }
        val names = ledger.batches().map { it.vault }.distinct()
        val pending = names.filter { name -> ledger.get(global, "authorizations", authorizationKey(pairingKey, name)) == null }
        if (pending.isNotEmpty()) {
            val response = client.request("GET", "/app/devices/ingest/obsidian/vaults", emptyMap(), null, maxResponseBytes = 64 * 1024)
            val listed = parseOtherObsidianVaults(response.status, response.bodyText()) ?: return@blocking false
            if (!pairingCurrent()) return@blocking false
            for (name in pending) {
                val token = authorizationKey(pairingKey, name)
                val match = listed.firstOrNull { it.vault == name }
                if (match != null) {
                    warning = ObsidianWarning(name, match.device, match.lastSyncedAt, token)
                    refresh(); return@blocking false
                }
                ledger.put(global, "authorizations", token, "1")
            }
        }
        warning = null; refresh(); pairingCurrent() && allowsDelivery()
    } ?: false

    private fun authorizationKey(pairing: String, vault: String) = obsidianHash((pairing + "\n" + vault).toByteArray(Charsets.UTF_8))
    fun runCheckBlocking(stopped: () -> Boolean): Boolean {
        val workSignal = CancellationSignal()
        val result = actor.submit<Boolean> { try { checkNow(stopped, workSignal); true } catch (e: Exception) { recordFailure(e); false } }
        while (true) {
            if (stopped() || Thread.currentThread().isInterrupted) {
                workSignal.cancel(); result.cancel(true); return false
            }
            try { return result.get(500, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { /* WorkManager owns cancellation. */ }
            catch (_: ExecutionException) { return false }
            catch (_: InterruptedException) { workSignal.cancel(); result.cancel(true); Thread.currentThread().interrupt(); return false }
        }
    }
    private fun <T> blocking(action: () -> T): T? {
        val result = actor.submit(Callable { action() })
        return try { result.get(60, TimeUnit.SECONDS) }
        catch (_: TimeoutException) { result.cancel(true); null }
        catch (e: ExecutionException) {
            android.util.Log.w("ObsidianSource", "delivery check failed: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}")
            null
        }
        catch (_: InterruptedException) { result.cancel(true); Thread.currentThread().interrupt(); null }
    }
    private fun checkNow(stopped: () -> Boolean = { false }, signal: CancellationSignal = CancellationSignal()) {
        if (!wishOn()) { refresh(); return }
        val root = ledger.get(global, "settings", "root") ?: return
        val uri = ledger.get(root, "meta", "uri") ?: return
        operation = signal
        val began = SystemClock.elapsedRealtime()
        try {
            val vault = reader.validate(Uri.parse(uri))
            val entries = reader.walk(vault, signal)
            val now = System.currentTimeMillis()
            val scan = engine.scan(root, vault.name, entries, now, { reader.read(vault, it, signal) },
                cancelled = { if (stopped() || Thread.currentThread().isInterrupted) signal.cancel(); signal.isCanceled },
                progress = { done, total -> if (done % 100 == 0) { ui = ui.copy(settingUp = ui.lastChecked == null, initialTotal = total); changed() } })
            signal.throwIfCanceled()
            engine.closeDueWindows(System.currentTimeMillis()) { stopped() || signal.isCanceled }
            stageBatches()
            fault = if (scan.unavailable > 0) "some_notes_unavailable" else null
            refresh()
        } finally {
            operation = null; lastDuration = SystemClock.elapsedRealtime() - began
        }
    }
    private fun stageBatches() {
        val dao = database.segmentDao()
        var staged = false
        for (batch in ledger.batches()) {
            val row = dao.segmentsByDay(batch.keys.day).firstOrNull { r ->
                r.stream == "obsidian" && dao.filesBySegmentId(r.id).any { it.name == batch.name }
            }
            if (row?.state == QueueState.UPLOADED || row?.state == QueueState.EVICTED || row?.lastError == "removed_in_journal") {
                ledger.transaction {
                    val sent = ledger.get(batch.root, "meta", "initial_sent")?.toInt() ?: 0
                    ledger.put(batch.root, "meta", "initial_sent", (sent + batch.initialNotes).toString())
                    ledger.retireBatch(batch.root, batch.name)
                }
                continue
            }
            if (row?.lastError == "obsidian_note_invalid") continue
            val payloadExists = row?.let { r -> Files.isRegularFile(spoolDir.resolve(r.day).resolve(r.stream).resolve(r.dirSegment).resolve(batch.name)) } ?: false
            if (row == null || !payloadExists) {
                val bytes = ledger.batchBytes(batch.root, batch.name) ?: error("Obsidian bytes unavailable")
                val segment = SealedSegment("obsidian", SegmentKey(batch.keys.day, batch.keys.segment), batch.keys,
                    listOf(SegmentPayload("obsidian", PayloadRef(batch.name, OBSIDIAN_MEDIA_TYPE, bytes.size.toLong(), batch.name.removePrefix("obsidian_").removeSuffix(".jsonl")),
                        batch.keys.startEpochMs, batch.keys.endEpochMs)), emptyList())
                val result = FileSpoolWriter(spoolDir, occupiedLeaves = { day, stream -> dao.occupiedDirSegments(day, stream) }).seal(segment,
                    object : PayloadBytesProvider { override fun open(payload: SegmentPayload) = ByteArrayInputStream(bytes) })
                check(result.state == SealState.SEALED) { "spool_not_saved" }
                RoomSealedSegmentSink(dao).persistSealed(segment, result, System.currentTimeMillis())
                staged = true
            }
            ledger.markSpooled(batch.root, batch.name)
        }
        if (staged) onSealed()
    }
    private fun schedule() {
        if (!wishOn() || ledger.get(global, "settings", "root") == null) return
        val work = PeriodicWorkRequest.Builder(ObsidianCheckWorker::class.java, PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(OBSIDIAN_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, work)
    }
    private fun scheduleTimer() {
        timer?.cancel(false); timer = null
        if (!foreground || !wishOn()) return
        val delay = if (lastDuration <= 600) 60_000L else (lastDuration * 100).coerceAtMost(900_000L)
        timer = actor.schedule({ try { checkNow() } catch (e: Exception) { recordFailure(e) }; scheduleTimer() }, delay, TimeUnit.MILLISECONDS)
    }
    private fun refresh() {
        val root = ledger.get(global, "settings", "root")
        val roots = ledger.roots(); val batches = ledger.batches()
        fun meta(key: String): String? = root?.let { ledger.get(it, "meta", key) }
        globalPause = ownerPaused()
        unspooledPending = roots.sumOf { ledger.keys(it, "pending").size } + batches.filter { !it.spooled }.sumOf { it.lines }
        val invalid = database.segmentDao().segmentsForDrain("obsidian").any { it.lastError == "obsidian_note_invalid" }
        ui = ObsidianUi(configured = root != null, enabled = wishOn(), paused = globalPause || ledger.get(global, "settings", "paused") == "1",
            vault = meta("vault"), lastChecked = meta("last_scan")?.toLong(),
            pending = roots.sumOf { ledger.keys(it, "pending").size } + batches.sumOf { it.lines },
            initialDone = meta("initial_sent")?.toInt() ?: 0, initialTotal = meta("initial_total")?.toInt() ?: 0,
            tooLarge = meta("too_large")?.toInt() ?: 0, unavailable = meta("unavailable")?.toInt() ?: 0,
            fault = fault ?: if (invalid) "invalid_note" else null, capability = capability, warning = warning,
            soFar = roots.sumOf { ledger.get(it, "meta", "notes_total")?.toLong() ?: 0 },
            started = ledger.get(global, "settings", "started")?.toLong(),
            lastTaken = roots.mapNotNull { ledger.get(it, "meta", "last_taken")?.toLong() }.maxOrNull())
        changed()
    }
    private fun recordFailure(error: Exception) {
        if (error is android.os.OperationCanceledException || error is InterruptedException || error is CancellationException) return
        android.util.Log.w("ObsidianSource", "check failed: ${error.javaClass.simpleName}")
        fault = when (error) {
            is VaultAccessException -> error.message
            is SecurityException -> "access_lost"
            else -> "read_failed"
        }
        refresh()
    }
    private fun submit(action: () -> Unit) { actor.execute { try { action() } catch (e: Exception) { recordFailure(e) } } }
    private fun changed() { onChanged(); listener() }
    fun close() { operation?.cancel(); timer?.cancel(false); actor.shutdownNow(); ledger.close(); if (ObsidianRuntimes.current === this) ObsidianRuntimes.current = null }
}
