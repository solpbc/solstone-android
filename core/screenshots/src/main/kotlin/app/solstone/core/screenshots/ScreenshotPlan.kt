// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

import java.time.LocalDate
import java.time.ZoneId

enum class HistoryChoice {
    LAST_30_DAYS,
    LAST_6_MONTHS,
    LAST_YEAR,
    ALL,
    NOTHING_BEFORE_TODAY,
}

data class PauseSpan(val startInclusiveMs: Long, val endExclusiveMs: Long?)

/** Today's screenshots are included. [HistoryChoice.ALL] has no lower bound. */
fun historyLowerBound(choice: HistoryChoice, today: LocalDate, zone: ZoneId): Long = when (choice) {
    HistoryChoice.NOTHING_BEFORE_TODAY -> today.atStartOfDay(zone).toInstant().toEpochMilli()
    HistoryChoice.LAST_30_DAYS -> today.minusDays(30).atStartOfDay(zone).toInstant().toEpochMilli()
    HistoryChoice.LAST_6_MONTHS -> today.minusMonths(6).atStartOfDay(zone).toInstant().toEpochMilli()
    HistoryChoice.LAST_YEAR -> today.minusYears(1).atStartOfDay(zone).toInstant().toEpochMilli()
    HistoryChoice.ALL -> Long.MIN_VALUE
}

fun pausedAt(atMs: Long, spans: List<PauseSpan>): Boolean =
    spans.any { atMs >= it.startInclusiveMs && (it.endExclusiveMs == null || atMs < it.endExclusiveMs) }

/** A capture during a pause, or before the chosen bound, is not brought in. */
fun eligibleCapture(atMs: Long, lowerBoundMs: Long, spans: List<PauseSpan>): Boolean =
    atMs >= lowerBoundMs && !pausedAt(atMs, spans)

enum class ReceivedEnd { DELIVERED, REFUSED_TYPE, REFUSED_SIZE, UNREADABLE, GONE }

data class ReceivedHash(val sha256: String, val end: ReceivedEnd)

/** What the journal paired now already ended. A new pairing clears the record ([firstSetupAgain]). */
fun alreadyWithJournal(rows: List<ReceivedHash>, sha256: String): Boolean =
    rows.any { it.sha256 == sha256 }

fun confirmLabel(count: Int): String = when (count) {
    0 -> "start from today"
    1 -> "bring in 1 screenshot"
    else -> "bring in $count screenshots"
}

/** `nothing from before today` names the start, even when today's folder has screenshots. */
fun confirmLabel(choice: HistoryChoice, count: Int): String =
    if (choice == HistoryChoice.NOTHING_BEFORE_TODAY || count == 0) "start from today" else confirmLabel(count)

enum class ShotFate { SKIP, COUNT, SEND, ALREADY, REFUSED_SIZE, REFUSED_TYPE, UNREADABLE }

/**
 * A file the classifier cannot tell is a screenshot is skipped.
 * One the journal paired now already ended is not sent again.
 * Before the owner confirms, an eligible screenshot is only counted.
 */
fun shotFate(
    read: ShotRead,
    atMs: Long?,
    sha256: String?,
    received: List<ReceivedHash>,
    lowerBoundMs: Long,
    pauses: List<PauseSpan>,
    confirmed: Boolean,
): ShotFate {
    val ended = when (read) {
        ShotRead.NotScreenshot -> return ShotFate.SKIP
        is ShotRead.Ended -> read.end
        is ShotRead.Shot -> null
    }
    if (atMs == null || !eligibleCapture(atMs, lowerBoundMs, pauses)) return ShotFate.SKIP
    if (sha256 != null && alreadyWithJournal(received, sha256)) return ShotFate.ALREADY
    return when (ended) {
        ShotEnd.REFUSED_SIZE -> ShotFate.REFUSED_SIZE
        ShotEnd.REFUSED_TYPE -> ShotFate.REFUSED_TYPE
        ShotEnd.UNREADABLE -> ShotFate.UNREADABLE
        null -> if (confirmed) ShotFate.SEND else ShotFate.COUNT
    }
}

fun backfillOpen(done: Int, total: Int): Boolean = total > 0 && done < total

/** Where phones save screenshots: AOSP and Pixel under Pictures, Samsung under DCIM. */
val SCREENSHOT_FOLDERS = listOf("Pictures/Screenshots", "DCIM/Screenshots")

/**
 * The folder the system picker opens at. [modifiedAt] answers a folder's last change, or null when
 * it is not there; the one written to most recently wins, so the picker opens where this phone
 * saves its screenshots. With neither present it opens at Pictures/Screenshots.
 */
fun screenshotsPickerFolder(modifiedAt: (String) -> Long?): String =
    SCREENSHOT_FOLDERS
        .mapNotNull { folder -> modifiedAt(folder)?.let { folder to it } }
        .maxByOrNull { it.second }
        ?.first
        ?: SCREENSHOT_FOLDERS.first()

/**
 * S22's `{path}` from a device-storage tree's document id: `primary:DCIM/Screenshots` reads
 * `DCIM › Screenshots`. The caller passes null for another provider, whose ids are not paths, and
 * that, or an id that names no path, reads as [fallback].
 */
fun screenshotsFolderPath(treeDocumentId: String?, fallback: String): String {
    val path = treeDocumentId?.substringAfter(':', "")?.trim('/').orEmpty()
    if (path.isEmpty()) return fallback
    return path.split('/').filter { it.isNotEmpty() }.joinToString(" › ")
}
