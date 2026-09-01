// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The journal mark — the generic, no-identity-yet form.
 *
 * 🔴 **Android rendered nothing here, in any state.** `journal-mark.md` § 7 records it
 * exactly that way ("Android rendered nothing in *any* state — an empty composable"),
 * and it was literal: `PhoneObserverScreen` passed `journalMark = {}` into the shell,
 * so the journal pill at the bottom of home was a 48dp empty box. Home could not
 * answer *which journal am I feeding* — the same question iOS's pill failed, one step
 * worse, because iOS at least drew something.
 *
 * This implements § 4.3 (the generic mark) via § 6's Compose mapping and § 5's tokens.
 * ⛔ **Only the generic form.** The owner's real mark is served as a render-spec by the
 * journal (§ 1) and no Android surface fetches or parses that spec today; painting a
 * placeholder mark over an identity we have not read would be exactly the failure
 * § 4.3's own guard names — an absence and a fetch failure are different states.
 * Drawing the identified mark is the next step and needs the spec on the wire first.
 *
 * ⛔ **One chip renderer, parameterised by side.** iOS shipped a second private chip
 * implementation for its compact pill, it drew bare glyph strokes with no tile, tint or
 * border, and the founder's read of that build was that it did not look like a valid
 * mark at all. Do not add a per-surface one here.
 */
object JournalMarkTokens {
    /** § 4.3 — chip 1, upright, dashed sol orange. */
    val chipOneBorder = SolstoneColors.markGenericChipOne

    /** § 4.3 — chip 2, rotated 45°, dashed mark-palette gold. */
    val chipTwoBorder = SolstoneColors.markGenericChipTwo

    /** § 4.3 — the generic chips' tint is 7%, against § 2.1's 12% for an identified one. */
    const val GENERIC_TINT_ALPHA = 0.07f

    /** § 5 — `mark.chip.radius` = 0.25 × side. */
    const val RADIUS_RATIO = 0.25f

    /** § 5 — `mark.chip.border` = 2px at the 48px baseline. */
    const val BORDER_RATIO = 2f / 48f

    /** § 5 — `mark.pair.gap` ≈ 0.23 × side. */
    const val GAP_RATIO = 0.23f

    /** § 4.3 — dash pattern `3.2 × S/27` on, `2.4 × S/27` off. */
    const val DASH_ON_RATIO = 3.2f / 27f
    const val DASH_OFF_RATIO = 2.4f / 27f

    /** § 2.2 — the words are `#1A1A1A`, the middot `#6E6453`, in both appearances. */
    val wordInk = SolstoneColors.surfaceDark
    val middotInk = SolstoneColors.markMiddotInk

    /** § 3 — the card the mark unit lives in. Fixed warm-bright in every appearance. */
    val cardFill = SolstoneColors.markCardFill
    val cardBorder = SolstoneColors.markCardBorder
    val cardRadius = 12.dp

    /** § 4.3 — the accessible name. ⛔ Never silence, and never the words alone. */
    const val GENERIC_ACCESSIBLE_NAME = "your journal, not set up yet"
}

/**
 * One chip: a tinted, bordered tile holding a glyph (§ 2.1). The generic chips are
 * visibly empty and dashed — the identity does not exist yet, so there is nothing
 * inside to draw.
 *
 * Rotation rotates the **whole chip** as one rigid unit (§ 2.1). Never counter-rotate.
 */
@Composable
private fun JournalMarkChip(
    side: Dp,
    border: Color,
    rotationDegrees: Float,
) {
    Canvas(
        Modifier
            .size(side)
            .rotate(rotationDegrees),
    ) {
        val s = size.minDimension
        val strokeWidth = s * JournalMarkTokens.BORDER_RATIO
        val radius = CornerRadius(s * JournalMarkTokens.RADIUS_RATIO)
        val inset = strokeWidth / 2f
        val boxSize = Size(s - strokeWidth, s - strokeWidth)
        val topLeft = Offset(inset, inset)
        drawRoundRect(
            color = border.copy(alpha = JournalMarkTokens.GENERIC_TINT_ALPHA),
            topLeft = topLeft,
            size = boxSize,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = border,
            topLeft = topLeft,
            size = boxSize,
            cornerRadius = radius,
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(
                        s * JournalMarkTokens.DASH_ON_RATIO,
                        s * JournalMarkTokens.DASH_OFF_RATIO,
                    ),
                ),
            ),
        )
    }
}

/**
 * The generic chip pair (§ 4.3): chip 1 upright, chip 2 at 45°.
 *
 * A rotated chip's true bounding box is wider than its side (`S · 0.7071` half-width
 * against `S / 2`), so the pair is laid out with the extra room reserved — otherwise
 * the diamond's corners clip and the pair sits off-centre.
 */
@Composable
fun JournalMarkChips(side: Dp, modifier: Modifier = Modifier) {
    val diamondBox = side * 1.4143f
    Row(
        modifier = modifier.clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        JournalMarkChip(
            side = side,
            border = JournalMarkTokens.chipOneBorder,
            rotationDegrees = 0f,
        )
        Spacer(Modifier.width(side * JournalMarkTokens.GAP_RATIO))
        androidx.compose.foundation.layout.Box(
            Modifier.size(diamondBox),
            contentAlignment = Alignment.Center,
        ) {
            JournalMarkChip(
                side = side,
                border = JournalMarkTokens.chipTwoBorder,
                rotationDegrees = 45f,
            )
        }
    }
}

/**
 * The mark's two words with their middot (§ 2.2).
 *
 * 🔒 Three spans, not one line: the words are the identity claim in full-strength ink,
 * the middot is its quiet join in muted warm ink. § 2.2 is explicit that these are two
 * roles and never one whole-line colour, and that they do **not** adapt in dark mode —
 * they sit on the card, which is fixed warm-bright in every appearance.
 *
 * ⚠ `journal` here is the mark's own word-pair, **not** the retired bare-noun title.
 */
@Composable
fun JournalMarkWords(
    first: String,
    second: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = first,
            fontFamily = ComfortaaBold,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            color = JournalMarkTokens.wordInk,
        )
        Text(
            text = " · ",
            fontFamily = ComfortaaBold,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            color = JournalMarkTokens.middotInk,
        )
        Text(
            text = second,
            fontFamily = ComfortaaBold,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            color = JournalMarkTokens.wordInk,
        )
    }
}

/**
 * The generic mark in its card (§ 3) — chips above, words below, both centred.
 *
 * ⛔ Never inline (chips beside words): that is the functional default, and it reads as
 * "icons next to a label" rather than as a mark.
 */
@Composable
fun JournalMarkCard(modifier: Modifier = Modifier) {
    Column(
        modifier
            .shellSurface(
                JournalMarkTokens.cardFill,
                JournalMarkTokens.cardBorder,
                RoundedCornerShape(JournalMarkTokens.cardRadius),
            )
            .padding(horizontal = 22.dp, vertical = 18.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = JournalMarkTokens.GENERIC_ACCESSIBLE_NAME
            }
            .testTag("journalMarkCard"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        JournalMarkChips(side = 48.dp)
        Spacer(Modifier.height(12.dp))
        JournalMarkWords(first = "your", second = "journal", fontSize = 18.sp)
    }
}
