// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

import app.solstone.core.model.WireKeys
import app.solstone.core.pl.parseJson
import app.solstone.core.segment.wireKeys
import app.solstone.core.sources.SCREENSHOTS_STREAM
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

const val SCREENSHOT_META_V = 1

enum class TakenFrom { PLATFORM, FILE }

data class ScreenshotWhen(val capturedAt: String, val takenFrom: TakenFrom, val epochMs: Long)

fun fileNameFor(kind: ShotKind): String = when (kind) {
    ShotKind.PNG -> "screenshot_screen.png"
    ShotKind.JPEG -> "screenshot_screen.jpg"
    ShotKind.WEBP -> "screenshot_screen.webp"
}

fun mediaTypeFor(kind: ShotKind): String = when (kind) {
    ShotKind.PNG -> "image/png"
    ShotKind.JPEG -> "image/jpeg"
    ShotKind.WEBP -> "image/webp"
}

/**
 * The platform's DateTimeOriginal is the capture, in the zone where it was taken.
 * A file time is used only when that record is missing.
 */
fun screenshotWhen(localDateTime: String?, modifiedAtMs: Long?, zone: ZoneId, offset: String? = null): ScreenshotWhen? {
    if (localDateTime != null) {
        val parsed = runCatching {
            LocalDateTime.parse(localDateTime, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        }.getOrNull() ?: return modifiedWhen(modifiedAtMs, zone)
        val stored = offset?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        val whenAt = if (stored != null) {
            parsed.atOffset(stored).truncatedTo(ChronoUnit.SECONDS)
        } else {
            parsed.atZone(zone).truncatedTo(ChronoUnit.SECONDS).toOffsetDateTime()
        }
        return ScreenshotWhen(formatCapturedAt(whenAt), TakenFrom.PLATFORM, whenAt.toInstant().toEpochMilli())
    }
    return modifiedWhen(modifiedAtMs, zone)
}

private fun modifiedWhen(modifiedAtMs: Long?, zone: ZoneId): ScreenshotWhen? {
    if (modifiedAtMs == null) return null
    val zoned = Instant.ofEpochMilli(modifiedAtMs).atZone(zone).truncatedTo(ChronoUnit.SECONDS)
    return ScreenshotWhen(formatCapturedAt(zoned.toOffsetDateTime()), TakenFrom.FILE, zoned.toInstant().toEpochMilli())
}

fun formatCapturedAt(value: OffsetDateTime): String {
    val truncated = value.truncatedTo(ChronoUnit.SECONDS)
    return if (truncated.offset == ZoneOffset.UTC) {
        DateTimeFormatter.ISO_INSTANT.format(truncated.toInstant())
    } else {
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(truncated)
    }
}

/** Day and segment are the capture's own clock, and the length is one second. */
fun screenshotKeys(capturedAt: String): WireKeys {
    val parsed = OffsetDateTime.parse(capturedAt)
    val start = parsed.toInstant().toEpochMilli()
    return wireKeys(start, start + 1000L, parsed.offset)
}

fun screenshotMeta(capturedAt: String, takenFrom: TakenFrom): LinkedHashMap<String, Any?> = linkedMapOf(
    "captured_at" to capturedAt,
    "taken_from" to takenFrom.name.lowercase(),
    "v" to SCREENSHOT_META_V,
)

/** Unknown on a failed read. False only when a valid status omits the capability. */
fun parseScreenshotsCapability(status: Int, body: String): Boolean? {
    if (status != 200) return null
    val root = runCatching { parseJson(body) as? Map<*, *> }.getOrNull() ?: return null
    if (!root.containsKey("capabilities")) return false
    val caps = root["capabilities"] as? List<*> ?: return null
    if (caps.any { it !is String }) return null
    return SCREENSHOTS_STREAM in caps
}
