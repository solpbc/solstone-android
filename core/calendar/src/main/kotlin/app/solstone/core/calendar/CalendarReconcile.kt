// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.calendar

import java.time.Instant
import java.time.ZoneId

enum class ScanOutcome {
    COMPLETE,
    FAILED,
    CANCELLED,
    ACCESS_LOST,
    PAUSED,
    PARTIAL,
    STORAGE_FAILED,
    UNCHECKED,
}

data class CalendarIdentityRecord(
    val event: String,
    val occurrence: String,
    val publishedFingerprint: String? = null,
    val publishedChangedAt: String? = null,
    val publishedChangedAtEpochMs: Long? = null,
    val pendingFingerprint: String? = null,
    val pendingChangedAt: String? = null,
    val pendingChangedAtEpochMs: Long? = null,
    val pendingCanonical: String? = null,
    val pendingIntentId: String? = null,
    val lastTitle: String? = null,
    val lastCalendar: String? = null,
    val holders: Set<Long> = emptySet(),
    val winningHolder: Long? = null,
    val missCount: Int = 0,
    val firstMissAt: Long? = null,
    val lastChange: String? = null,
    val touchedAt: Long? = null,
    val nameHolder: Long? = null,
    val lastStartEpochMs: Long? = null,
    val providerEventIds: Set<Long> = emptySet(),
    val lastAccount: String? = null,
)

data class SeenEvent(
    val event: String,
    val occurrence: String?,
    val calendarId: Long,
    val calendarName: String,
    val startEpochMs: Long,
    val note: CalendarNote,
    val providerEventIds: Set<Long> = emptySet(),
)

data class MoveLookup(
    val readable: Boolean,
    val stillPresent: Boolean,
    val seen: SeenEvent? = null,
    val seenAll: List<SeenEvent> = emptyList(),
)

data class ReconcileResult(
    val identities: List<CalendarIdentityRecord>,
    val notes: List<CalendarNote>,
    val advanceLastSuccess: Boolean,
    val discarded: Boolean,
)

fun occurrenceKey(occurrence: String?): String = occurrence ?: ""

fun reconcileScan(
    identities: List<CalendarIdentityRecord>,
    seen: List<SeenEvent>,
    selectedCalendarIds: Set<Long>,
    outcome: ScanOutcome,
    observationEpochMs: Long,
    zone: ZoneId,
    scanToken: Long,
    currentToken: Long,
    lowerBoundEpochMs: Long,
    detectMissing: (CalendarIdentityRecord) -> Boolean = { true },
    moveLookup: (CalendarIdentityRecord) -> MoveLookup = { MoveLookup(readable = true, stillPresent = false) },
): ReconcileResult {
    if (scanToken != currentToken || outcome != ScanOutcome.COMPLETE) {
        return ReconcileResult(identities, emptyList(), advanceLastSuccess = false, discarded = true)
    }
    val byKey = identities.associateBy { it.event to it.occurrence }.toMutableMap()
    val notes = mutableListOf<CalendarNote>()
    val seenByKey = seen.groupBy { it.event to occurrenceKey(it.occurrence) }
    val handled = mutableSetOf<Pair<String, String>>()

    for ((key, events) in seenByKey) {
        val previous = byKey[key]
        if (previous != null && previous.touchedAt != null && observationEpochMs < previous.touchedAt) {
            handled += key
            continue
        }
        if (previous == null && events.minOf { it.startEpochMs } < lowerBoundEpochMs) {
            handled += key
            continue
        }
        val next = applyPresent(previous, events, observationEpochMs, zone, selectedCalendarIds)
        byKey[key] = next.identity
        next.note?.let { notes += it }
        handled += key
    }

    val missing = byKey.filter { (key, previous) -> key !in handled && detectMissing(previous) &&
        previous.holders.intersect(selectedCalendarIds).isNotEmpty() &&
        (previous.touchedAt == null || observationEpochMs >= previous.touchedAt) }
    val lookups = missing.mapValues { (_, previous) -> moveLookup(previous) }
    if (lookups.values.any { !it.readable }) {
        return ReconcileResult(identities, emptyList(), advanceLastSuccess = false, discarded = true)
    }
    for ((key, previous) in byKey.toMap()) {
        if (key in handled || !detectMissing(previous)) continue
        if (previous.touchedAt != null && observationEpochMs < previous.touchedAt) continue
        if (previous.holders.intersect(selectedCalendarIds).isEmpty()) {
            byKey[key] = previous.copy(holders = emptySet(), winningHolder = null)
            continue
        }
        val lookup = lookups[key] ?: continue
        if (!lookup.readable) continue
        if (lookup.stillPresent && (lookup.seen != null || lookup.seenAll.isNotEmpty())) {
            val present = lookup.seenAll.ifEmpty { listOfNotNull(lookup.seen) }
            val next = applyPresent(previous, present, observationEpochMs, zone, selectedCalendarIds)
            byKey[key] = next.identity
            next.note?.let { notes += it }
            continue
        }
        if (lookup.stillPresent) continue
        val missed = noteMiss(previous, observationEpochMs, zone) ?: continue
        byKey[key] = missed.identity
        missed.note?.let { notes += it }
    }

    return ReconcileResult(
        identities = byKey.values.sortedWith(compareBy({ it.event }, { it.occurrence })),
        notes = notes,
        advanceLastSuccess = true,
        discarded = false,
    )
}

/** Unchecking a calendar stops future reads of it and never writes a removal. */
fun dropCalendar(identities: List<CalendarIdentityRecord>, calendarId: Long): List<CalendarIdentityRecord> =
    identities.map { identity ->
        if (calendarId !in identity.holders) return@map identity
        val holders = identity.holders - calendarId
        identity.copy(
            holders = holders,
            winningHolder = if (identity.winningHolder == calendarId) holders.minOrNull() else identity.winningHolder,
        )
    }

private data class PresentUpdate(val identity: CalendarIdentityRecord, val note: CalendarNote?)

private fun applyPresent(
    previous: CalendarIdentityRecord?,
    events: List<SeenEvent>,
    observationEpochMs: Long,
    zone: ZoneId,
    readCalendarIds: Set<Long>,
): PresentUpdate {
    val winner = events.minBy { it.calendarId }
    val observedHolders = events.map { it.calendarId }.toSet()
    val nameHolder = previous?.nameHolder ?: winner.calendarId
    val nameHolderRead = nameHolder in readCalendarIds
    val nameSource = if (nameHolderRead) events.firstOrNull { it.calendarId == nameHolder } ?: winner else null
    val calendarName = when {
        nameSource != null -> nameSource.calendarName
        previous?.lastCalendar != null -> previous.lastCalendar
        else -> winner.calendarName
    }
    // The account rides with the calendar name it belongs to.
    val account = when {
        nameSource != null -> nameSource.note.account
        previous?.lastCalendar != null -> previous.lastAccount
        else -> winner.note.account
    }
    val body = winner.note.copy(calendar = calendarName, account = account)
    val fingerprint = contentFingerprint(body)
    val priorFingerprint = previous?.pendingFingerprint ?: previous?.publishedFingerprint
    val priorChangedAt = previous?.pendingChangedAt ?: previous?.publishedChangedAt
    val priorEpoch = previous?.pendingChangedAtEpochMs ?: previous?.publishedChangedAtEpochMs
    val same = previous != null && fingerprint == priorFingerprint && previous.lastChange != CalendarChange.REMOVED.wire
    val identityBase = (previous ?: CalendarIdentityRecord(winner.event, occurrenceKey(winner.occurrence))).copy(
        holders = observedHolders,
        winningHolder = winner.calendarId,
        nameHolder = if (nameHolderRead && events.none { it.calendarId == nameHolder }) winner.calendarId else nameHolder,
        lastStartEpochMs = winner.startEpochMs,
        providerEventIds = events.flatMap { it.providerEventIds }.toSet(),
        missCount = 0,
        firstMissAt = null,
        touchedAt = observationEpochMs,
    )
    if (same) {
        return PresentUpdate(identityBase, null)
    }
    val changedAt = if (previous?.pendingFingerprint == fingerprint && previous.pendingChangedAt != null) {
        previous.pendingChangedAt
    } else {
        nextChangedAt(priorEpoch, observationEpochMs, zone)
    }
    val change = if (previous == null || previous.publishedFingerprint == null || previous.lastChange == CalendarChange.REMOVED.wire) {
        CalendarChange.ADDED
    } else {
        CalendarChange.CHANGED
    }
    val note = body.copy(change = change, changedAt = changedAt)
    val pendingEpoch = changedAtEpoch(changedAt)
    return PresentUpdate(
        identityBase.copy(
            pendingFingerprint = contentFingerprint(note),
            pendingChangedAt = changedAt,
            pendingChangedAtEpochMs = pendingEpoch,
            pendingCanonical = encodeCalendarLine(note),
        ),
        note,
    )
}

private data class MissUpdate(val identity: CalendarIdentityRecord, val note: CalendarNote?)

private fun noteMiss(
    previous: CalendarIdentityRecord,
    observationEpochMs: Long,
    zone: ZoneId,
): MissUpdate? {
    if (previous.publishedFingerprint == null || previous.lastChange == CalendarChange.REMOVED.wire) {
        return MissUpdate(
            previous.copy(
                missCount = previous.missCount + 1,
                firstMissAt = previous.firstMissAt ?: observationEpochMs,
                touchedAt = observationEpochMs,
            ),
            null,
        )
    }
    val first = previous.firstMissAt ?: observationEpochMs
    val count = previous.missCount + 1
    val due = count >= 2 && observationEpochMs - first >= CALENDAR_MISS_GAP_MS
    if (!due) {
        return MissUpdate(
            previous.copy(missCount = count, firstMissAt = first, touchedAt = observationEpochMs),
            null,
        )
    }
    val changedAt = nextChangedAt(previous.publishedChangedAtEpochMs, observationEpochMs, zone)
    val note = removedNote(
        event = previous.event,
        occurrence = previous.occurrence.ifEmpty { null },
        title = previous.lastTitle,
        calendar = previous.lastCalendar ?: "",
        account = previous.lastAccount,
        changedAt = changedAt,
    )
    return MissUpdate(
        previous.copy(
            missCount = count,
            firstMissAt = first,
            touchedAt = observationEpochMs,
            pendingFingerprint = contentFingerprint(note),
            pendingChangedAt = changedAt,
            pendingChangedAtEpochMs = changedAtEpoch(changedAt),
            pendingCanonical = encodeCalendarLine(note),
            lastChange = previous.lastChange,
        ),
        note,
    )
}

fun nextChangedAt(previousEpochMs: Long?, observationEpochMs: Long, zone: ZoneId): String {
    val observed = observationEpochMs - Math.floorMod(observationEpochMs, 1000L)
    val advanced = if (previousEpochMs == null) {
        observed
    } else {
        val previous = previousEpochMs - Math.floorMod(previousEpochMs, 1000L)
        maxOf(observed, previous + 1000L)
    }
    return formatChangedAt(Instant.ofEpochMilli(advanced), zone)
}

fun changedAtEpoch(changedAt: String): Long =
    java.time.OffsetDateTime.parse(changedAt).toInstant().toEpochMilli()
