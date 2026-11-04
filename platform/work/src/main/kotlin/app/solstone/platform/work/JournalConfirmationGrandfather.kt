// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.crypto.instanceIdMatchesCa
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
                    try {
                        store.confirm(snapshot.home.clientCertFingerprint)
                    } catch (t: Exception) {
                        workerLog("w", "confirmation write failed; pairing left unconfirmed", t)
                    }
                }
            } else if (snapshot is PairingGraphSnapshot.Absent) {
                if (store.inspect() is StoreInspectResult.Missing) {
                    try {
                        store.settle()
                    } catch (t: Exception) {
                        workerLog("w", "confirmation write failed; pairing left unconfirmed", t)
                    }
                }
            }
            holdPairingWhoseIdIsNotItsCas(publisher, store)
            // A pairing answered for just before a process died can still have one kept aside.
            if (snapshot is PairingGraphSnapshot.Committed &&
                (store.inspect() as? StoreInspectResult.Ready)?.value?.confirmed == snapshot.home.clientCertFingerprint
            ) {
                publisher.discardPrior(snapshot.pairing)
            }
        }
    }
}

/**
 * A pairing stored before ids were checked may name a journal its pinned CA isn't. It goes back to
 * the held state, once, and the owner is asked about the mark that CA gives. Nothing else is re-asked.
 * A CA that can't be read proves nothing, so it holds nothing here: no dial can verify a peer
 * without it, and the mark shows as unavailable.
 */
fun holdPairingWhoseIdIsNotItsCas(publisher: PairingPublisher, store: JournalConfirmationStore) {
    if (!JournalConfirmationPolicy.consults) return
    publisher.withMutationBoundary {
        val snapshot = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed ?: return@withMutationBoundary
        val fingerprint = snapshot.home.clientCertFingerprint
        val state = (store.inspect() as? StoreInspectResult.Ready)?.value ?: return@withMutationBoundary
        if (state.confirmed != fingerprint || state.reasked == fingerprint) return@withMutationBoundary
        val caPem = runCatching { publisher.pinnedCaPem(snapshot.pairing) }.getOrNull() ?: return@withMutationBoundary
        if (instanceIdMatchesCa(snapshot.home.instanceId, caPem) != false) return@withMutationBoundary
        try {
            store.holdToAskAgain(fingerprint)
        } catch (t: Exception) {
            workerLog("w", "confirmation write failed; pairing not held", t)
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
        publisher.discardPrior(snapshot.pairing)
        true
    } catch (_: Exception) {
        false
    }
}
