// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

const val INTAKE_AUDIO = "audio"
const val INTAKE_SCREEN = "screen"
const val INTAKE_CAMERA = "camera"
const val INTAKE_LOCATION = "location"
const val INTAKE_CALENDAR = "calendar"
const val INTAKE_IMPORT = "import"
const val INTAKE_SCREENSHOTS = "screenshots"
const val LOCATION_PAYLOAD_NAME = "location.jsonl"

private const val EARTH_RADIUS_METRES = 6_371_000.0

/** What one source has taken in. [total] is seconds, millimetres, or a count. */
data class IntakeReading(
    val total: Long,
    val startedAt: Long,
    val lastAt: Long,
)

/** One kept location fix. A missing or non-finite accuracy contributes no distance. */
data class IntakeFix(
    val atEpochMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyMeters: Double?,
)

/**
 * One source touched by a first insert.
 * [anchor] is the location fix to store, or null when the stored anchor stays put.
 */
data class IntakeDelta(
    val sourceId: String,
    val add: Long,
    val materialAt: Long,
    val anchor: IntakeFix?,
)

/**
 * [startedAt] is the apply clock and never moves. [lastAt] is the material time and only moves forward.
 * A zero [add] still records a row when [touch] is true.
 */
fun advanceIntake(
    existing: IntakeReading?,
    add: Long,
    materialAt: Long,
    now: Long,
    touch: Boolean,
): IntakeReading? {
    if (!touch) return existing
    if (existing == null) return IntakeReading(total = add, startedAt = now, lastAt = materialAt)
    return IntakeReading(
        total = existing.total + add,
        startedAt = existing.startedAt,
        lastAt = maxOf(existing.lastAt, materialAt),
    )
}

fun intakeSeenKey(event: String, occurrence: String?): String = event + "\u0000" + (occurrence ?: "")

/** Per-source adds for one first insert. Calendar files are not counted here. */
fun segmentIntakeDeltas(
    files: List<SegmentFileRow>,
    fixes: List<IntakeFix>,
    anchor: IntakeFix?,
): List<IntakeDelta> {
    val deltas = ArrayList<IntakeDelta>(4)
    durationDelta(files, INTAKE_AUDIO)?.let { deltas += it }
    durationDelta(files, INTAKE_SCREEN)?.let { deltas += it }
    cameraDelta(files)?.let { deltas += it }
    countDelta(files, INTAKE_SCREENSHOTS)?.let { deltas += it }
    locationDelta(fixes, anchor)?.let { deltas += it }
    return deltas
}

/**
 * Sum each file's span in milliseconds, then floor once.
 * A finished span over zero counts as at least one second.
 */
private fun durationDelta(files: List<SegmentFileRow>, sourceId: String): IntakeDelta? {
    var millis = 0L
    var materialAt = Long.MIN_VALUE
    for (row in files) {
        if (row.sourceId != sourceId) continue
        val span = row.captureEndEpochMs - row.captureStartEpochMs
        if (span <= 0L) continue
        millis += span
        if (row.captureEndEpochMs > materialAt) materialAt = row.captureEndEpochMs
    }
    if (millis <= 0L) return null
    val seconds = millis / 1000L
    return IntakeDelta(sourceId, if (seconds == 0L) 1L else seconds, materialAt, null)
}

private fun cameraDelta(files: List<SegmentFileRow>): IntakeDelta? = countDelta(files, INTAKE_CAMERA)

private fun countDelta(files: List<SegmentFileRow>, sourceId: String): IntakeDelta? {
    val kept = files.filter { it.sourceId == sourceId && it.byteSize > 0L }
    if (kept.isEmpty()) return null
    return IntakeDelta(sourceId, kept.size.toLong(), kept.maxOf { it.captureEndEpochMs }, null)
}

/**
 * Internal steps of this segment always count. The step from the stored anchor counts only when this
 * segment's earliest fix is strictly later. An older segment does not move the anchor.
 * A step counts only when its length is strictly greater than both accuracies.
 */
fun locationDelta(fixes: List<IntakeFix>, anchor: IntakeFix?): IntakeDelta? {
    val kept = keptFixes(fixes)
    if (kept.isEmpty()) return null
    var millimetres = 0L
    val earliest = kept.first()
    val latest = kept.last()
    if (anchor != null && earliest.atEpochMs > anchor.atEpochMs) {
        millimetres += stepMillimetres(anchor, earliest)
    }
    for (index in 1 until kept.size) {
        millimetres += stepMillimetres(kept[index - 1], kept[index])
    }
    val newAnchor = if (anchor == null || latest.atEpochMs > anchor.atEpochMs) latest else null
    return IntakeDelta(INTAKE_LOCATION, millimetres, latest.atEpochMs, newAnchor)
}

fun keptFixes(fixes: List<IntakeFix>): List<IntakeFix> {
    val sorted = fixes.filter { it.lat.isFinite() && it.lon.isFinite() }.sortedBy { it.atEpochMs }
    if (sorted.isEmpty()) return emptyList()
    val out = ArrayList<IntakeFix>(sorted.size)
    var previousAt: Long? = null
    for (fix in sorted) {
        if (fix.atEpochMs == previousAt) continue
        out += fix
        previousAt = fix.atEpochMs
    }
    return out
}

fun stepMillimetres(from: IntakeFix, to: IntakeFix): Long {
    val accFrom = usableAccuracy(from.accuracyMeters) ?: return 0L
    val accTo = usableAccuracy(to.accuracyMeters) ?: return 0L
    val metres = haversineMetres(from.lat, from.lon, to.lat, to.lon)
    if (!metres.isFinite() || metres <= accFrom || metres <= accTo) return 0L
    return round(metres * 1000.0).toLong()
}

fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(p1) * cos(p2) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_METRES * c
}

/** Skips a bad line. Never throws. */
fun parseLocationFixes(text: String): List<IntakeFix> {
    val out = ArrayList<IntakeFix>()
    var start = 0
    while (start <= text.length) {
        val newline = text.indexOf('\n', start)
        val end = if (newline < 0) text.length else newline
        if (end > start) {
            val line = text.substring(start, end).trim()
            if (line.isNotEmpty()) parseLocationLine(line)?.let { out += it }
        }
        if (newline < 0) break
        start = newline + 1
    }
    return out
}

/** Missing or unreadable payload is no fixes. The segment insert still proceeds. */
fun readLocationFixes(directory: File?): List<IntakeFix> {
    if (directory == null || !directory.isDirectory) return emptyList()
    val file = File(directory, LOCATION_PAYLOAD_NAME)
    if (!file.isFile) return emptyList()
    return try {
        parseLocationFixes(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        emptyList()
    }
}

private fun usableAccuracy(accuracy: Double?): Double? {
    if (accuracy == null || !accuracy.isFinite() || accuracy < 0.0) return null
    return accuracy
}

private val timestampPattern = Regex(""""timestamp"\s*:\s*(-?\d+)""")
private val latPattern = Regex(""""lat"\s*:\s*(-?(?:\d+\.\d+|\d+|\.\d+)(?:[eE][+-]?\d+)?)""")
private val lonPattern = Regex(""""lon"\s*:\s*(-?(?:\d+\.\d+|\d+|\.\d+)(?:[eE][+-]?\d+)?)""")
private val accuracyPattern = Regex(""""accuracy"\s*:\s*(-?(?:\d+\.\d+|\d+|\.\d+)(?:[eE][+-]?\d+)?)""")

private fun parseLocationLine(line: String): IntakeFix? {
    return try {
        val timestamp = timestampPattern.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val lat = latPattern.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val lon = lonPattern.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        val accuracyRaw = accuracyPattern.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        val accuracy = if (accuracyRaw != null && accuracyRaw.isFinite()) accuracyRaw else null
        IntakeFix(timestamp, lat, lon, accuracy)
    } catch (_: Exception) {
        null
    }
}
