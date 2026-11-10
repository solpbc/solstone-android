// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class DistanceSystem { MILES, KILOMETERS }

enum class IntakeKind { DURATION, DISTANCE, PHOTOS, EVENTS, ITEMS }

/** SI measures in kilometers. US and UK both measure in miles. */
fun distanceSystem(measurementSystemName: String): DistanceSystem =
    if (measurementSystemName == "SI") DistanceSystem.KILOMETERS else DistanceSystem.MILES

/**
 * Miles or kilometers when the platform measurement system is unavailable.
 * The United States, the United Kingdom, Liberia, and Myanmar use miles.
 * A locale with no country uses kilometers.
 */
fun measurementSystemNameBeforeApi28(country: String): String {
    val code = country.uppercase(Locale.ROOT)
    return if (code == "US" || code == "GB" || code == "LR" || code == "MM") "US" else "SI"
}

fun intakeKind(sourceId: String): IntakeKind = when (sourceId) {
    "audio", "screen" -> IntakeKind.DURATION
    "location" -> IntakeKind.DISTANCE
    "camera" -> IntakeKind.PHOTOS
    "calendar" -> IntakeKind.EVENTS
    else -> IntakeKind.ITEMS
}

data class PhoneIntakeReading(
    val total: Long,
    val startedAt: Long,
    val lastAt: Long,
)

/**
 * `{total}, since {started}`, or `nothing yet` when nothing has been taken in.
 * A location row with no distance yet still has a total.
 */
fun formatSoFar(
    reading: PhoneIntakeReading?,
    kind: IntakeKind,
    system: DistanceSystem,
    nowEpochMs: Long,
    zone: ZoneId,
    locale: Locale,
): String {
    reading ?: return "nothing yet"
    val total = formatTotal(reading.total, kind, system, locale)
    val started = formatStarted(reading.startedAt, nowEpochMs, zone)
    return "$total, since $started"
}

/** The status value under `so far`, or null when the row should read `nothing yet`. */
fun formatStatusTotal(
    reading: PhoneIntakeReading?,
    kind: IntakeKind,
    system: DistanceSystem,
    locale: Locale,
): String? {
    reading ?: return null
    return formatTotal(reading.total, kind, system, locale)
}

/** Null while nothing has been taken in, so the row is left out. */
fun formatLastTakenIn(reading: PhoneIntakeReading?, nowEpochMs: Long, zone: ZoneId): String? {
    reading ?: return null
    return formatRelative(reading.lastAt, nowEpochMs, zone)
}

fun formatTotal(total: Long, kind: IntakeKind, system: DistanceSystem, locale: Locale): String =
    when (kind) {
        IntakeKind.DURATION -> formatDuration(total, locale)
        IntakeKind.DISTANCE -> formatDistance(total, system, locale)
        IntakeKind.PHOTOS -> countPhrase(total, "photo", "photos", locale)
        IntakeKind.EVENTS -> countPhrase(total, "event", "events", locale)
        IntakeKind.ITEMS -> countPhrase(total, "item", "items", locale)
    }

fun formatDuration(seconds: Long, locale: Locale): String {
    if (seconds < 60L) return "less than a minute"
    val minutes = seconds / 60L
    if (minutes < 60L) return countPhrase(minutes, "minute", "minutes", locale)
    return countPhrase(minutes / 60L, "hour", "hours", locale)
}

fun formatDistance(millimetres: Long, system: DistanceSystem, locale: Locale): String {
    val metres = millimetres / 1000.0
    return when (system) {
        DistanceSystem.KILOMETERS -> {
            if (metres < 1000.0) "less than a kilometer" else countPhrase((metres / 1000.0).toLong(), "kilometer", "kilometers", locale)
        }
        DistanceSystem.MILES -> {
            val miles = metres / 1609.344
            if (miles < 1.0) "less than a mile" else countPhrase(miles.toLong(), "mile", "miles", locale)
        }
    }
}

fun formatStarted(epochMs: Long, nowEpochMs: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    val month = MONTHS[date.monthValue - 1]
    return if (date.year == today.year) {
        "$month ${date.dayOfMonth}"
    } else {
        "$month ${date.dayOfMonth}, ${date.year}"
    }
}

fun formatRelative(thenEpochMs: Long, nowEpochMs: Long, zone: ZoneId): String {
    val elapsed = nowEpochMs - thenEpochMs
    if (elapsed < 60_000L) return "just now"
    val minutes = elapsed / 60_000L
    if (minutes < 60L) return "$minutes min ago"
    val hours = minutes / 60L
    if (hours < 24L) return if (hours == 1L) "1 hour ago" else "$hours hours ago"
    val then = Instant.ofEpochMilli(thenEpochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    if (then == today.minusDays(1)) return "yesterday"
    return formatStarted(thenEpochMs, nowEpochMs, zone)
}

private fun countPhrase(count: Long, one: String, many: String, locale: Locale): String {
    val grouped = NumberFormat.getIntegerInstance(locale).format(count)
    return if (count == 1L) "$grouped $one" else "$grouped $many"
}

private val MONTHS = listOf(
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december",
)
