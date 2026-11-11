// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

/**
 * Under the largest text and display size, a word wider than its slot is broken letter by
 * letter (`scre/ens/hots`, `last check/ed`), which the shell's source-name rule forbids. Two
 * answers, in order: give a label and its value or control their own lines when they cannot sit
 * side by side ([rowFit]); and only when one word cannot fit even a whole line, set that text
 * smaller so the word stays whole ([WholeWordText]).
 */

internal enum class RowFit { SIDE_BY_SIDE, STACKED }

/** Side by side only while each side's longest word fits beside the other's. */
internal fun rowFit(startMinPx: Int, endMinPx: Int, gapPx: Int, availablePx: Int): RowFit =
    if (startMinPx + gapPx + endMinPx > availablePx) RowFit.STACKED else RowFit.SIDE_BY_SIDE

/**
 * True when a laid-out text broke a word across lines, or, with no wrapping, ran past its width.
 * [lineEnds] are each line's end offsets as the layout reports them.
 */
internal fun breaksInsideAWord(text: String, lineEnds: List<Int>, overflowedWidth: Boolean): Boolean {
    if (overflowedWidth) return true
    return lineEnds.dropLast(1).any { end ->
        end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace()
    }
}

/**
 * Text that never breaks inside a word. ⚠ Judged from the real layout, not a measurement made
 * beside it: Android scales large text non-linearly, and a computed size still came out too wide
 * (`screen/shots`). When the layout breaks a word, the size steps down and the text lays out again.
 */
@Composable
internal fun WholeWordText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val resolved = LocalTextStyle.current.merge(style)
    var factor by remember(text, resolved) { mutableFloatStateOf(1f) }
    val fitted = if (factor < 1f && resolved.fontSize.isSpecified) {
        resolved.copy(
            fontSize = resolved.fontSize * factor,
            lineHeight = if (resolved.lineHeight.isSpecified) resolved.lineHeight * factor else resolved.lineHeight,
        )
    } else {
        resolved
    }
    Text(
        text = text,
        modifier = modifier,
        style = fitted,
        color = color,
        softWrap = softWrap,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout ->
            val ends = (0 until layout.lineCount).map { layout.getLineEnd(it) }
            if (factor > 0.5f && resolved.fontSize.isSpecified && breaksInsideAWord(text, ends, layout.didOverflowWidth)) {
                factor = (factor * 0.9f).coerceAtLeast(0.5f)
            }
        },
    )
}

/**
 * A label and its value or control: side by side while both fit, otherwise the label takes its own
 * line and the end part goes on the next, at the start ([stackedEndAtEnd] false, a value) or at the
 * end (true, a control such as a switch, as the shell places a tile's switch).
 */
@Composable
internal fun LabelValueRow(
    modifier: Modifier = Modifier,
    gap: Dp = 12.dp,
    stackedEndAtEnd: Boolean = false,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    Layout(contents = listOf(start, end), modifier = modifier) { (startParts, endParts), constraints ->
        val s = startParts.firstOrNull()
        val e = endParts.firstOrNull()
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val gapPx = gap.roundToPx()
        val sMin = s?.minIntrinsicWidth(Constraints.Infinity) ?: 0
        val eMin = e?.minIntrinsicWidth(Constraints.Infinity) ?: 0
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        if (available != Constraints.Infinity && rowFit(sMin, eMin, gapPx, available) == RowFit.STACKED) {
            val sp = s?.measure(loose.copy(maxWidth = available))
            val ep = e?.measure(loose.copy(maxWidth = available))
            val height = (sp?.height ?: 0) + (ep?.height ?: 0)
            layout(available, maxOf(height, constraints.minHeight)) {
                sp?.placeRelative(0, 0)
                ep?.placeRelative(if (stackedEndAtEnd) available - ep.width else 0, sp?.height ?: 0)
            }
        } else {
            val eMax = e?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
            val width = if (available == Constraints.Infinity) (s?.maxIntrinsicWidth(Constraints.Infinity) ?: 0) + gapPx + eMax else available
            val endWidth = maxOf(eMin, minOf(eMax, width - gapPx - sMin))
            val ep = e?.measure(loose.copy(maxWidth = endWidth))
            val sp = s?.measure(loose.copy(maxWidth = maxOf(0, width - gapPx - (ep?.width ?: 0))))
            val height = maxOf(sp?.height ?: 0, ep?.height ?: 0, constraints.minHeight)
            layout(width, height) {
                sp?.placeRelative(0, (height - sp.height) / 2)
                ep?.placeRelative(width - ep.width, (height - ep.height) / 2)
            }
        }
    }
}
