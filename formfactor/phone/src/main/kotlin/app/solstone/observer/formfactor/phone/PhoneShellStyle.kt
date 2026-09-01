// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The shell's usage tokens: the surfaces that sit on the ground, the hairline that
 * separates them from it, the measurements everything is laid out from, and the type
 * pairing that decides which face carries which words.
 *
 * The Android sibling of `solstone-swift`'s `Sources/Design/DeckStyle.swift`, and it
 * exists for the same reason. [SolstoneColorSchemes] already gave this app a warm
 * ground in both appearances — that half was right and is untouched here. What was
 * missing is everything *on* the ground: the deck drew its tiles as bare `Column`s
 * with padding and no container at all, so a "tile" was four lines of default-size
 * text floating on cream. A grid of things the owner owns has to read as objects.
 *
 * ⚠ Identity values stay CMO's, in [SolstoneColors], and reach the shell through the
 * [MaterialTheme] colour scheme. Nothing here introduces a colour literal that is not
 * already a brand value or a derived alpha of one; everything here is *usage*, which
 * is VPX's.
 */
object ShellMetrics {
    /** The gutter every surface uses against the screen edge. */
    val screenMargin = PHONE_CONTENT_MARGIN_DP.dp

    /** Between two tiles, and between two cards. */
    val gutter = 12.dp

    /** Inside a card or a pane section. */
    val surfacePadding = 16.dp

    /**
     * Inside a deck tile. Tighter than a card's — the deck's job is to show every
     * source at once, and the tile has to hold a glyph, a name, a state and a
     * sub-line inside a half-width column.
     */
    val tilePadding = 12.dp

    /**
     * A tile's corner — **one uniform radius for the whole grid**.
     *
     * ⚠ The iOS shell shipped hard-cornered tiles because it drew `ConcentricRectangle`
     * with no container shape, and concentric corners resolve to zero for any peer
     * inset further than the container radius. Compose has no concentric primitive and
     * so cannot reproduce that defect, but the *rule* it corrected applies here and is
     * why this is a single token rather than a per-call number: a grid of peers takes
     * one radius, applied uniformly, or the tiles stop reading as a set.
     */
    val tileRadius = 22.dp
    val tileShape: Shape = RoundedCornerShape(tileRadius)

    /**
     * A grouped card inside a pane. Tighter than a tile so a card inside a pane never
     * reads as a second deck.
     */
    val cardRadius = 18.dp
    val cardShape: Shape = RoundedCornerShape(cardRadius)

    /** The floor a source tile never falls below, so a short row still reads as a band. */
    val tileMinHeight = 122.dp

    /**
     * `import` and `add more` are shorter than a source tile — they carry a name and a
     * sub-line and no state, so matching a source tile's height would leave a hole in
     * the middle of each. The approved mock sizes them the same way.
     */
    val utilTileMinHeight = 100.dp

    /** A tappable row in a pane or the shelf. Above the 48dp touch floor by design. */
    val rowMinHeight = 56.dp

    /** Vertical rhythm between a section's heading and its content. */
    val sectionSpacing = 8.dp

    /** Between two sections. */
    val sectionGap = 24.dp

    /** The hairline weight. Thin enough to be an edge, not a frame. */
    val hairline = 1.dp

    /** Inset for a pane's scrolling content, so nothing sits against the app bar. */
    val paneContentPadding = PaddingValues(
        start = PHONE_CONTENT_MARGIN_DP.dp,
        end = PHONE_CONTENT_MARGIN_DP.dp,
        top = 8.dp,
        bottom = 96.dp,
    )
}

/**
 * The hairline that separates a surface from the ground.
 *
 * Derived from the scheme's own ink rather than added as a new literal, so it stays
 * warm in both appearances: on cream it is a low-alpha warm dark, on the dark ground a
 * low-alpha warm light. A grey system separator is exactly what a warm ground must not
 * carry — it is the tell that a surface reached for the platform default.
 */
val shellHairline: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

/**
 * A tile, a card, a grouped row: the surface that sits *on* the ground.
 *
 * `surfaceContainerHigh` is cream-bright on light and the dark lift on dark — one step
 * off the ground in both, which is the whole requirement.
 */
val shellSurface: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainerHigh

/** Secondary ink: sub-lines, values, anything read after the name. */
val shellSecondaryInk: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)

/** Puts a surface on the ground with its hairline, in one call so the two never drift. */
fun Modifier.shellSurface(color: Color, hairline: Color, shape: Shape): Modifier =
    this
        .background(color = color, shape = shape)
        .border(width = ShellMetrics.hairline, color = hairline, shape = shape)

/**
 * The destination-tile treatment: no fill, a dashed edge.
 *
 * `import` and `add more` are doors, not things the owner owns, and the approved mock
 * draws them as an outline rather than a filled card. It is also what keeps § 2.4
 * honest at a glance — a dashed tile visibly has no state to report, so the absence of
 * a state word and a switch reads as intent rather than as an omission.
 */
fun Modifier.shellDashedSurface(hairline: Color, cornerRadius: Dp): Modifier =
    this.drawBehind {
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
            color = hairline,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style = Stroke(
                width = stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f.dp.toPx(), 4f.dp.toPx())),
            ),
        )
    }

/**
 * The brand display face.
 *
 * ⚠ **Comfortaa was not in this repository at all before 2026-09-01** — no font
 * resource, no `FontFamily`, no reference anywhere in any module. Every word in the
 * app was the platform default face at the platform default size, which is the single
 * largest reason the shell read as a stock settings app rather than as solstone. The
 * iOS analogue of this defect was subtler and worse: the face *shipped inside the app*
 * and was never registered, so it silently fell back for the life of the app. Here
 * there was nothing to fall back from.
 *
 * ✅ The Android check is the built artifact, same as iOS's:
 * `unzip -l phone-real-debug.apk | grep comfortaa` — a font resource that is not in
 * the APK is not rendering, whatever the source says.
 */
val ComfortaaBold = FontFamily(Font(R.font.comfortaa_bold, FontWeight.Bold))

/**
 * Comfortaa names things; the platform face is read.
 *
 * The brand face carries the greeting, tile and row names, pane and section titles.
 * The platform face carries state words, sub-lines, values and any long copy, where
 * its legibility and its font-scale behaviour are worth more than the character.
 * ⛔ Do not set body copy in Comfortaa.
 *
 * Only the display/headline/title roles are overridden — body, label and the rest keep
 * the Material defaults, which is what makes this a *pairing* rather than a re-skin.
 */
fun solstoneTypography(): Typography {
    val base = Typography()
    fun brand(style: androidx.compose.ui.text.TextStyle) =
        style.copy(fontFamily = ComfortaaBold, fontWeight = FontWeight.Bold)
    return base.copy(
        displayLarge = brand(base.displayLarge),
        displayMedium = brand(base.displayMedium),
        displaySmall = brand(base.displaySmall),
        headlineLarge = brand(base.headlineLarge),
        headlineMedium = brand(base.headlineMedium),
        // The greeting and every pane heading. Sized down from Material's 24sp so a
        // two-word greeting and the status pill share the app bar without collision.
        headlineSmall = brand(base.headlineSmall).copy(fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = brand(base.titleLarge),
        // A tile's or a row's name.
        titleMedium = brand(base.titleMedium).copy(fontSize = 17.sp, lineHeight = 22.sp),
        titleSmall = brand(base.titleSmall),
    )
}
