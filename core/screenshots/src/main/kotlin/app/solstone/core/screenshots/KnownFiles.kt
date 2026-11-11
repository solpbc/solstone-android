// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What an earlier folder read already settled about one file, so a check does not open it again.
 * A folder of thousands of photos or screenshots is otherwise read whole on every app open and
 * every background check. A file whose size or change time moved is read again.
 */
data class KnownFile(val documentId: String, val size: Long, val modifiedMs: Long, val verdict: KnownVerdict)

sealed interface KnownVerdict {
    /** The file carries no screenshot record; that cannot change while the bytes do not. */
    data object NotScreenshot : KnownVerdict

    /** This exact file is already in [journalId]. */
    data class InJournal(val journalId: String) : KnownVerdict
}

/** The verdict that still holds for a listed file, or null when it must be read. */
fun knownVerdict(known: KnownFile?, size: Long?, modifiedMs: Long?): KnownVerdict? {
    if (known == null || size == null || modifiedMs == null) return null
    return if (known.size == size && known.modifiedMs == modifiedMs) known.verdict else null
}

fun encodeKnown(files: Collection<KnownFile>): String = buildString {
    for (f in files) {
        val verdict = when (val v = f.verdict) {
            KnownVerdict.NotScreenshot -> "not"
            is KnownVerdict.InJournal -> "in\t${enc(v.journalId)}"
        }
        appendLine("${enc(f.documentId)}\t${f.size}\t${f.modifiedMs}\t$verdict")
    }
}

fun decodeKnown(text: String): Map<String, KnownFile> {
    val out = linkedMapOf<String, KnownFile>()
    for (line in text.lineSequence()) {
        val f = line.split('\t')
        if (f.size < 4) continue
        val size = f[1].toLongOrNull() ?: continue
        val modified = f[2].toLongOrNull() ?: continue
        val verdict = when (f[3]) {
            "not" -> KnownVerdict.NotScreenshot
            "in" -> f.getOrNull(4)?.let { KnownVerdict.InJournal(dec(it)) } ?: continue
            else -> continue
        }
        val id = dec(f[0])
        out[id] = KnownFile(id, size, modified, verdict)
    }
    return out
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun dec(value: String): String = URLDecoder.decode(value, "UTF-8")
