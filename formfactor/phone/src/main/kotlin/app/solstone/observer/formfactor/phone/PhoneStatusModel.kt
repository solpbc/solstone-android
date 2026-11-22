// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

import app.solstone.core.pl.JournalVersionFreshness
import app.solstone.core.pl.JournalVersionReading
import app.solstone.core.pl.UnknownJournalSighting
import app.solstone.core.spool.GAP_HEADLINE
import app.solstone.core.spool.GapDayLoss
import app.solstone.core.spool.gapDayLine
import app.solstone.core.spool.gapFoundInWindow
import app.solstone.core.spool.gapWindowEndMillis
import java.time.ZoneId

enum class StatusPillKind {
    CONNECTED,
    IMPORT_REVIEW,
    IMPORT_ATTENTION,
    SYNCING,
    CONNECTING,
    OFFLINE,
    NOT_PAIRED,
    REFUSED,
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
    val technicalDetails: String = phoneText("android.gap.not.run"),
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
    val gapTechnicalDetails: String = phoneText("android.gap.not.run"),
    val wrist: WristShare = WristShare.Unknown,
    val journalVersion: JournalVersionReading? = null,
    val calendarHeldForJournal: Boolean = false,
    /** False when this redraw does not know connectivity or how many segments are waiting. */
    val connectivityKnown: Boolean = true,
    /** Additive to the connection's own state: nothing here changes the pill. */
    val unknownJournals: List<UnknownJournalSighting> = emptyList(),
    /** The journal itself refuses this phone; see [app.solstone.observer.harness.journalRefusesThisDevice]. */
    val refused: Boolean = false,
    /** Not connected, and not yet long enough to read offline. Only meaningful when not [online]. */
    val connecting: Boolean = false,
    /** Imports staged in the journal waiting for owner preview & confirmation (non-fault). */
    val importReviewCount: Int = 0,
    /** One or more imports need owner action (refused, missing copy, expired, or held by journal version). */
    val importsNeedAttention: Boolean = false,
)

data class PhoneJournalFacts(
    val version: String = phoneText("android.gap.unknown"),
    val location: String = phoneText("android.ownerfacts.line"),
    val connection: String = phoneText("android.ownerfacts.line"),
    val fingerprint: String = phoneText("android.ownerfacts.line"),
    val intake: String = phoneText("android.ownerfacts.line"),
    /** The saved direct address, `host:port`, or a dash when none is saved. */
    val address: String = phoneText("android.ownerfacts.line"),
    /** What the last `check connection` found, or null before one has been asked for. */
    val check: String? = null,
)

fun journalVersionDisplayText(reading: JournalVersionReading?): String = when (reading?.freshness) {
    null, JournalVersionFreshness.NEVER_OBSERVED -> phoneText("android.gap.unknown")
    JournalVersionFreshness.LAST_KNOWN -> phoneText("android.status.reading.version.last.known", "freshness" to ("last-known"), "version" to (reading.version.orEmpty()))
    JournalVersionFreshness.CURRENT -> reading.version.orEmpty()
}

fun statusPillKind(
    model: PhoneStatusModel,
    nowEpochMs: Long = statusWallNow(),
    zone: ZoneId = ZoneId.systemDefault(),
): StatusPillKind = when {
    !model.paired -> StatusPillKind.NOT_PAIRED
    model.refused -> StatusPillKind.REFUSED
    !model.online -> if (model.connecting) StatusPillKind.CONNECTING else StatusPillKind.OFFLINE
    model.awaitingMarkConfirmation -> StatusPillKind.AWAITING_MARK_CONFIRMATION
    model.pendingCount > 0 -> StatusPillKind.SYNCING
    model.audioAwaitingCustody || !model.recoveryCompleted -> StatusPillKind.SYNCING
    gapPill(model, nowEpochMs, zone) != GapPill.NONE -> StatusPillKind.NOTICE
    model.importsNeedAttention -> StatusPillKind.IMPORT_ATTENTION
    model.importReviewCount > 0 -> StatusPillKind.IMPORT_REVIEW
    else -> StatusPillKind.CONNECTED
}

val STATUS_CONNECTING get() = phoneText("android.journalmark.connecting")
val STATUS_UNKNOWN_RECOVERY_LEAD get() = phoneText("android.status.unfinished.item.couldn.t.be")
val STATUS_WAITING_TO_SYNC_LEAD get() = phoneText("android.status.waiting.sync")
val STATUS_ON_THIS_DEVICE get() = phoneText("android.import.device")
val STATUS_REFUSED_LEAD get() = phoneText("android.status.journalstoppedacceptingdevice")
private val REFUSED_BODY get() = phoneText("android.status.anythingwaitingstayshere")
val PAIR_AGAIN get() = phoneText("android.migration.pair.again")
private val MARK_LINE get() = phoneText("android.status.waiting.confirm.journal.s.mark")
private val CALENDAR_HELD_SUB get() = phoneText("android.status.calendar.notes.stay.phone.until")

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
    if (model.gateOpen && model.unknownUnseen) {
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
    !model.paired -> phoneText("android.source.not.paired")
    model.refused -> if (model.pendingCount > 0) phoneText("android.status.pairagainwaiting", "count" to (model.pendingCount)) else PAIR_AGAIN
    !model.online && model.connecting -> STATUS_CONNECTING
    !model.online -> phoneText("android.status.offline.model.pendingcount.waiting", "count" to (model.pendingCount))
    model.awaitingMarkConfirmation -> if (model.pendingCount > 0) {
        phoneText("android.status.confirm.mark.model.pendingcount.waiting", "count" to (model.pendingCount))
    } else {
        phoneText("android.status.confirm.mark")
    }
    model.pendingCount > 0 -> phoneText("android.status.model.pendingcount.syncing", "count" to (model.pendingCount))
    model.audioAwaitingCustody -> STATUS_WAITING_TO_SYNC_LEAD
    !model.recoveryCompleted -> phoneText("android.status.n0.syncing", "count" to (0))
    gapPill(model, nowEpochMs, zone) == GapPill.UNKNOWN -> STATUS_UNKNOWN_RECOVERY_LEAD
    gapPill(model, nowEpochMs, zone) == GapPill.GAP -> GAP_HEADLINE
    model.importsNeedAttention -> phoneText("android.source.connected")
    model.importReviewCount > 0 -> phoneText("android.status.ready.to.review.pill", "count" to (model.importReviewCount))
    else -> phoneText("android.source.connected")
    }
}

/**
 * A cold redraw knows pairing, custody, and the gap record. It does not know the link or the
 * waiting count, so it does not invent a connection or a waiting number: until this process hears
 * the journal it is connecting, never a remembered connected.
 */
private fun coldStatusLine(model: PhoneStatusModel, nowEpochMs: Long, zone: ZoneId): String =
    linkFreeStatusLine(model, nowEpochMs, zone) ?: STATUS_CONNECTING

/**
 * The line that stays true however long it sits on a surface nothing redraws: what the phone itself
 * holds, never a word about the link. Null when the phone has nothing of the kind to say.
 */
fun linkFreeStatusLine(
    model: PhoneStatusModel,
    nowEpochMs: Long = statusWallNow(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? = when {
    !model.paired -> phoneText("android.source.not.paired")
    model.refused -> PAIR_AGAIN
    model.awaitingMarkConfirmation -> phoneText("android.status.confirm.mark")
    model.audioAwaitingCustody -> STATUS_WAITING_TO_SYNC_LEAD
    model.gateOpen && model.unknownUnseen -> STATUS_UNKNOWN_RECOVERY_LEAD
    model.gateOpen && unseenGapInWindow(model, nowEpochMs, zone) && !model.calendarHeldForJournal -> GAP_HEADLINE
    else -> null
}

fun PhoneStatusModel.clearedForSupport(): PhoneStatusModel = copy(
    gapLosses = emptyList(),
    unknownPresent = false,
    unknownUnseen = false,
    unseenUnknownKeys = emptyList(),
    gateOpen = false,
    importReviewCount = 0,
    importsNeedAttention = false,
)

data class StatusPaneCopy(
    val count: Boolean,
    val lead: String?,
    val lines: List<String>,
    val confirm: Boolean,
    val pairAgain: Boolean = false,
)

fun statusPaneCopy(
    model: PhoneStatusModel,
    nowEpochMs: Long = statusWallNow(),
    zone: ZoneId = ZoneId.systemDefault(),
    importsNeedYou: Boolean = false,
    importsReadyToReview: Int = 0,
): StatusPaneCopy? {
    val summary = pairedStatusSummary(model, nowEpochMs, zone) ?: return null
    val day = gapDaySentence(model, nowEpochMs, zone)
    val showGap = day != null
    val showUnknown = model.gateOpen && model.unknownUnseen
    val notice = showGap || showUnknown
    val kind = statusPillKind(model, nowEpochMs, zone)
    val effectiveImportsNeedYou = importsNeedYou || model.importsNeedAttention
    val effectiveImportsReview = maxOf(importsReadyToReview, model.importReviewCount)
    val lines = mutableListOf<String>()
    val caughtUpLead = when {
        effectiveImportsNeedYou -> phoneText("android.status.imports.need")
        effectiveImportsReview > 0 -> phoneText("android.status.ready.to.review.lead", "count" to (effectiveImportsReview))
        model.calendarHeldForJournal -> phoneText("android.status.journal.needs.update")
        notice -> null
        model.unknownPresent -> phoneText("android.status.nothing.waiting.sync")
        else -> phoneText("android.status.all.caught.up")
    }
    val lead = when (summary.lead) {
        PairedStatusLead.COUNT -> null
        PairedStatusLead.MARK_LINE -> MARK_LINE
        PairedStatusLead.REFUSED -> STATUS_REFUSED_LEAD
        PairedStatusLead.CAUGHT_UP -> caughtUpLead
    }
    val replaced = summary.lead == PairedStatusLead.CAUGHT_UP && lead == null && notice
    if (summary.markLineFollowsCount) lines += MARK_LINE
    if (summary.subLine) {
        val sub = when (kind) {
            StatusPillKind.CONNECTED,
            StatusPillKind.NOTICE,
            StatusPillKind.IMPORT_ATTENTION,
            StatusPillKind.IMPORT_REVIEW,
            -> when {
                effectiveImportsNeedYou -> phoneText("android.status.open.import.see.why")
                effectiveImportsReview > 0 -> phoneText("android.status.open.import.review.start")
                model.calendarHeldForJournal -> CALENDAR_HELD_SUB
                notice -> null
                model.unknownPresent -> null
                else -> phoneText("android.status.everything.journal")
            }
            StatusPillKind.SYNCING -> phoneText("android.status.syncing.journal")
            StatusPillKind.CONNECTING, StatusPillKind.OFFLINE -> STATUS_ON_THIS_DEVICE
            StatusPillKind.REFUSED -> REFUSED_BODY
            StatusPillKind.AWAITING_MARK_CONFIRMATION -> phoneText("android.status.nothing.waiting.goes.into.journal")
            StatusPillKind.NOT_PAIRED -> null
        }
        if (sub != null) lines += sub
    }
    if (model.calendarHeldForJournal && kind !in setOf(
            StatusPillKind.CONNECTED,
            StatusPillKind.NOTICE,
            StatusPillKind.IMPORT_ATTENTION,
            StatusPillKind.IMPORT_REVIEW,
        )
    ) {
        lines += phoneText("android.status.journal.needs.update")
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
    if (model.paired && model.online && notice) noticeLines += phoneText("android.source.connected")
    if (model.audioAwaitingCustody) {
        noticeLines += STATUS_WAITING_TO_SYNC_LEAD
        noticeLines += STATUS_ON_THIS_DEVICE
    }
    return if (replaced && noticeLines.isNotEmpty()) {
        StatusPaneCopy(count = false, lead = noticeLines.first(), lines = noticeLines.drop(1) + lines, confirm = summary.action)
    } else {
        StatusPaneCopy(
            count = summary.lead == PairedStatusLead.COUNT,
            lead = lead,
            lines = lines + noticeLines,
            confirm = summary.action,
            pairAgain = kind == StatusPillKind.REFUSED,
        )
    }
}
