// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.observer.formfactor.phone.PhoneJournalFacts
import app.solstone.observer.formfactor.phone.PhoneStatusModel
import app.solstone.observer.formfactor.phone.journalVersionDisplayText
import app.solstone.observer.harness.WishStoreState

/**
 * The facts `settings > your journal` and `technical details` show.
 *
 * ⚠ [intakeRunning] is whether intake is actually running — the foreground service live and
 * holding capture types — never the owner's standing wish. `desiredOn` is true from the first
 * launch whether or not a single source is on, so reading it here showed `intake: running` over a
 * screen where every source said `ready to set up`.
 */
internal fun phoneJournalFacts(
    pairing: PairingGraphSnapshot,
    status: PhoneStatusModel?,
    intakeRunning: Boolean,
    check: String? = null,
): PhoneJournalFacts {
    val committed = pairing as? PairingGraphSnapshot.Committed
    return PhoneJournalFacts(
        version = journalVersionDisplayText(status?.journalVersion),
        // `direct` and `relay` are how the transport is named in code, not how a route is named
        // to the person taking it.
        location = when {
            committed == null -> "—"
            committed.isDirectEligible && committed.isRelayEligible -> "—"
            committed.isDirectEligible -> "straight to your journal"
            committed.isRelayEligible -> "through the relay sol pbc runs"
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
        // ⚠ The same two words the ongoing notification uses. `running` here against `on` in the
        // shade gave one founder-ruled state word two vocabularies on one device.
        intake = if (intakeRunning) "on" else "off",
        check = check,
    )
}

internal fun shouldShowWelcome(
    wishes: WishStoreState,
    pairing: PairingGraphSnapshot,
): Boolean = wishes is WishStoreState.Absent && pairing is PairingGraphSnapshot.Absent
