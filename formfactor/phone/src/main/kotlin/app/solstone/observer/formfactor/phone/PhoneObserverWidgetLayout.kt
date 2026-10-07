// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

/**
 * What the home-screen widget shows at the size it has: which of its facts survive, in what order.
 *
 * A 3x1 widget has room for about two lines, and at 200% text for one, while as many as eight
 * facts can be live at once (the state word, the sync text, a diagnosis and up to three notices,
 * each with its sub-line). Before this, every fact was a line and the stack overflowed the cell:
 * the top line ran under the rounded corner and the last one was cut mid-word.
 *
 * 🔴 **A line is shown whole or not at all.** A fact cut mid-word reads as a different fact, and a
 * clipped `confirm the mark` is no longer the held line. So lines are fitted, not clipped: each is
 * measured at the widget's real width and text scale, and one that does not fit is dropped whole.
 * ⚠ The one exception is the first line by priority, which always shows. When even it cannot fit,
 * it takes every line there is, alone, and ends in an ellipsis.
 *
 * **Display order never changes:** the header (source and state word), the diagnosis that explains
 * it, the sync text, then the notices. **Priority is what changes**, because the line that
 * survives at the smallest size is the worst signal:
 * - a fault puts the header first, then its diagnosis: the verdict and its reason;
 * - otherwise the sync text goes first. The switch already shows on or off, and the sync text
 *   carries the facts nothing else on the widget can (held, offline, not paired, a gap).
 *
 * Notices are lead and sub-line pairs, so once one does not fit, none after it is shown: a sub-line
 * never appears without its lead. Everything the widget drops is in the status pane, one tap away.
 */
enum class PhoneWidgetLineKind {
    HEADER,
    DIAGNOSIS,
    SYNC,
    NOTICE,
}

data class PhoneWidgetLine(
    val kind: PhoneWidgetLineKind,
    val text: String,
    /** How many lines it takes at this width and text scale; the renderer's `maxLines`. */
    val lines: Int = 1,
)

/** Measures how many lines [text] wraps to at the widget's content width. */
fun interface PhoneWidgetTextMeasure {
    fun lineCount(text: String, kind: PhoneWidgetLineKind): Int
}

/** The header: the source label and its state word, in the shape of the notification's `intake · on`. */
fun phoneWidgetHeaderText(model: PhoneObserverWidgetModel, sourceLabel: String): String =
    "$sourceLabel · ${model.stateWord}"

/** Every fact the widget could show, in display order. */
fun phoneWidgetCandidateLines(model: PhoneObserverWidgetModel, sourceLabel: String): List<PhoneWidgetLine> =
    buildList {
        add(PhoneWidgetLine(PhoneWidgetLineKind.HEADER, phoneWidgetHeaderText(model, sourceLabel)))
        model.diagnosis?.let { add(PhoneWidgetLine(PhoneWidgetLineKind.DIAGNOSIS, it)) }
        add(PhoneWidgetLine(PhoneWidgetLineKind.SYNC, model.syncText))
        model.syncDetail
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.forEach { add(PhoneWidgetLine(PhoneWidgetLineKind.NOTICE, it)) }
    }

private fun priorityOf(kind: PhoneWidgetLineKind, needsAttention: Boolean): Int =
    if (needsAttention) {
        when (kind) {
            PhoneWidgetLineKind.HEADER -> 0
            PhoneWidgetLineKind.DIAGNOSIS -> 1
            PhoneWidgetLineKind.SYNC -> 2
            PhoneWidgetLineKind.NOTICE -> 3
        }
    } else {
        when (kind) {
            PhoneWidgetLineKind.SYNC -> 0
            PhoneWidgetLineKind.HEADER -> 1
            PhoneWidgetLineKind.DIAGNOSIS -> 2
            PhoneWidgetLineKind.NOTICE -> 3
        }
    }

/**
 * The lines that fit in [maxLines] text lines, in display order, each carrying its measured height.
 *
 * [maxLines] below 1 is treated as 1: a widget always says something.
 */
fun fitPhoneWidgetLines(
    model: PhoneObserverWidgetModel,
    sourceLabel: String,
    maxLines: Int,
    measure: PhoneWidgetTextMeasure,
): List<PhoneWidgetLine> {
    val budget = maxLines.coerceAtLeast(1)
    val candidates = phoneWidgetCandidateLines(model, sourceLabel)
        .map { it.copy(lines = measure.lineCount(it.text, it.kind).coerceAtLeast(1)) }
    // Stable sort: notices keep their order among themselves.
    val byPriority = candidates.withIndex().sortedBy { priorityOf(it.value.kind, model.needsAttention) }
    // The first line by priority always shows: whole if it fits, and otherwise in every line
    // there is, ending in an ellipsis, with nothing else beside it.
    val (firstIndex, first) = byPriority.first()
    if (first.lines > budget) return listOf(first.copy(lines = budget))
    val kept = sortedSetOf(firstIndex)
    var remaining = budget - first.lines
    var noticesClosed = false
    for ((index, line) in byPriority.drop(1)) {
        if (line.kind == PhoneWidgetLineKind.NOTICE && noticesClosed) continue
        if (line.lines <= remaining) {
            kept += index
            remaining -= line.lines
        } else if (line.kind == PhoneWidgetLineKind.NOTICE) {
            noticesClosed = true
        }
    }
    return kept.map { candidates[it] }
}
