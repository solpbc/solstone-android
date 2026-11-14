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
 * Nothing here names a journal: a new pairing starts the source's first setup again
 * ([firstSetupAgain]), so the received rows are only ever about the journal paired now.
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
    /** The owner paused this source on its own page, apart from a pause of the whole app. */
    val sourcePaused: Boolean = false,
)

/**
 * A new pairing starts this source's first setup again: the page asks how far back, with a fresh
 * count, and nothing comes in until the owner confirms. What the device already holds stays: the
 * folder and its grant, the owner's pause, and the screenshots still waiting to go, which go to the
 * journal paired now. S17 is not shown, as on a first setup.
 */
fun firstSetupAgain(book: ScreenshotBook): ScreenshotBook = book.copy(
    confirmed = false,
    reconfirm = false,
    period = 1,
    frozen = emptySet(),
    marks = emptyList(),
    received = emptyList(),
)

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
        appendLine("received\t${it.sha256}\t${it.end.name}")
    }
    book.staged.forEach {
        appendLine("staged\t${enc(it.documentId)}\t${it.sha256}\t${enc(it.capturedAt)}\t${it.takenFrom}\t${enc(it.segmentId)}\t${it.epochMs}")
    }
    book.marks.forEach { appendLine("mark\t${enc(it.documentId)}\t${it.end.name}") }
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
    // A book written before the record was journal-free named a journal on each received row and the
    // journal it was confirmed for. Only that journal's rows still say what the journal paired now holds.
    val legacyReceived = mutableListOf<Pair<String, ReceivedHash>>()
    val staged = mutableListOf<StagedShot>()
    val marks = mutableListOf<DocMark>()
    var checked: Long? = null
    var confirmedFor: String? = null
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
            "received" -> if (f.size >= 4) {
                val legacyJournal = dec(f[1])
                val end = runCatching { ReceivedEnd.valueOf(f[3]) }.getOrNull() ?: continue
                if (legacyJournal.isNotEmpty() && f[2].isNotEmpty()) legacyReceived += legacyJournal to ReceivedHash(f[2], end)
            } else {
                val sha = f.getOrNull(1).orEmpty()
                val end = runCatching { ReceivedEnd.valueOf(f[2]) }.getOrNull() ?: continue
                if (sha.isNotEmpty()) received += ReceivedHash(sha, end)
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
            "journal" -> confirmedFor = dec(f.getOrNull(1).orEmpty()).ifEmpty { null }
            "paused" -> sourcePaused = f.getOrNull(1) == "1"
        }
    }
    if (confirmedFor != null) received += legacyReceived.filter { it.first == confirmedFor }.map { it.second }
    return ScreenshotBook(
        treeUri = tree, folderName = name, history = history, confirmed = confirmed, reconfirm = reconfirm,
        period = period, lowerBoundMs = lower, frozen = frozen, pauses = pauses,
        received = received.distinctBy { it.sha256 }, staged = staged, marks = marks, lastCheckedMs = checked,
        sourcePaused = sourcePaused,
    )
}

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun dec(value: String): String = URLDecoder.decode(value, "UTF-8")
