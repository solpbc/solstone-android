// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.JournalConfirmationStore
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.StoreInspectResult

object JournalConfirmationGrandfather {
    @Volatile
    private var ran = false

    internal fun resetForTest() {
        ran = false
    }

    fun grandfather(
        publisher: PairingPublisher,
        store: JournalConfirmationStore,
        testHook: (() -> Unit)? = null,
    ) {
        if (!JournalConfirmationPolicy.consults) return
        publisher.withMutationBoundary {
            if (ran) return@withMutationBoundary
            ran = true
            val snapshot = publisher.currentSnapshot()
            testHook?.invoke()
            if (snapshot is PairingGraphSnapshot.Committed) {
                if (store.inspect() is StoreInspectResult.Missing) {
                    store.confirm(snapshot.home.clientCertFingerprint)
                }
            }
        }
    }
}

fun confirmCurrentJournal(
    publisher: PairingPublisher,
    store: JournalConfirmationStore,
    fingerprint: String,
): Boolean = publisher.withMutationBoundary {
    val snapshot = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed ?: return@withMutationBoundary false
    if (snapshot.home.clientCertFingerprint != fingerprint) return@withMutationBoundary false
    try {
        store.confirm(fingerprint)
        true
    } catch (_: Exception) {
        false
    }
}
