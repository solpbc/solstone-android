// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class DistanceSystem { MILES, KILOMETERS }

enum class IntakeKind { DURATION, DISTANCE, PHOTOS, EVENTS, NOTES, SCREENSHOTS, ITEMS }

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
    "obsidian" -> IntakeKind.NOTES
    "screenshots" -> IntakeKind.SCREENSHOTS
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
    reading ?: return phoneText("android.intake.nothingyet")
    val total = formatTotal(reading.total, kind, system, locale)
    val started = formatStarted(reading.startedAt, nowEpochMs, zone)
    return phoneText("android.intake.totalsince" , "total" to (total), "started" to (started))
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
        IntakeKind.PHOTOS -> phoneText("android.intake.photos", "count" to (total), "_locale" to (locale))
        IntakeKind.EVENTS -> phoneText("android.intake.events", "count" to (total), "_locale" to (locale))
        IntakeKind.NOTES -> phoneText("android.intake.notes", "count" to (total), "_locale" to (locale))
        IntakeKind.SCREENSHOTS -> phoneText("android.intake.screenshots", "count" to (total), "_locale" to (locale))
        IntakeKind.ITEMS -> phoneText("android.intake.items", "count" to (total), "_locale" to (locale))
    }

fun formatDuration(seconds: Long, locale: Locale): String {
    if (seconds < 60L) return phoneText("android.intake.lessthanminute")
    val minutes = seconds / 60L
    if (minutes < 60L) return phoneText("android.intake.minutes", "count" to (minutes), "_locale" to (locale))
    return phoneText("android.intake.hours", "count" to (minutes / 60L), "_locale" to (locale))
}

fun formatDistance(millimetres: Long, system: DistanceSystem, locale: Locale): String {
    val metres = millimetres / 1000.0
    return when (system) {
        DistanceSystem.KILOMETERS -> {
            if (metres < 1000.0) phoneText("android.intake.lessthankilometer") else phoneText("android.intake.kilometers", "count" to ((metres / 1000.0).toLong()), "_locale" to (locale))
        }
        DistanceSystem.MILES -> {
            val miles = metres / 1609.344
            if (miles < 1.0) phoneText("android.intake.lessthanmile") else phoneText("android.intake.miles", "count" to (miles.toLong()), "_locale" to (locale))
        }
    }
}

fun formatStarted(epochMs: Long, nowEpochMs: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    val month = MONTHS[date.monthValue - 1]
    return if (date.year == today.year) {
        phoneText("android.intake.startedthismonth", "month" to (month), "day" to (date.dayOfMonth))
    } else {
        phoneText("android.intake.startedyear", "month" to (month), "day" to (date.dayOfMonth), "year" to (date.year))
    }
}

fun formatRelative(thenEpochMs: Long, nowEpochMs: Long, zone: ZoneId): String {
    val elapsed = nowEpochMs - thenEpochMs
    if (elapsed < 60_000L) return phoneText("android.about.freshnessnow")
    val minutes = elapsed / 60_000L
    if (minutes < 60L) return phoneText("android.intake.minago", "count" to (minutes))
    val hours = minutes / 60L
    if (hours < 24L) return phoneText("android.about.freshnesshours", "count" to (hours))
    val then = Instant.ofEpochMilli(thenEpochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    if (then == today.minusDays(1)) return phoneText("android.intake.yesterday")
    return formatStarted(thenEpochMs, nowEpochMs, zone)
}

private val MONTHS = listOf(
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december",
)
