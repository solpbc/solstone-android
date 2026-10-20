// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.observer.MobileImportClient
import app.solstone.core.observer.MobileImportFile
import app.solstone.core.observer.MobileImportItem
import app.solstone.core.observer.MobileImportOutcome
import app.solstone.core.observer.MobileImportSource
import app.solstone.core.observer.MobileImportText
import app.solstone.core.pl.PlHttpClient
import app.solstone.platform.persistence.room.ImportChange
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportItemDescriptor
import java.io.InputStream
import java.nio.file.Files

/** How many distinct imports one sync run takes on, across all its transport attempts. */
internal const val IMPORTS_PER_RUN = 16

/**
 * What one sync run has done with imports so far, carried across its transport attempts (direct,
 * then relay), so an item retried on direct is tried again on relay without counting twice, and
 * the cap holds for the whole run.
 */
internal class ImportRun(private val cap: Int = IMPORTS_PER_RUN) {
    val selected = linkedSetOf<String>()
    val retried = linkedSetOf<String>()

    /** The last import stage finished without a retry or a refusal and took every item it could. */
    var stageClean = false

    /** The last stage stopped at the cap with more eligible items waiting. */
    var stoppedByCap = false

    fun mayTake(id: String): Boolean = id in selected || selected.size < cap
}

internal enum class ImportStageResult {
    /** Every item it took was delivered, refused, or saved; nothing to retry. */
    CLEAN,

    /** At least one item should be tried again (on fallback, or by a later run). */
    RETRY,

    /** The journal refused this pairing; the run stops. */
    AUTH_STOP,

    /** The pairing changed during the stage; the run stops sending. */
    PAIRING_LEFT,

    /** The journal's version doesn't take imports yet, or isn't known; nothing was sent. */
    HELD,
}

/**
 * Deliver held imports to the paired journal, oldest first, within [run]'s cap. Each item's
 * exchange (save, then start) is marked in the store so the owner can't remove it mid-flight, and
 * each answer is recorded and the exchange ended under the store lock. An answer from the journal
 * a request went to counts even if the owner re-paired meanwhile: that journal has the item, the
 * same rule segments follow. A save answer that arrives after a re-pair sends no start.
 */
internal fun deliverImports(
    http: PlHttpClient,
    store: ImportCustodyStore,
    run: ImportRun,
    instanceId: String,
    journalVersion: String,
    gate: ImportVersionGate,
    pairingCurrent: () -> Boolean,
    now: () -> Long,
    log: (String, Throwable?) -> Unit,
    client: (PlHttpClient) -> MobileImportClient = ::MobileImportClient,
): ImportStageResult {
    if (gate != ImportVersionGate.SUPPORTED) return ImportStageResult.HELD
    val wire = client(http)
    val candidates = store.eligible(instanceId, journalVersion, now(), Int.MAX_VALUE)
    var anyRetry = false
    var result = ImportStageResult.CLEAN
    run.stoppedByCap = false
    try {
        for (candidate in candidates) {
            if (!run.mayTake(candidate.id)) {
                run.stoppedByCap = true
                break
            }
            if (!pairingCurrent()) {
                result = ImportStageResult.PAIRING_LEFT
                break
            }
            if (store.beginExchange(candidate.id, instanceId, journalVersion) != ImportChange.DONE) continue
            run.selected.add(candidate.id)
            run.retried.remove(candidate.id)
            val step = try {
                deliverOne(wire, store, candidate.id, candidate.progress?.let { it.path to it.timestamp }, instanceId, journalVersion, pairingCurrent)
            } catch (t: Throwable) {
                log("import ${candidate.id} failed; retry", t)
                ItemStep.RETRY
            } finally {
                store.endExchange(candidate.id)
            }
            when (step) {
                ItemStep.DONE -> Unit
                ItemStep.RETRY -> {
                    run.retried.add(candidate.id)
                    anyRetry = true
                }
                ItemStep.AUTH_STOP -> {
                    result = ImportStageResult.AUTH_STOP
                    break
                }
                ItemStep.PAIRING_LEFT -> {
                    result = ImportStageResult.PAIRING_LEFT
                    break
                }
            }
        }
    } finally {
        runCatching { store.finish() }.onFailure { log("finishing delivered imports failed", it) }
    }
    if (result == ImportStageResult.CLEAN && anyRetry) result = ImportStageResult.RETRY
    run.stageClean = result == ImportStageResult.CLEAN
    return result
}

/**
 * Record backoff for every item this run tried and could not deliver, once the whole run (all its
 * transports) is over, so a retry on direct doesn't keep the same item from relay.
 */
internal fun recordImportRetries(store: ImportCustodyStore, run: ImportRun, instanceId: String, now: Long) {
    for (id in run.retried) {
        store.markRetry(id, instanceId, now)
    }
}

private enum class ItemStep { DONE, RETRY, AUTH_STOP, PAIRING_LEFT }

private fun deliverOne(
    wire: MobileImportClient,
    store: ImportCustodyStore,
    id: String,
    saved: Pair<String, String>?,
    instanceId: String,
    journalVersion: String,
    pairingCurrent: () -> Boolean,
): ItemStep {
    var progress = saved
    if (progress == null) {
        val descriptor = store.item(id) ?: return ItemStep.DONE
        when (val outcome = save(wire, descriptor)) {
            is MobileImportOutcome.Saved -> {
                if (!pairingCurrent()) return ItemStep.PAIRING_LEFT
                if (store.recordSaved(id, outcome.path, outcome.timestamp, instanceId) == ImportChange.GONE) return ItemStep.DONE
                progress = outcome.path to outcome.timestamp
            }
            else -> return settle(store, id, outcome, instanceId, journalVersion)
        }
    }
    if (!pairingCurrent()) return ItemStep.PAIRING_LEFT
    return settle(store, id, wire.start(progress.first, progress.second), instanceId, journalVersion)
}

private fun settle(
    store: ImportCustodyStore,
    id: String,
    outcome: MobileImportOutcome,
    instanceId: String,
    journalVersion: String,
): ItemStep =
    when (outcome) {
        MobileImportOutcome.Delivered -> {
            store.recordDelivered(id, instanceId)
            ItemStep.DONE
        }
        is MobileImportOutcome.NeedsAttention -> {
            store.recordAttention(id, outcome.error, outcome.reasonCode ?: "", outcome.kind, instanceId, journalVersion)
            ItemStep.DONE
        }
        MobileImportOutcome.AuthStop -> ItemStep.AUTH_STOP
        MobileImportOutcome.Retry -> ItemStep.RETRY
        // A save answer is handled by the caller; at start it means the journal answered
        // something else, so try again later rather than guess.
        is MobileImportOutcome.Saved -> ItemStep.RETRY
    }

private fun save(wire: MobileImportClient, descriptor: ImportItemDescriptor): MobileImportOutcome {
    val item: MobileImportItem = if (descriptor.payloadKind == "text") {
        MobileImportText(descriptor.id, String(Files.readAllBytes(descriptor.payloadPath), Charsets.UTF_8))
    } else {
        MobileImportFile(
            clientItemId = descriptor.id,
            filename = descriptor.displayName,
            mediaType = descriptor.mime,
            length = descriptor.size,
            source = FileImportSource(Files.newInputStream(descriptor.payloadPath)),
        )
    }
    return wire.save(item)
}

private class FileImportSource(private val input: InputStream) : MobileImportSource {
    override fun read(target: ByteArray, offset: Int, length: Int): Int = input.read(target, offset, length)
    override fun close() = input.close()
}
