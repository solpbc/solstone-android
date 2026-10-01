// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.pl.JournalVersionFreshness
import app.solstone.core.pl.JournalVersionReading

enum class StatusPillKind {
    CONNECTED,
    SYNCING,
    OFFLINE,
    NOT_PAIRED,
    AWAITING_MARK_CONFIRMATION,
}

data class PhoneStatusModel(
    val paired: Boolean,
    val online: Boolean,
    val pendingCount: Int,
    val hasContentPending: Boolean,
    val awaitingMarkConfirmation: Boolean,
    val wrist: WristShare = WristShare.Unknown,
    val journalVersion: JournalVersionReading? = null,
)

data class PhoneJournalFacts(
    val version: String = "unknown",
    val label: String = "—",
    val location: String = "—",
    val connection: String = "—",
    val fingerprint: String = "—",
    val intake: String = "—",
    /** The saved direct address, `host:port`, or a dash when none is saved. */
    val address: String = "—",
    /** What the last `check connection` found, or null before one has been asked for. */
    val check: String? = null,
)

fun journalVersionDisplayText(reading: JournalVersionReading?): String = when (reading?.freshness) {
    null, JournalVersionFreshness.NEVER_OBSERVED -> "unknown"
    JournalVersionFreshness.LAST_KNOWN -> "${reading.version} (last known)"
    JournalVersionFreshness.CURRENT -> reading.version.orEmpty()
}

fun statusPillKind(model: PhoneStatusModel): StatusPillKind = when {
    !model.paired -> StatusPillKind.NOT_PAIRED
    !model.online -> StatusPillKind.OFFLINE
    model.awaitingMarkConfirmation -> StatusPillKind.AWAITING_MARK_CONFIRMATION
    model.pendingCount > 0 -> StatusPillKind.SYNCING
    else -> StatusPillKind.CONNECTED
}

fun statusPillText(model: PhoneStatusModel): String = when (statusPillKind(model)) {
    StatusPillKind.CONNECTED -> "connected"
    StatusPillKind.SYNCING -> "${model.pendingCount} syncing"
    StatusPillKind.OFFLINE -> "offline · ${model.pendingCount} waiting"
    StatusPillKind.NOT_PAIRED -> "not paired"
    StatusPillKind.AWAITING_MARK_CONFIRMATION -> if (model.pendingCount > 0) {
        "confirm the mark · ${model.pendingCount} waiting"
    } else {
        "confirm the mark"
    }
}
