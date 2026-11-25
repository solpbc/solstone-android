// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.obsidian

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.work.WorkerParameters
import app.solstone.core.model.SegmentKey
import app.solstone.core.obsidian.*
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.fetchJournalCapability
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.PayloadRef
import app.solstone.platform.foldersync.FolderCheckWorker
import app.solstone.platform.foldersync.FolderChecks
import app.solstone.platform.foldersync.FolderDelivery
import app.solstone.platform.foldersync.FolderRuntime
import app.solstone.platform.foldersync.IncompleteFolderListing
import app.solstone.platform.foldersync.journalHasSegment
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.CancellationException

const val OBSIDIAN_WORK_NAME = "solstone-obsidian-check"
object ObsidianRuntimes : FolderRuntime<ObsidianSession>()
interface ObsidianWorkerHost { fun obsidianSessionForWork(): ObsidianSession? }

// Keep this class name for WorkManager requests persisted by older installs.
class ObsidianCheckWorker(context: Context, params: WorkerParameters) : FolderCheckWorker(context, params) {
    override fun runCheck(stopped: () -> Boolean): Boolean =
        (ObsidianRuntimes.current ?: (applicationContext as? ObsidianWorkerHost)?.obsidianSessionForWork())
            ?.runCheckBlocking(stopped) == true
}

data class ObsidianWarning(val vault: String, val device: String?, val lastSynced: String, val token: String)
data class ObsidianUi(
    val configured: Boolean = false, val enabled: Boolean = false, val paused: Boolean = false,
    val settingUp: Boolean = false, val vault: String? = null, val lastChecked: Long? = null,
    val pending: Int = 0, val initialDone: Int = 0, val initialTotal: Int = 0,
    val tooLarge: Int = 0, val unavailable: Int = 0, val fault: String? = null,
    val capability: Boolean? = null, val warning: ObsidianWarning? = null,
)

/** One actor owns the private ledger, provider scans and the handoff to the normal spool. */
class ObsidianSession(
    context: Context, private val database: SolstonePersistenceDatabase, spoolDir: Path,
    private val wishOn: () -> Boolean, private val setEnabled: (Boolean) -> Boolean,
    private val ownerStopWitness: () -> String?, private val onSealed: () -> Unit,
    private val onChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val ledger = SqliteObsidianLedger(appContext)
    private val reader = SafObsidianVault(appContext.contentResolver)
    private val engine = ObsidianEngine(ledger, ZoneId.systemDefault())
    private val delivery = FolderDelivery(spoolDir, database)
    @Volatile private var listener: () -> Unit = {}
    @Volatile private var pauseRequested = false
    @Volatile private var offRequested = false
    @Volatile private var stopRequested = false
    @Volatile private var acknowledgedStop: String? = null
    @Volatile var unspooledPending = 0; private set
    @Volatile var ui = ObsidianUi(); private set
    private var capability: Boolean? = null
    private var warning: ObsidianWarning? = null
    private var fault: String? = null
    private var lastDuration = 0L
    private var globalPause = false
    private val global = SqliteObsidianLedger.GLOBAL_ROOT
    private val checks = FolderChecks(
        appContext, OBSIDIAN_WORK_NAME, ObsidianCheckWorker::class.java, "obsidian-read",
        check = ::checkNow, onFailure = ::recordFailure, backgroundWanted = ::backgroundWanted,
        foregroundDelayMs = { if (wishOn()) (lastDuration * 100).coerceIn(60_000L, 900_000L) else null },
    )
    init { ObsidianRuntimes.register(this); checks.submit { refresh() } }
    fun setListener(next: () -> Unit) { listener = next }
    fun hasAccess(): Boolean = ui.configured && ui.fault != "access_lost"
    fun isPaused(): Boolean = ui.paused || ownerStopHolds() || stopRequested
    private fun ownerStopHolds() = obsidianOwnerStopHolds(ownerStopWitness(), acknowledgedStop)
    fun allowsDelivery(): Boolean = !pauseRequested && !offRequested && !stopRequested && !ui.paused && !ownerStopHolds() && wishOn()
    fun onProcessStart() = checks.submit { applyCounters(); schedule(); refresh() }
    fun onForeground(value: Boolean) = checks.onForeground(value)
    fun onPauseChanged() {
        if (ownerStopWitness() == null) stopRequested = false
        checks.submit { refresh() }
    }
    fun onOwnerStopRequested(saved: Boolean) {
        stopRequested = true
        checks.submit { if (!saved) fault = "wish_not_saved"; refresh() }
    }
    private fun acknowledgeOwnerStop(token: String?) {
        if (token == null) ledger.remove(global, "settings", "owner_stop_ack")
        else ledger.put(global, "settings", "owner_stop_ack", token)
    }
    fun check() = checks.submit { checks.checkNow() }
    fun choose(uri: Uri) {
        val acceptedStop = ownerStopWitness()
        checks.cancelCheck()
        checks.submit {
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
                    ledger.remove(global, "settings", "paused")
                    acknowledgeOwnerStop(acceptedStop)
                }
                published = true
                if (!setEnabled(true)) throw VaultAccessException("wish_not_saved")
                if (current != null && current != chosen.root) {
                    ledger.get(current, "meta", "uri")?.let { old ->
                        runCatching { reader.release(Uri.parse(old)) }
                    }
                }
                offRequested = false; pauseRequested = false; stopRequested = false
                fault = null; warning = null; refresh(); schedule(); checks.checkNow()
            } catch (e: Exception) {
                if (!alreadyGranted && !published) runCatching { reader.release(uri) }
                recordFailure(e)
            }
        }
    }
    fun turnOn() {
        val acceptedStop = ownerStopWitness()
        checks.submit {
            val root = ledger.get(global, "settings", "root") ?: return@submit
            val uri = ledger.get(root, "meta", "uri") ?: return@submit
            reader.validate(Uri.parse(uri))
            if (!setEnabled(true)) throw VaultAccessException("wish_not_saved")
            ledger.transaction { ledger.remove(global, "settings", "paused"); acknowledgeOwnerStop(acceptedStop) }
            offRequested = false; pauseRequested = false; stopRequested = false
            fault = null; warning = null; refresh(); schedule(); checks.checkNow()
        }
    }
    fun turnOff() {
        offRequested = true
        checks.cancelCheck()
        checks.submit {
            if (!setEnabled(false)) { fault = "wish_not_saved"; refresh(); return@submit }
            warning = null; fault = null
            checks.stopScheduling()
            refresh()
        }
    }
    fun pause() {
        pauseRequested = true
        checks.submit { ledger.put(global, "settings", "paused", "1"); refresh() }
    }
    fun resume() {
        val acceptedStop = ownerStopWitness()
        checks.submit {
            ledger.transaction { ledger.remove(global, "settings", "paused"); acknowledgeOwnerStop(acceptedStop) }
            pauseRequested = false; stopRequested = false; refresh()
            checks.checkNow(); onSealed()
        }
    }
    fun continueWarning() = checks.submit {
        warning?.let { ledger.put(global, OBSIDIAN_APPROVALS, it.token, "1") }
        warning = null; refresh(); onSealed()
    }
    fun cancelWarning() = turnOff()

    /**
     * A new pairing: the next check sends the tree's current notes once, after the same-name check,
     * as a first turn-on does. The tree and its grant, the owner's pause, and what is already waiting
     * stay. Runs before the pairing is confirmed, so it is saved before anything can go to the new
     * journal. Returns once saved, or false if that did not happen in time.
     */
    fun restartFirstSetup(): Boolean {
        checks.cancelCheck()
        return checks.restartFirstSetup {
            // A batch the last journal took is settled first, so it does not count toward this
            // journal's first send.
            runCatching { stageBatches() }
            engine.firstSetupAgain(global)
            warning = null; capability = null
            refresh()
        }
    }

    /** Invoked by the normal authenticated sender, only after mark confirmation. */
    fun prepareDelivery(client: PlHttpClient, pairingCurrent: () -> Boolean, fingerprint: String = ""): Boolean = checks.blocking(
        60, onFailure = { android.util.Log.w("ObsidianSource", "delivery check failed: ${it.javaClass.simpleName}") },
    ) {
        capability = null
        refresh()
        if (!allowsDelivery() || !pairingCurrent()) return@blocking false
        capability = fetchJournalCapability(client, OBSIDIAN_CAPABILITY, fingerprint)
        if (capability != true || !pairingCurrent()) { refresh(); return@blocking false }
        val names = ledger.batches().map { it.vault }.distinct()
        val pending = names.filter { name -> ledger.get(global, OBSIDIAN_APPROVALS, obsidianApprovalKey(name)) == null }
        if (pending.isNotEmpty()) {
            val listed = fetchOtherObsidianVaults(client) ?: return@blocking false
            if (!pairingCurrent()) return@blocking false
            for (name in pending) {
                val token = obsidianApprovalKey(name)
                val match = listed.firstOrNull { it.vault == name }
                if (match != null) {
                    warning = ObsidianWarning(name, match.device, match.lastSyncedAt, token)
                    refresh(); return@blocking false
                }
                ledger.put(global, OBSIDIAN_APPROVALS, token, "1")
            }
        }
        warning = null; refresh(); pairingCurrent() && allowsDelivery()
    } ?: false

    fun runCheckBlocking(stopped: () -> Boolean): Boolean = checks.runFromWorker(stopped)

    private fun checkNow(signal: CancellationSignal) {
        if (!wishOn()) { refresh(); return }
        val root = ledger.get(global, "settings", "root") ?: return
        val uri = ledger.get(root, "meta", "uri") ?: return
        val began = SystemClock.elapsedRealtime()
        try {
            fun cancelled(): Boolean {
                if (Thread.currentThread().isInterrupted) signal.cancel()
                return signal.isCanceled
            }
            applyCounters(::cancelled)
            engine.closeDueWindows(System.currentTimeMillis(), ::cancelled)
            stageBatches()
            val vault = reader.validate(Uri.parse(uri), signal = signal)
            val entries = reader.walk(vault, signal)
            val stage = ObsidianReadStage(ledger, root)
            val needed = engine.entriesNeedingRead(root, entries)
            stage.fill(needed, { reader.read(vault, it, signal) }, System::currentTimeMillis,
                cancelled = ::cancelled,
                progress = { done, total -> if (done % 100 == 0) { ui = ui.copy(settingUp = ui.lastChecked == null, initialTotal = total); changed() } })
            signal.throwIfCanceled()
            val now = System.currentTimeMillis()
            val scan = engine.scan(root, vault.name, entries, now, stage::read,
                cancelled = ::cancelled,
                readStageToken = stage.token)
            applyCounters(::cancelled)
            stage.clearApplied()
            signal.throwIfCanceled()
            engine.closeDueWindows(System.currentTimeMillis(), ::cancelled)
            stageBatches()
            fault = if (scan.unavailable > 0) "some_notes_unavailable" else null
            refresh()
        } finally {
            lastDuration = SystemClock.elapsedRealtime() - began
        }
    }
    private fun applyCounters(stopped: () -> Boolean = { false }) {
        while (true) {
            if (stopped()) throw CancellationException("Obsidian counter apply cancelled")
            val ops = pendingObsidianCounters(ledger)
            if (ops.isEmpty()) return
            database.obsidianIntakeDao().apply(ops, System.currentTimeMillis()) { !stopped() }
            retireObsidianCounters(ledger, ops)
        }
    }
    private fun stageBatches() {
        var staged = false
        for (batch in ledger.batches()) {
            val row = delivery.receipt(batch.keys.day, "obsidian", batch.name)
            if (row.journalHasSegment() || row?.lastError == "removed_in_journal") {
                ledger.transaction {
                    val sent = ledger.get(batch.root, "meta", "initial_sent")?.toInt() ?: 0
                    ledger.put(batch.root, "meta", "initial_sent", (sent + batch.initialNotes).toString())
                    ledger.retireBatch(batch.root, batch.name)
                }
                continue
            }
            if (row?.lastError == "obsidian_note_invalid") continue
            if (row == null || !delivery.hasPayload(row, batch.name)) {
                val bytes = ledger.batchBytes(batch.root, batch.name) ?: error("Obsidian bytes unavailable")
                val segment = SealedSegment("obsidian", SegmentKey(batch.keys.day, batch.keys.segment), batch.keys,
                    listOf(SegmentPayload("obsidian", PayloadRef(batch.name, OBSIDIAN_MEDIA_TYPE, bytes.size.toLong(), batch.name.removePrefix("obsidian_").removeSuffix(".jsonl")),
                        batch.keys.startEpochMs, batch.keys.endEpochMs)), emptyList())
                check(delivery.seal(segment, bytes) != null) { "spool_not_saved" }
                staged = true
            }
            ledger.markSpooled(batch.root, batch.name)
        }
        if (staged) onSealed()
    }
    private fun backgroundWanted() = wishOn() && ledger.get(global, "settings", "root") != null

    private fun schedule() {
        if (backgroundWanted()) checks.scheduleBackground()
    }

    private fun refresh() {
        val root = ledger.get(global, "settings", "root")
        val roots = ledger.roots(); val batches = ledger.batches()
        fun meta(key: String): String? = root?.let { ledger.get(it, "meta", key) }
        acknowledgedStop = ledger.get(global, "settings", "owner_stop_ack")
        globalPause = ownerStopHolds() || stopRequested
        unspooledPending = roots.sumOf { ledger.keys(it, "pending").size } + batches.filter { !it.spooled }.sumOf { it.lines }
        val invalid = database.segmentDao().segmentsForDrain("obsidian").any { it.lastError == "obsidian_note_invalid" }
        ui = ObsidianUi(configured = root != null, enabled = wishOn(), paused = globalPause || ledger.get(global, "settings", "paused") == "1",
            vault = meta("vault"), lastChecked = meta("last_scan")?.toLong(),
            pending = roots.sumOf { ledger.keys(it, "pending").size } + batches.sumOf { it.lines },
            initialDone = meta("initial_sent")?.toInt() ?: 0, initialTotal = meta("initial_total")?.toInt() ?: 0,
            tooLarge = meta("too_large")?.toInt() ?: 0, unavailable = meta("unavailable")?.toInt() ?: 0,
            fault = fault ?: if (invalid) "invalid_note" else null, capability = capability, warning = warning,
)
        changed()
    }
    private fun recordFailure(error: Exception) {
        if (error is android.os.OperationCanceledException || error is InterruptedException || error is CancellationException) return
        android.util.Log.w("ObsidianSource", "check failed: ${error.javaClass.simpleName}")
        fault = when (error) {
            is VaultAccessException, is IncompleteFolderListing -> error.message
            is SecurityException -> "access_lost"
            else -> "read_failed"
        }
        refresh()
    }
    private fun changed() { onChanged(); listener() }
    fun close() {
        ObsidianRuntimes.unregister(this)
        checks.close()
        check(checks.awaitClosed(10)) { "Obsidian reads did not stop" }
        ledger.close()
    }
}
