// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.JournalVersionRecord

/** Whether the paired journal can take phone imports. */
enum class ImportVersionGate {
    /** The journal answers a repeated save or start with a replay, so imports may go. */
    SUPPORTED,

    /** No version recorded yet for this pairing; the phone is still checking. */
    UNKNOWN,

    /** The journal is older than the first version that can take imports, or its version can't be read. */
    TOO_OLD,
}

/** The first journal release whose import save and start are safe to repeat. */
internal val MIN_IMPORT_JOURNAL_VERSION = listOf(2, 0, 52)

/**
 * The one gate for phone imports, shared by delivery and by what the app says about waiting
 * imports, so the two can never disagree.
 *
 * A record counts only when it belongs to this pairing's journal (its instance and certificate
 * chain). The version may carry a leading `v` and a suffix after its numbers; each dotted number
 * is compared as a number, so 2.0.100 is newer than 2.0.52.
 */
fun importVersionGate(record: JournalVersionRecord?, instanceId: String, caChainFingerprint: String): ImportVersionGate {
    if (record == null || record.instanceId != instanceId || record.caChainFingerprint != caChainFingerprint) {
        return ImportVersionGate.UNKNOWN
    }
    val parts = numericVersion(record.version) ?: return ImportVersionGate.TOO_OLD
    return if (compareVersions(parts, MIN_IMPORT_JOURNAL_VERSION) >= 0) {
        ImportVersionGate.SUPPORTED
    } else {
        ImportVersionGate.TOO_OLD
    }
}

private fun numericVersion(raw: String): List<Int>? {
    val numbers = raw.trim().trimStart('v').takeWhile { it.isDigit() || it == '.' }
    val parts = numbers.split('.')
    if (parts.size < 3 || parts.any { it.isEmpty() }) return null
    return parts.map { it.toIntOrNull() ?: return null }
}

private fun compareVersions(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}
