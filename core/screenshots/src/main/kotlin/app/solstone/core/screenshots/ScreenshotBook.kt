// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

import java.net.URLDecoder
import java.net.URLEncoder

data class StagedShot(
    val documentId: String,
    val sha256: String,
    val capturedAt: String,
    val takenFrom: String,
    val segmentId: String,
    val epochMs: Long,
)

data class DocMark(val documentId: String, val end: ReceivedEnd)

/**
 * What this device remembers about the folder grant and the screenshots it has already handled.
 * The received rows are per journal, so pairing a different journal does not drop them and does
 * not treat that journal as already having the file.
 */
data class ScreenshotBook(
    val treeUri: String? = null,
    val folderName: String = "Screenshots",
    val history: HistoryChoice = HistoryChoice.LAST_30_DAYS,
    val confirmed: Boolean = false,
    val reconfirm: Boolean = false,
    val period: Int = 1,
    val lowerBoundMs: Long = Long.MIN_VALUE,
    val frozen: Set<String> = emptySet(),
    val pauses: List<PauseSpan> = emptyList(),
    val received: List<ReceivedHash> = emptyList(),
    val staged: List<StagedShot> = emptyList(),
    val marks: List<DocMark> = emptyList(),
    val lastCheckedMs: Long? = null,
    /** The journal the owner chose how far back for; null until a paired journal is seen. */
    val journalId: String? = null,
    /** The owner paused this source on its own page, apart from a pause of the whole app. */
    val sourcePaused: Boolean = false,
)

/**
 * "Already in your journal" is per journal, so pairing a different journal is a fresh start: the
 * source goes back to asking how far back, as turning it back on does. The first journal seen
 * after a confirm made while unpaired is that confirm's journal. An unpair changes nothing. S17 is
 * not shown: a different journal holds none of these screenshots yet.
 * Returns null when the book is unchanged.
 */
fun followJournal(book: ScreenshotBook, current: String?): ScreenshotBook? = when {
    !book.confirmed || current == null || book.journalId == current -> null
    book.journalId == null -> book.copy(journalId = current)
    else -> book.copy(
        confirmed = false,
        reconfirm = false,
        period = book.period + 1,
        pauses = emptyList(),
        frozen = emptySet(),
        marks = emptyList(),
        journalId = null,
        sourcePaused = false,
    )
}

/**
 * The background check runs only while the source is on: a folder is granted, the owner has chosen
 * how far back to start, and the wish is on. Before the confirm nothing is sent, so a background
 * read would only count screenshots nobody is looking at.
 */
fun backgroundCheckWanted(book: ScreenshotBook, wishOn: Boolean): Boolean =
    book.treeUri != null && book.confirmed && wishOn

fun backfillProgress(book: ScreenshotBook): Pair<Int, Int> {
    val done = book.frozen.count { id -> book.marks.any { it.documentId == id } }
    return done to book.frozen.size
}

fun encodeBook(book: ScreenshotBook): String = buildString {
    appendLine("tree\t${enc(book.treeUri.orEmpty())}")
    appendLine("name\t${enc(book.folderName)}")
    appendLine("history\t${book.history.name}")
    appendLine("confirmed\t${if (book.confirmed) 1 else 0}")
    appendLine("reconfirm\t${if (book.reconfirm) 1 else 0}")
    appendLine("period\t${book.period}")
    appendLine("lower\t${book.lowerBoundMs}")
    book.frozen.forEach { appendLine("frozen\t${enc(it)}") }
    book.pauses.forEach { span ->
        appendLine("pause\t${span.startInclusiveMs}\t${span.endExclusiveMs?.toString().orEmpty()}")
    }
    book.received.forEach {
        appendLine("received\t${enc(it.journalId)}\t${it.sha256}\t${it.end.name}")
    }
    book.staged.forEach {
        appendLine("staged\t${enc(it.documentId)}\t${it.sha256}\t${enc(it.capturedAt)}\t${it.takenFrom}\t${enc(it.segmentId)}\t${it.epochMs}")
    }
    book.marks.forEach { appendLine("mark\t${enc(it.documentId)}\t${it.end.name}") }
    book.lastCheckedMs?.let { appendLine("checked\t$it") }
    book.journalId?.let { appendLine("journal\t${enc(it)}") }
    if (book.sourcePaused) appendLine("paused\t1")
}

fun decodeBook(text: String): ScreenshotBook {
    var tree: String? = null
    var name = "Screenshots"
    var history = HistoryChoice.LAST_30_DAYS
    var confirmed = false
    var reconfirm = false
    var period = 1
    var lower = Long.MIN_VALUE
    val frozen = linkedSetOf<String>()
    val pauses = mutableListOf<PauseSpan>()
    val received = mutableListOf<ReceivedHash>()
    val staged = mutableListOf<StagedShot>()
    val marks = mutableListOf<DocMark>()
    var checked: Long? = null
    var journal: String? = null
    var sourcePaused = false
    for (line in text.lineSequence()) {
        if (line.isEmpty()) continue
        val f = line.split('\t')
        when (f[0]) {
            "tree" -> tree = dec(f.getOrNull(1).orEmpty()).ifEmpty { null }
            "name" -> name = dec(f.getOrNull(1).orEmpty()).ifEmpty { "Screenshots" }
            "history" -> history = runCatching { HistoryChoice.valueOf(f[1]) }.getOrDefault(HistoryChoice.LAST_30_DAYS)
            "confirmed" -> confirmed = f.getOrNull(1) == "1"
            "reconfirm" -> reconfirm = f.getOrNull(1) == "1"
            "period" -> period = f.getOrNull(1)?.toIntOrNull() ?: 1
            "lower" -> lower = f.getOrNull(1)?.toLongOrNull() ?: Long.MIN_VALUE
            "frozen" -> frozen += dec(f.getOrNull(1).orEmpty())
            "pause" -> {
                val start = f.getOrNull(1)?.toLongOrNull() ?: continue
                val end = f.getOrNull(2)?.toLongOrNull()
                pauses += PauseSpan(start, end)
            }
            "received" -> {
                val journal = dec(f.getOrNull(1).orEmpty())
                val sha = f.getOrNull(2).orEmpty()
                val end = runCatching { ReceivedEnd.valueOf(f[3]) }.getOrNull() ?: continue
                if (journal.isNotEmpty() && sha.isNotEmpty()) received += ReceivedHash(journal, sha, end)
            }
            "staged" -> {
                if (f.size < 7) continue
                val epoch = f[6].toLongOrNull() ?: continue
                staged += StagedShot(dec(f[1]), f[2], dec(f[3]), f[4], dec(f[5]), epoch)
            }
            "mark" -> {
                val end = runCatching { ReceivedEnd.valueOf(f[2]) }.getOrNull() ?: continue
                marks += DocMark(dec(f[1]), end)
            }
            "checked" -> checked = f.getOrNull(1)?.toLongOrNull()
            "journal" -> journal = dec(f.getOrNull(1).orEmpty()).ifEmpty { null }
            "paused" -> sourcePaused = f.getOrNull(1) == "1"
        }
    }
    return ScreenshotBook(tree, name, history, confirmed, reconfirm, period, lower, frozen, pauses, received, staged, marks, checked, journal, sourcePaused)
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun dec(value: String): String = URLDecoder.decode(value, "UTF-8")
