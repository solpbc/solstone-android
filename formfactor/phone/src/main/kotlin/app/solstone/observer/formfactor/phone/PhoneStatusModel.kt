// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.pl.JournalVersionFreshness
import app.solstone.core.pl.JournalVersionReading
import app.solstone.core.spool.GAP_HEADLINE
import app.solstone.core.spool.GapDayLoss
import app.solstone.core.spool.gapDayLine
import app.solstone.core.spool.gapFoundInWindow
import app.solstone.core.spool.gapWindowEndMillis
import java.time.ZoneId

enum class StatusPillKind {
    CONNECTED,
    SYNCING,
    OFFLINE,
    NOT_PAIRED,
    AWAITING_MARK_CONFIRMATION,
    NOTICE,
}

data class PhoneGapLoss(
    val id: String,
    val capturedEpochMs: Long?,
    val foundEpochMs: Long,
    val seen: Boolean,
)

data class PhoneGapStatus(
    val losses: List<PhoneGapLoss> = emptyList(),
    val unknownPresent: Boolean = false,
    val unknownUnseen: Boolean = false,
    val unseenUnknownKeys: List<String> = emptyList(),
    val gateOpen: Boolean = false,
    val technicalDetails: String = "not run",
)

data class PhoneStatusModel(
    val paired: Boolean,
    val online: Boolean,
    val pendingCount: Int,
    val hasContentPending: Boolean,
    val awaitingMarkConfirmation: Boolean,
    val recoveryCompleted: Boolean,
    val audioAwaitingCustody: Boolean,
    val gapLosses: List<PhoneGapLoss> = emptyList(),
    val unknownPresent: Boolean = false,
    val unknownUnseen: Boolean = false,
    val unseenUnknownKeys: List<String> = emptyList(),
    val gateOpen: Boolean = false,
    val gapTechnicalDetails: String = "not run",
    val wrist: WristShare = WristShare.Unknown,
    val journalVersion: JournalVersionReading? = null,
    val calendarHeldForJournal: Boolean = false,
    /** False when this redraw does not know connectivity or how many segments are waiting. */
    val connectivityKnown: Boolean = true,
    /** Last status line drawn while those facts were known. A cold redraw keeps it. */
    val rememberedSyncText: String? = null,
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

fun statusPillKind(
    model: PhoneStatusModel,
    nowEpochMs: Long = statusWallNow(),
    zone: ZoneId = ZoneId.systemDefault(),
): StatusPillKind = when {
    !model.paired -> StatusPillKind.NOT_PAIRED
    !model.online -> StatusPillKind.OFFLINE
    model.awaitingMarkConfirmation -> StatusPillKind.AWAITING_MARK_CONFIRMATION
    model.pendingCount > 0 -> StatusPillKind.SYNCING
    model.audioAwaitingCustody || !model.recoveryCompleted -> StatusPillKind.SYNCING
    gapPill(model, nowEpochMs, zone) != GapPill.NONE -> StatusPillKind.NOTICE
    else -> StatusPillKind.CONNECTED
}

const val STATUS_UNKNOWN_RECOVERY_LEAD = "an unfinished item couldn't be checked"
const val STATUS_WAITING_TO_SYNC_LEAD = "waiting to sync"
const val STATUS_ON_THIS_DEVICE = "on this device"
private const val MARK_LINE = "waiting for you to confirm your journal's mark"
private const val CALENDAR_HELD_SUB = "calendar notes stay on this phone until your journal can take them."

private enum class GapPill { NONE, UNKNOWN, GAP }

private fun gapPill(model: PhoneStatusModel, nowEpochMs: Long, zone: ZoneId): GapPill = when {
    !model.gateOpen -> GapPill.NONE
    model.unknownUnseen -> GapPill.UNKNOWN
    unseenGapInWindow(model, nowEpochMs, zone) && !model.calendarHeldForJournal -> GapPill.GAP
    else -> GapPill.NONE
}

fun unseenGapInWindow(model: PhoneStatusModel, nowEpochMs: Long, zone: ZoneId): Boolean =
    model.gapLosses.any { !it.seen && gapFoundInWindow(it.foundEpochMs, nowEpochMs, zone) }

fun displayedGapLossIds(model: PhoneStatusModel, nowEpochMs: Long, zone: ZoneId): Set<String> =
    model.gapLosses.filter { !it.seen && gapFoundInWindow(it.foundEpochMs, nowEpochMs, zone) }.map { it.id }.toSet()

fun gapAlarmAt(model: PhoneStatusModel, nowEpochMs: Long, zone: ZoneId): Long? {
    if (statusPillText(model, nowEpochMs, zone) != GAP_HEADLINE) return null
    return model.gapLosses
        .filter { !it.seen && gapFoundInWindow(it.foundEpochMs, nowEpochMs, zone) }
        .minOfOrNull { gapWindowEndMillis(it.foundEpochMs, zone) }
}

fun interface GapWindowScheduler {
    /** `null` cancels the one alarm. A millis value is the inexact window end. */
    fun schedule(windowEndMillis: Long?)
}

fun syncGapAlarm(scheduler: GapWindowScheduler, model: PhoneStatusModel, nowEpochMs: Long, zone: ZoneId) {
    scheduler.schedule(gapAlarmAt(model, nowEpochMs, zone))
}

private fun PhoneStatusModel.dayLosses(): List<GapDayLoss> =
    gapLosses.map { GapDayLoss(it.capturedEpochMs, it.foundEpochMs, it.seen) }

fun gapDaySentence(model: PhoneStatusModel, nowEpochMs: Long, zone: ZoneId): String? =
    if (model.gateOpen) gapDayLine(model.dayLosses(), nowEpochMs, zone) else null

fun audioCustodyLines(
    model: PhoneStatusModel,
    nowEpochMs: Long = statusWallNow(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<String> = buildList {
    if (model.gateOpen && model.unknownPresent) {
        add(STATUS_UNKNOWN_RECOVERY_LEAD)
        add(STATUS_ON_THIS_DEVICE)
    }
    val day = gapDaySentence(model, nowEpochMs, zone)
    if (day != null) {
        add(GAP_HEADLINE)
        add(day)
    }
    if (model.audioAwaitingCustody) {
        add(STATUS_WAITING_TO_SYNC_LEAD)
        add(STATUS_ON_THIS_DEVICE)
    }
}

fun statusPillText(
    model: PhoneStatusModel,
    nowEpochMs: Long = statusWallNow(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (!model.connectivityKnown) return coldStatusLine(model, nowEpochMs, zone)
    return when {
    !model.paired -> "not paired"
    !model.online -> "offline · ${model.pendingCount} waiting"
    model.awaitingMarkConfirmation -> if (model.pendingCount > 0) {
        "confirm the mark · ${model.pendingCount} waiting"
    } else {
        "confirm the mark"
    }
    model.pendingCount > 0 -> "${model.pendingCount} syncing"
    model.audioAwaitingCustody -> STATUS_WAITING_TO_SYNC_LEAD
    !model.recoveryCompleted -> "0 syncing"
    gapPill(model, nowEpochMs, zone) == GapPill.UNKNOWN -> STATUS_UNKNOWN_RECOVERY_LEAD
    gapPill(model, nowEpochMs, zone) == GapPill.GAP -> GAP_HEADLINE
    else -> "connected"
    }
}

/**
 * A cold redraw knows pairing, custody, and the gap record. It does not know the link or the
 * waiting count, so it does not invent "connected" or a waiting number. A gap still replaces the
 * remembered line. With no remembered line, the status line stays blank.
 */
private fun coldStatusLine(model: PhoneStatusModel, nowEpochMs: Long, zone: ZoneId): String {
    if (!model.paired) return "not paired"
    if (model.audioAwaitingCustody) return STATUS_WAITING_TO_SYNC_LEAD
    if (model.gateOpen && model.unknownUnseen) return STATUS_UNKNOWN_RECOVERY_LEAD
    if (model.gateOpen && unseenGapInWindow(model, nowEpochMs, zone) && !model.calendarHeldForJournal) {
        return GAP_HEADLINE
    }
    return model.rememberedSyncText.orEmpty()
}

fun PhoneStatusModel.clearedForSupport(): PhoneStatusModel = copy(
    gapLosses = emptyList(),
    unknownPresent = false,
    unknownUnseen = false,
    unseenUnknownKeys = emptyList(),
    gateOpen = false,
)

data class StatusPaneCopy(
    val count: Boolean,
    val lead: String?,
    val lines: List<String>,
    val confirm: Boolean,
)

fun statusPaneCopy(
    model: PhoneStatusModel,
    nowEpochMs: Long = statusWallNow(),
    zone: ZoneId = ZoneId.systemDefault(),
    importsNeedYou: Boolean = false,
): StatusPaneCopy? {
    val summary = pairedStatusSummary(model, nowEpochMs, zone) ?: return null
    val day = gapDaySentence(model, nowEpochMs, zone)
    val showGap = day != null
    val showUnknown = model.unknownPresent
    val notice = showGap || showUnknown
    val kind = statusPillKind(model, nowEpochMs, zone)
    val lines = mutableListOf<String>()
    val caughtUpLead = when {
        importsNeedYou -> "imports need you"
        model.calendarHeldForJournal -> "journal needs an update"
        notice -> null
        else -> "all caught up"
    }
    val lead = when (summary.lead) {
        PairedStatusLead.COUNT -> null
        PairedStatusLead.MARK_LINE -> MARK_LINE
        PairedStatusLead.CAUGHT_UP -> caughtUpLead
    }
    val replaced = summary.lead == PairedStatusLead.CAUGHT_UP && lead == null && notice
    if (summary.markLineFollowsCount) lines += MARK_LINE
    if (summary.subLine) {
        val sub = when (kind) {
            StatusPillKind.CONNECTED, StatusPillKind.NOTICE -> when {
                importsNeedYou -> "open import to see why"
                model.calendarHeldForJournal -> CALENDAR_HELD_SUB
                notice -> null
                else -> "everything is in your journal"
            }
            StatusPillKind.SYNCING -> "syncing to your journal…"
            StatusPillKind.OFFLINE -> STATUS_ON_THIS_DEVICE
            StatusPillKind.AWAITING_MARK_CONFIRMATION -> "nothing waiting goes into your journal until you do."
            StatusPillKind.NOT_PAIRED -> null
        }
        if (sub != null) lines += sub
    }
    if (model.calendarHeldForJournal && kind != StatusPillKind.CONNECTED && kind != StatusPillKind.NOTICE) {
        lines += "journal needs an update"
        lines += CALENDAR_HELD_SUB
    }
    val noticeLines = mutableListOf<String>()
    if (showUnknown) {
        noticeLines += STATUS_UNKNOWN_RECOVERY_LEAD
        noticeLines += STATUS_ON_THIS_DEVICE
    }
    if (showGap) {
        noticeLines += GAP_HEADLINE
        noticeLines += day
    }
    if (model.paired && model.online && notice) noticeLines += "connected"
    if (model.audioAwaitingCustody) {
        noticeLines += STATUS_WAITING_TO_SYNC_LEAD
        noticeLines += STATUS_ON_THIS_DEVICE
    }
    return if (replaced && noticeLines.isNotEmpty()) {
        StatusPaneCopy(count = false, lead = noticeLines.first(), lines = noticeLines.drop(1) + lines, confirm = summary.action)
    } else {
        StatusPaneCopy(count = summary.lead == PairedStatusLead.COUNT, lead = lead, lines = lines + noticeLines, confirm = summary.action)
    }
}
