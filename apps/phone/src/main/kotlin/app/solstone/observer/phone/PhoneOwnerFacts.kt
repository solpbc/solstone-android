// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.observer.formfactor.phone.PhoneJournalFacts
import app.solstone.observer.formfactor.phone.PhoneStatusModel
import app.solstone.observer.formfactor.phone.journalVersionDisplayText
import app.solstone.observer.harness.WishStoreState

internal fun phoneJournalFacts(
    pairing: PairingGraphSnapshot,
    status: PhoneStatusModel?,
    intakeRunning: Boolean,
): PhoneJournalFacts {
    val committed = pairing as? PairingGraphSnapshot.Committed
    return PhoneJournalFacts(
        version = journalVersionDisplayText(status?.journalVersion),
        location = when {
            committed == null -> "—"
            committed.isDirectEligible && committed.isRelayEligible -> "—"
            committed.isDirectEligible -> "direct"
            committed.isRelayEligible -> "relay"
            else -> "—"
        },
        connection = when {
            committed == null -> "not paired"
            status?.paired != true -> "—"
            status.online -> "connected"
            else -> "offline"
        },
        // This is the journal trust anchor, not this phone's client-certificate fingerprint.
        fingerprint = committed?.home?.caChainFingerprint ?: "—",
        intake = if (intakeRunning) "running" else "off",
    )
}

internal fun shouldShowWelcome(
    wishes: WishStoreState,
    pairing: PairingGraphSnapshot,
): Boolean = wishes is WishStoreState.Absent && pairing is PairingGraphSnapshot.Absent
