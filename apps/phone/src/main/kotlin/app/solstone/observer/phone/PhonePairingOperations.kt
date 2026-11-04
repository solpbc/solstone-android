// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.awaitingMarkConfirmation
import app.solstone.core.sources.PHONE_STREAM
import app.solstone.observer.formfactor.phone.PairingMismatchResult
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.SyncStores
import app.solstone.platform.work.JournalRevokeOutcome
import app.solstone.platform.work.confirmCurrentJournal
import app.solstone.platform.work.forgetPushAfterCleared
import app.solstone.platform.work.revokeThisDeviceOnJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher

internal suspend fun performPhoneMismatch(stores: SyncStores, expected: PairingGeneration): PairingMismatchResult =
    when (clearMismatchedPairing(
        publisher = stores.publisher,
        expected = expected,
        revoke = { revokeThisDeviceOnJournal(stores.publisher, expectedPairing = expected) },
        afterCleared = { forgetPushAfterCleared(stores) },
        canClear = {
            awaitingMarkConfirmation(JournalConfirmationPolicy.consults, stores.publisher.currentSnapshot(), stores.journalConfirmationStore)
        },
        log = PhoneDiagLog::appendRaw,
    )) {
        is GraphMutationResult.Cleared -> PairingMismatchResult.Disconnected
        is GraphMutationResult.Applied -> PairingMismatchResult.NotChanged
        else -> PairingMismatchResult.LocalFailure
    }

/**
 * Answers no to the pairing [expected]: leaves it on the journal, then goes back to the pairing it
 * replaced if one was kept, or else to no pairing at all.
 */
internal suspend fun clearMismatchedPairing(
    publisher: PairingPublisher,
    expected: PairingGeneration,
    revoke: () -> JournalRevokeOutcome,
    afterCleared: () -> Unit,
    canClear: () -> Boolean,
    log: (String) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): GraphMutationResult = leaveJournal(
    dispatcher = dispatcher,
    revoke = revoke,
    forget = {
        publisher.withMutationBoundary {
            val current = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed
            if (current?.pairing != expected || !canClear()) {
                GraphMutationResult.Conflict("pairing changed")
            } else if (publisher.canRestorePrior(expected)) {
                publisher.restorePrior(expected)
            } else {
                publisher.forget().also { result ->
                    if (result is GraphMutationResult.Cleared) afterCleared()
                }
            }
        }
    },
    log = log,
)

internal fun performPhoneConfirm(
    context: Context,
    stores: SyncStores,
    clientCertFingerprint: String,
): Boolean {
    if (confirmCurrentJournal(stores.publisher, stores.journalConfirmationStore, clientCertFingerprint)) {
        SyncScheduler.enqueueAfterConfirm(context.applicationContext, PHONE_STREAM)
        return true
    }
    return false
}

internal fun currentPairing(stores: SyncStores): PairingGeneration? =
    (stores.publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing

internal fun requestPairingMark(stores: SyncStores): Long? =
    stores.journalIdentityCoordinator.onMarkRequested()

internal fun Context.openUrl(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (this@openUrl !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        startActivity(intent)
    }
}
