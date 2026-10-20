// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.identity.PairingGeneration
import app.solstone.observer.formfactor.phone.ImportRowKind
import app.solstone.observer.formfactor.phone.ImportWaiting
import app.solstone.observer.formfactor.phone.IntakeRefusalLine
import app.solstone.observer.formfactor.phone.PhoneImportPaneState
import app.solstone.observer.formfactor.phone.PhoneImportRow
import app.solstone.observer.formfactor.phone.PhoneIntakeLine
import app.solstone.observer.formfactor.phone.importRecentState
import app.solstone.platform.persistence.room.ImportAttentionScope
import app.solstone.platform.persistence.room.ImportChange
import app.solstone.platform.work.ImportVersionGate
import app.solstone.platform.work.ImportWaitingReason
import app.solstone.platform.work.SyncStores
import app.solstone.platform.work.importVersionGate
import app.solstone.platform.work.importWaitingReason

/**
 * Refusals a retry could plausibly change: the journal's queue or a write was briefly unavailable,
 * the record it staged went missing (a retry saves it again), or a preview was still running.
 * Every other refusal, and any code this list doesn't know, offers only `remove`.
 */
private val RETRYABLE_REASONS = setOf(
    "import_queue_unreachable",
    "import_metadata_failed",
    "import_not_found",
    "import_preview_required",
)
private val NOT_RETRYABLE_KINDS = setOf("client_item_conflict")

/** Reads the import pane's state from the import store and the pairing. Call off the main thread. */
internal fun readImportPane(
    intake: ImportIntake,
    stores: SyncStores,
    pairingFact: PairingFact,
    awaitingConfirmation: Boolean,
): PhoneImportPaneState {
    val identity = stores.identityStore.load()
    val record = stores.journalVersionStore.load()
    val gate = if (identity == null) ImportVersionGate.UNKNOWN else importVersionGate(record, identity.instanceId, identity.caChainFingerprint)
    val items = intake.store.items(identity?.instanceId, record?.version)
    // A revoked pairing is a refusal too: only pairing again sends these.
    val refused = pairingFact == PairingFact.REVOKED || (identity != null &&
        (stores.pairingRefusalStore?.refusedFor(PairingGeneration(identity.instanceId, identity.clientCertFingerprint)) ?: false))
    val reason = importWaitingReason(
        paired = pairingFact != PairingFact.UNPAIRED,
        pairingIncomplete = pairingFact == PairingFact.INCOMPLETE,
        awaitingConfirmation = awaitingConfirmation,
        refused = refused,
        gate = gate,
        sending = items.any { it.inFlight },
    )
    val rows = items.map { item ->
        val kind = when {
            item.inFlight -> ImportRowKind.SENDING
            item.scope == ImportAttentionScope.HERE -> ImportRowKind.REFUSED
            item.scope == ImportAttentionScope.ELSEWHERE -> ImportRowKind.GOING_AGAIN
            item.scope == ImportAttentionScope.LOCAL -> ImportRowKind.MISSING_COPY
            else -> ImportRowKind.WAITING
        }
        PhoneImportRow(
            id = item.id,
            name = item.displayName,
            kind = kind,
            error = item.error,
            retryable = kind == ImportRowKind.REFUSED && item.reasonCode in RETRYABLE_REASONS && item.kind !in NOT_RETRYABLE_KINDS,
            savedAtJournal = item.hasSavedProgress,
        )
    }
    val pending = items.count { it.state == "pending" }
    val attention = items.count { it.state == "attention" && it.scope != ImportAttentionScope.ELSEWHERE }
    val delivered = intake.store.lastDelivery()?.instanceId?.let { it == identity?.instanceId } ?: false
    val line = when (val state = intake.state.value) {
        is IntakeState.Copying -> PhoneIntakeLine.Copying(state.done, state.total)
        is IntakeState.Finished -> PhoneIntakeLine.Finished(state.outcome.accepted, state.outcome.refused.map { IntakeRefusalLine(it.label, it.reason.toLine()) })
        IntakeState.Idle -> null
    }
    return PhoneImportPaneState(
        // An item another journal refused is going again here, so it counts as waiting.
        recent = importRecentState(reason.toPane(), pending + items.count { it.scope == ImportAttentionScope.ELSEWHERE }, attention, delivered),
        rows = rows,
        intake = line,
        interrupted = intake.interrupted,
    )
}

private fun ImportWaitingReason.toPane(): ImportWaiting = ImportWaiting.valueOf(name)

/**
 * What the owner is told when a remove or a retry didn't go as asked; null when it did. The
 * store's answer is said as it is, since the item may have moved on since the pane was read.
 */
internal fun importChangeText(change: ImportChange): String? =
    when (change) {
        ImportChange.DONE -> null
        ImportChange.DELIVERED -> "it's already in your journal."
        ImportChange.GONE -> "it's already gone from this device."
        ImportChange.IN_FLIGHT -> "it's sending now, so nothing changed."
        ImportChange.REFUSED -> "nothing changed. it may have moved on; check the list."
    }

/** One picked or shared item, described and opened now, while its read grant holds. */
internal fun ContentResolver.intakeItemFor(uri: Uri): IntakeItem.Stream {
    var name: String? = null
    var size: Long? = null
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
                size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            }
        }
    }
    return IntakeItem.Stream(
        displayName = name,
        mime = if (uri.scheme == "content") runCatching { getType(uri) }.getOrNull() else null,
        scheme = uri.scheme,
        reportedSize = size,
        input = runCatching { openInputStream(uri) }.getOrNull(),
    )
}
