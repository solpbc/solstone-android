// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val GAP_LOSS_FACT = "gap-loss"

const val GAP_HEADLINE = "there's a gap in your journal"
const val GAP_UNDATED = "some of what you shared didn't reach it."
const val GAP_TODAY = "some of what you shared today didn't reach it."
const val GAP_YESTERDAY = "some of what you shared yesterday didn't reach it."

val GAP_REASONS: Set<String> = setOf(
    "uncommitted window",
    "recording did not start",
    "record not written",
    "undeliverable",
    "empty recording",
    "remux failed",
    "no bytes",
    "same bounds",
    "custody unproven",
    "seal failed",
    "delivered",
    "screen restart",
    "no captured frame",
    "screen did not start",
    "invalid payload",
    "empty draft",
    "late fragment",
    "legacy marker",
    "unreadable record",
)

val GAP_SOURCES: Set<String> = setOf(
    "audio",
    "screen",
    "camera",
    "location",
    "metadata",
    "other",
    "unknown",
    "spool",
)

val GAP_KINDS: Set<String> = setOf("loss", "diagnostic", "legacy")

private val MATERIAL_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
private val LOCAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")

/** A manifest source id outside the named capture ids is `other`. `unknown` is only the converted marker. */
fun gapSourceFor(sourceId: String): String = when (sourceId) {
    "audio", "screen", "camera", "location", "metadata" -> sourceId
    else -> "other"
}

fun gapMaterialOk(material: String): Boolean = MATERIAL_PATTERN.matches(material)

/**
 * ADTS existed when a header parses at offset 0 and [fileSize] covers that frame.
 * No parseable header existed only when [fileSize] is greater than 8191.
 * A file shorter than its first declared frame did not exist.
 * [prefix] is the leading bytes; callers pass at least the header when the file has it.
 */
fun adtsExisted(prefix: ByteArray, fileSize: Long): Boolean {
    val frameLength = adtsFrameLength(prefix) ?: return fileSize > 8191L
    return fileSize >= frameLength
}

/** Reads at most the ADTS header and applies [adtsExisted]. A missing file did not exist. */
fun adtsFileExisted(path: Path): Boolean {
    if (!Files.isRegularFile(path)) return false
    val size = Files.size(path)
    val count = minOf(9L, size).toInt()
    val prefix = ByteArray(count)
    if (count > 0) {
        Files.newInputStream(path).use { stream ->
            var offset = 0
            while (offset < count) {
                val read = stream.read(prefix, offset, count - offset)
                if (read < 0) break
                offset += read
            }
        }
    }
    return adtsExisted(prefix, size)
}

fun adtsFrameLength(prefix: ByteArray): Int? {
    if (prefix.size < 7) return null
    val b0 = prefix[0].toInt() and 0xFF
    val b1 = prefix[1].toInt() and 0xFF
    if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return null
    val headerLength = if ((b1 and 0x01) != 0) 7 else 9
    if (prefix.size < headerLength) return null
    val b3 = prefix[3].toInt() and 0xFF
    val b4 = prefix[4].toInt() and 0xFF
    val b5 = prefix[5].toInt() and 0xFF
    val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 and 0xE0) shr 5)
    if (frameLength < headerLength) return null
    return frameLength
}

/** m4a, mp4, and any other payload file existed when its length is greater than 0. */
fun payloadBytesExisted(length: Long): Boolean = length > 0L

/**
 * A samples.idx line is `pts,size,flags`. Bit 2 is codec configuration and is not a captured frame.
 */
fun screenIndexHasRealSample(indexText: String): Boolean {
    return indexText.lineSequence().any { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@any false
        val parts = line.split(',')
        if (parts.size != 3) return@any false
        val flags = parts[2].toIntOrNull() ?: return@any false
        flags and (1 shl 2) == 0
    }
}

/** Found local date is today or one of the previous 6 local dates. Not 6 times 24 hours. */
fun gapFoundInWindow(foundEpochMs: Long, nowEpochMs: Long, zone: ZoneId): Boolean {
    val today = localDate(nowEpochMs, zone)
    val found = localDate(foundEpochMs, zone)
    return !found.isAfter(today) && !found.isBefore(today.minusDays(6))
}

/** Local midnight at the start of [foundEpochMs]'s date plus 7 days. */
fun gapWindowEndMillis(foundEpochMs: Long, zone: ZoneId): Long =
    localDate(foundEpochMs, zone).plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()

/** A failed sweep leaves the gate shut unless this install already opened it. */
fun gapPassCompleted(sweepSucceeded: Boolean, opened: Boolean): Boolean = sweepSucceeded || opened

data class GapDayLoss(
    val capturedEpochMs: Long?,
    val foundEpochMs: Long,
    val seen: Boolean,
)

/**
 * Day line from unseen in-window losses only. An unseen loss outside the window does not change it.
 * Fixed English weekday names.
 */
fun gapDayLine(losses: List<GapDayLoss>, nowEpochMs: Long, zone: ZoneId): String? {
    val inWindow = losses.filter { !it.seen && gapFoundInWindow(it.foundEpochMs, nowEpochMs, zone) }
    if (inWindow.isEmpty()) return null
    val today = localDate(nowEpochMs, zone)
    val captureDates = inWindow.map { loss -> loss.capturedEpochMs?.let { localDate(it, zone) } }
    if (captureDates.any { it == null || it.isBefore(today.minusDays(6)) }) return GAP_UNDATED
    val days = captureDates.filterNotNull().toSortedSet()
    if (days.size == 1) {
        val day = days.first()
        return when {
            day == today -> GAP_TODAY
            day == today.minusDays(1) -> GAP_YESTERDAY
            !day.isBefore(today.minusDays(6)) && day.isBefore(today) ->
                "some of what you shared on ${weekdayName(day)} didn't reach it."
            else -> GAP_UNDATED
        }
    }
    val earliest = days.first()
    return when {
        earliest == today.minusDays(1) -> "some of what you shared since yesterday didn't reach it."
        !earliest.isBefore(today.minusDays(6)) && earliest.isBefore(today) ->
            "some of what you shared since ${weekdayName(earliest)} didn't reach it."
        else -> GAP_UNDATED
    }
}

fun weekdayName(day: LocalDate): String = when (day.dayOfWeek) {
    DayOfWeek.MONDAY -> "monday"
    DayOfWeek.TUESDAY -> "tuesday"
    DayOfWeek.WEDNESDAY -> "wednesday"
    DayOfWeek.THURSDAY -> "thursday"
    DayOfWeek.FRIDAY -> "friday"
    DayOfWeek.SATURDAY -> "saturday"
    DayOfWeek.SUNDAY -> "sunday"
}

data class GapNoticeRow(
    val id: String,
    val kind: String,
    val source: String,
    val reason: String,
    val material: String,
    val capturedEpochMs: Long?,
    val foundEpochMs: Long,
    val seen: Boolean,
    val count: Int,
    val firstEpochMs: Long,
    val lastEpochMs: Long,
    /** Marker modification time for an imported legacy row. Retention uses [foundEpochMs]. */
    val markerEpochMs: Long? = null,
)

sealed interface GapConversionState {
    data object NotRun : GapConversionState
    data class Complete(val count: Int) : GapConversionState
    data class Failed(val reason: String) : GapConversionState
}

/**
 * Last 30 local days, newest first. Every field the technical-details pane shows is in the text:
 * local time, kind, source, reason, short material id, captured time when known, seen or unseen,
 * repeat count, and the conversion state.
 */
fun formatGapTechnicalDetails(
    rows: List<GapNoticeRow>,
    conversion: GapConversionState,
    nowEpochMs: Long,
    zone: ZoneId,
): String {
    val cutoff = localDate(nowEpochMs, zone).minusDays(30)
    val shown = rows
        .filter { !localDate(it.lastEpochMs, zone).isBefore(cutoff) }
        .sortedWith(compareByDescending<GapNoticeRow> { it.lastEpochMs }.thenByDescending { it.id })
    val header = when (conversion) {
        GapConversionState.NotRun -> "not run"
        is GapConversionState.Complete -> "complete ${conversion.count}"
        is GapConversionState.Failed -> "failed ${conversion.reason}"
    }
    if (shown.isEmpty()) return header
    return buildString {
        append(header)
        for (row in shown) {
            append('\n')
            append(formatGapTechnicalRow(row, zone))
        }
    }
}

fun formatGapTechnicalRow(row: GapNoticeRow, zone: ZoneId): String {
    val captured = row.capturedEpochMs?.let { formatGapLocalTime(it, zone) } ?: "unknown"
    val seen = if (row.seen) "seen" else "unseen"
    val marked = row.markerEpochMs?.let { " marked ${formatGapLocalTime(it, zone)}" }.orEmpty()
    return "${formatGapLocalTime(row.lastEpochMs, zone)} ${row.kind} ${row.source} ${row.reason} ${row.material} captured $captured $seen count ${row.count}$marked"
}

/**
 * A material id for one draft or final folder. Day, stream, and folder stay distinct, the alphabet
 * matches [gapMaterialOk], and a long id keeps a hash suffix so two folders cannot share a key.
 */
fun gapDraftMaterial(day: String, stream: String, folder: String): String {
    fun piece(raw: String): String {
        val cleaned = raw.map { ch ->
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-' -> ch
                else -> '_'
            }
        }.joinToString("")
        return cleaned.ifEmpty { "x" }
    }
    val joined = "${piece(day)}.${piece(stream)}.${piece(folder)}"
    if (joined.length <= 64 && gapMaterialOk(joined)) return joined
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(joined.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)
    val room = (64 - digest.length - 1).coerceAtLeast(1)
    val prefix = joined.take(room).trimEnd('.')
    val candidate = "$prefix.$digest"
    return if (gapMaterialOk(candidate)) candidate else digest
}

/** Draft and final folders are `{day}/{stream}/{folder}`. */
fun gapMaterialForDraft(dir: Path): String {
    val folder = dir.fileName?.toString().orEmpty()
    val stream = dir.parent?.fileName?.toString().orEmpty()
    val day = dir.parent?.parent?.fileName?.toString().orEmpty()
    return gapDraftMaterial(day, stream, folder)
}

fun formatGapLocalTime(epochMs: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(LOCAL_TIME)

fun legacyMaterial(sha256: String, fileKey: String = ""): String {
    val hex = sha256.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
    if (hex.isNotEmpty()) return "legacy-${hex.take(12)}"
    return gapDraftMaterial("legacy", "unread", fileKey.ifEmpty { "file" })
}

/** Fact-folder name for one legacy cache file. An unreadable hash is keyed by that file, not shared. */
fun legacyFactFolder(sha256: String, fileName: String): String {
    val hex = sha256.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
    if (hex.isNotEmpty()) return hex
    return gapDraftMaterial("unread", "file", fileName)
}

/**
 * Empty `gap-loss` fact. Created only after a loss row has been flushed.
 * The file and its parent are synced. A failed write deletes a fact this call created,
 * so the folder is left without one and the next pass tries again.
 */
fun writeGapLossFact(
    directory: Path,
    syncDirectory: (Path) -> Unit = ::forceGapDirectory,
): Boolean {
    var created = false
    val fact = directory.resolve(GAP_LOSS_FACT)
    return try {
        Files.createDirectories(directory)
        if (Files.isRegularFile(fact)) {
            FileOutputStream(fact.toFile(), true).use { it.fd.sync() }
            syncDirectory(directory)
            return true
        }
        val temp = Files.createTempFile(directory, "gap-loss-", ".tmp")
        try {
            FileOutputStream(temp.toFile()).use { it.fd.sync() }
            try {
                Files.move(temp, fact, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, fact, StandardCopyOption.REPLACE_EXISTING)
            }
            created = true
            syncDirectory(directory)
            true
        } finally {
            Files.deleteIfExists(temp)
        }
    } catch (_: Exception) {
        if (created) runCatching { Files.deleteIfExists(fact) }
        false
    }
}

fun gapLossFactExists(directory: Path): Boolean =
    runCatching { Files.isRegularFile(directory.resolve(GAP_LOSS_FACT)) }.getOrDefault(false)

internal fun forceGapDirectory(dir: Path) {
    val interrupted = Thread.interrupted()
    try {
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal fun localDate(epochMs: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
