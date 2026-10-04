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
    val recoveryCompleted: Boolean,
    val audioAwaitingCustody: Boolean,
    val unresolvedAudioInterruption: Boolean,
    val unresolvedOtherInterruption: Boolean = false,
    val unresolvedUnknownRecovery: Boolean = false,
    val wrist: WristShare = WristShare.Unknown,
    val journalVersion: JournalVersionReading? = null,
)

data class PhoneJournalFacts(
    val version: String = "unknown",
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
    !model.recoveryCompleted || model.audioAwaitingCustody || model.unresolvedAudioInterruption ||
        model.unresolvedOtherInterruption || model.unresolvedUnknownRecovery -> StatusPillKind.SYNCING
    else -> StatusPillKind.CONNECTED
}

const val STATUS_AUDIO_INTERRUPTED_LEAD = "audio was interrupted"
const val STATUS_AUDIO_INTERRUPTED_DETAIL = "some audio hasn't reached your journal."
const val STATUS_OTHER_INTERRUPTED_LEAD = "there's a gap in your journal"
const val STATUS_OTHER_INTERRUPTED_DETAIL = "some of what you shared hasn't reached it."
const val STATUS_UNKNOWN_RECOVERY_LEAD = "an unfinished item couldn't be checked"
const val STATUS_WAITING_TO_SYNC_LEAD = "waiting to sync"
const val STATUS_ON_THIS_DEVICE = "on this device"

fun audioCustodyLines(model: PhoneStatusModel): List<String> = buildList {
    if (model.unresolvedOtherInterruption) {
        add(STATUS_OTHER_INTERRUPTED_LEAD)
        add(STATUS_OTHER_INTERRUPTED_DETAIL)
    }
    if (model.unresolvedAudioInterruption) {
        add(STATUS_AUDIO_INTERRUPTED_LEAD)
        add(STATUS_AUDIO_INTERRUPTED_DETAIL)
    }
    if (model.unresolvedUnknownRecovery) {
        add(STATUS_UNKNOWN_RECOVERY_LEAD)
        add(STATUS_ON_THIS_DEVICE)
    }
    if (model.audioAwaitingCustody) {
        add(STATUS_WAITING_TO_SYNC_LEAD)
        add(STATUS_ON_THIS_DEVICE)
    }
}

fun statusPillText(model: PhoneStatusModel): String = when {
    !model.paired -> "not paired"
    !model.online -> "offline · ${model.pendingCount} waiting"
    model.awaitingMarkConfirmation -> if (model.pendingCount > 0) {
        "confirm the mark · ${model.pendingCount} waiting"
    } else {
        "confirm the mark"
    }
    model.pendingCount > 0 -> "${model.pendingCount} syncing"
    model.unresolvedOtherInterruption -> STATUS_OTHER_INTERRUPTED_LEAD
    model.unresolvedAudioInterruption -> STATUS_AUDIO_INTERRUPTED_LEAD
    model.unresolvedUnknownRecovery -> STATUS_UNKNOWN_RECOVERY_LEAD
    model.audioAwaitingCustody -> STATUS_WAITING_TO_SYNC_LEAD
    !model.recoveryCompleted -> "0 syncing"
    else -> "connected"
}
