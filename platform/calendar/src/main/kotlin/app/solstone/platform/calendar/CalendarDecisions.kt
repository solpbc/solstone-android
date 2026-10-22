// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.calendar

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal val CALENDAR_EARLIEST_QUERY_MS: Long =
    LocalDate.of(1900, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun queryFloor(lower: Long): Long =
    if (lower == Long.MIN_VALUE) CALENDAR_EARLIEST_QUERY_MS else lower.coerceAtLeast(CALENDAR_EARLIEST_QUERY_MS)

fun calendarSelectionFingerprint(ids: Set<Long>, span: String): String {
    val material = ids.sorted().joinToString(",") + "\u0000" + span
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun confirmReady(
    status: String?,
    rowGeneration: Long?,
    currentGeneration: Long,
    rowFingerprint: String?,
    fingerprint: String,
): Boolean = status == "ready" && rowGeneration == currentGeneration && rowFingerprint == fingerprint

fun resumeUnchanged(confirmed: Boolean, selectionMatches: Boolean, spanMatches: Boolean): Boolean =
    confirmed && selectionMatches && spanMatches

/** A denial turns the source off only when nothing has been confirmed. */
fun denyLeavesSourceOff(confirmed: Boolean): Boolean = !confirmed

/** A grant never imports and never enables on its own. Resume is a separate step. */
fun grantEnablesSource(): Boolean = false

fun shouldScheduleCalendarWork(confirmed: Boolean, wishOn: Boolean, permission: Boolean): Boolean =
    confirmed && wishOn && permission

internal fun encodePerCalendar(counts: Map<Long, Int>): String =
    counts.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }

internal fun decodePerCalendar(raw: String): Map<Long, Int> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(',').mapNotNull { part ->
        val bits = part.split(':', limit = 2)
        val id = bits.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val count = bits.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        id to count
    }.toMap()
}

internal fun epochMillis(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun instantOf(epochMs: Long): Instant = Instant.ofEpochMilli(epochMs)
