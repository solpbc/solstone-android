// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import app.solstone.core.model.IdentityState

fun confirmedFor(
    consults: Boolean,
    store: JournalConfirmationStore,
    fingerprint: String,
): Boolean =
    !consults || store.inspect().let { inspected ->
        inspected is StoreInspectResult.Ready && inspected.value.confirmed == fingerprint
    }

/**
 * Display predicate. Awaiting is a paired, committed home whose mark is not confirmed for this
 * fingerprint. Not awaiting does not mean may send.
 */
fun awaitingMarkConfirmation(
    consults: Boolean,
    snapshot: PairingGraphSnapshot,
    store: JournalConfirmationStore,
): Boolean {
    val home = (snapshot as? PairingGraphSnapshot.Committed)?.home ?: return false
    if (home.state != IdentityState.PAIRED) return false
    return !confirmedFor(consults, store, home.clientCertFingerprint)
}
