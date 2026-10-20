// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

/** Why owner-shared imports are still on this device, the most important reason first. */
enum class ImportWaitingReason {
    UNPAIRED,
    AWAITING_CONFIRMATION,

    /** The journal refused this device's pairing, or revoked it. Only pairing again fixes it. */
    PAIRING_REFUSED,

    /** This device's pairing says paired but its credential or every route to the journal is missing. */
    PAIRING_INCOMPLETE,

    /** No version recorded for this journal yet. */
    CHECKING_JOURNAL,

    /** The journal is too old to take imports, or its version can't be read. */
    JOURNAL_TOO_OLD,

    /** An item is being sent now. */
    SENDING,

    WAITING,
}

/**
 * The one reason imports are waiting, by precedence: pairing comes before the journal's version,
 * and both before whether something is in flight. It doesn't depend on how many items wait.
 */
fun importWaitingReason(
    paired: Boolean,
    pairingIncomplete: Boolean,
    awaitingConfirmation: Boolean,
    refused: Boolean,
    gate: ImportVersionGate,
    sending: Boolean,
): ImportWaitingReason =
    when {
        !paired -> ImportWaitingReason.UNPAIRED
        awaitingConfirmation -> ImportWaitingReason.AWAITING_CONFIRMATION
        refused -> ImportWaitingReason.PAIRING_REFUSED
        pairingIncomplete -> ImportWaitingReason.PAIRING_INCOMPLETE
        gate == ImportVersionGate.UNKNOWN -> ImportWaitingReason.CHECKING_JOURNAL
        gate == ImportVersionGate.TOO_OLD -> ImportWaitingReason.JOURNAL_TOO_OLD
        sending -> ImportWaitingReason.SENDING
        else -> ImportWaitingReason.WAITING
    }
