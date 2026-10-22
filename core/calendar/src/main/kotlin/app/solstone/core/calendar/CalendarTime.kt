// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.calendar

import app.solstone.core.model.WireKeys
import app.solstone.core.segment.wireKeys
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

const val CALENDAR_WINDOW_MS = 300_000L
const val CALENDAR_MISS_GAP_MS = 10L * 60L * 1000L

enum class HistoryKind {
    NONE_BEFORE_TODAY,
    DAYS_30,
    MONTHS_6,
    YEARS_1,
    ALL,
}

fun formatChangedAt(instant: Instant, zone: ZoneId): String {
    val truncated = instant.truncatedTo(ChronoUnit.SECONDS)
    val offset = truncated.atZone(zone).offset
    val written = if (offset.totalSeconds % 60 != 0) ZoneOffset.UTC else offset
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(truncated.atOffset(written))
}

fun calendarWindow(changedAt: String): WireKeys {
    val parsed = OffsetDateTime.parse(changedAt)
    val epochMs = parsed.toInstant().toEpochMilli()
    val offsetMs = parsed.offset.totalSeconds * 1000L
    val start = Math.floorDiv(epochMs + offsetMs, CALENDAR_WINDOW_MS) * CALENDAR_WINDOW_MS - offsetMs
    return wireKeys(start, start + CALENDAR_WINDOW_MS, parsed.offset)
}

fun startOfDayEpochMs(day: LocalDate, zone: ZoneId): Long =
    day.atStartOfDay(zone).toInstant().toEpochMilli()

fun defaultLowerBound(kind: HistoryKind, today: LocalDate, zone: ZoneId): Long = when (kind) {
    HistoryKind.NONE_BEFORE_TODAY -> startOfDayEpochMs(today, zone)
    HistoryKind.DAYS_30 -> startOfDayEpochMs(today.minusDays(30), zone)
    HistoryKind.MONTHS_6 -> startOfDayEpochMs(today.minusMonths(6), zone)
    HistoryKind.YEARS_1 -> startOfDayEpochMs(today.minusYears(1), zone)
    HistoryKind.ALL -> Long.MIN_VALUE
}

/** Confirmed history retains its approved date, including none-before-today. */
fun confirmedLowerBound(
    kind: HistoryKind,
    today: LocalDate,
    zone: ZoneId,
    confirmedEpochDay: Long?,
): Long {
    if (kind == HistoryKind.ALL || confirmedEpochDay == null) {
        return defaultLowerBound(kind, today, zone)
    }
    return startOfDayEpochMs(LocalDate.ofEpochDay(confirmedEpochDay), zone)
}

fun horizonExclusiveEnd(today: LocalDate, zone: ZoneId): Long =
    startOfDayEpochMs(today.plusYears(1).plusDays(1), zone)

fun routineReadWindow(nowEpochMs: Long, zone: ZoneId, lastSuccessAt: Long?): Pair<Long, Long> {
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    val thirty = defaultLowerBound(HistoryKind.DAYS_30, today, zone)
    val start = if (lastSuccessAt == null) thirty else minOf(thirty, lastSuccessAt)
    return start to horizonExclusiveEnd(today, zone)
}

/** The next deep-history slice. The end is exclusive and at most three years after [cursorEpochMs]. */
fun nextDeepSlice(cursorEpochMs: Long, zone: ZoneId, horizonExclusive: Long): Pair<Long, Long>? {
    if (cursorEpochMs >= horizonExclusive) return null
    val end = minOf(
        Instant.ofEpochMilli(cursorEpochMs).atZone(zone).plusYears(3).toInstant().toEpochMilli(),
        horizonExclusive,
    )
    if (end <= cursorEpochMs) return null
    return cursorEpochMs to end
}

fun epochDayOf(epochMs: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toEpochDay()
