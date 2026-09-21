// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * One switch palette for the whole shell, and the off state is the reason it exists.
 *
 * 🔴 **A source that was OFF drew its switch thumb in the brand accent, so it read as ON at a
 * glance.** Nothing in the switch code was wrong: Material3 defaults `uncheckedThumbColor` and
 * `uncheckedBorderColor` to `colorScheme.outline`, and this theme sets `outline` to
 * `solOrangeAccessible` — the accent. So the off thumb was a filled ochre pill, the same colour
 * language as the on track, and on the source detail screen an off switch and an on switch sat 500dp
 * apart with the OFF one carrying the more saturated fill.
 *
 * ⛔ **This is the one thing this app may never do: be confidently wrong about whether something is
 * taking things in.** A state word that says `off` under a control that looks on is worse than
 * either alone, because the glance wins and the glance was the lie.
 *
 * ⚠ **Fixed here rather than in the scheme, deliberately.** `outline` is load-bearing for card
 * borders and dividers across every surface; retuning it to fix a switch would move a dozen
 * unrelated edges and the accent border is *correct* in most of those places. The defect is
 * specifically that a two-state control borrowed the accent for its negative state.
 *
 * ✅ Every role here comes from the scheme, so all four variants (light, light-medium,
 * light-high, dark) and any future one are covered without a second table.
 */
@Composable
fun solstoneSwitchColors(): SwitchColors = solstoneSwitchColorValues(MaterialTheme.colorScheme).toSwitchColors()

/**
 * The six values, as data, so a test can measure **what the shell actually draws**.
 *
 * ⚠ `SolstoneSwitchColorsTest` used to assert on `scheme.onSurface` directly, with a comment
 * describing it as "what `solstoneSwitchColors()` uses" — a hand-maintained mirror, not a read.
 * Any change made here shipped with that test green and never measured. It reads this now.
 */
data class SolstoneSwitchValues(
    val checkedThumb: Color,
    val checkedTrack: Color,
    val checkedBorder: Color,
    val uncheckedThumb: Color,
    val uncheckedTrack: Color,
    val uncheckedBorder: Color,
)

/**
 * 🔴 **The off border carries alpha; the off thumb does not, and the asymmetry is the whole point.**
 *
 * The 2026-09-10 ruling fixed the off state's **hue** — an off switch may carry no accent fill —
 * by moving thumb and border from Material's `outline` (the accent in this palette) to `onSurface`.
 * It never addressed **weight**, and `onSurface` is `#1A1A1A`: the ruling swapped an accent for the
 * darkest ink available, when Material's own off state is a mid-tone. On a real phone the result
 * was the heaviest control on the screen, a solid black thumb inside a 2dp black ring, beside cream
 * tiles and hairlines.
 *
 * ⛔ **Alpha cannot be applied uniformly.** Composited over `surfaceContainerHighest`, `onSurface`
 * lightens *through* `primary`'s luminance: contrast from `primary` falls 3.45 → 2.19 (α 0.85) →
 * 1.09 (α 0.65) before rising again. The off-reads-as-on bar (≥ 2.0) is therefore cleared only
 * above roughly α 0.82, where the colour is not visibly lighter. So a uniform alpha buys nothing.
 *
 * ✅ **The thumb keeps full `onSurface`** — it is the element whose fill the glance reads, so the
 * hue ruling stands untouched by construction. ✅ **The border takes alpha**, because the border is
 * the ink doing the visual damage and it *cannot* make off read as on: the ON state draws
 * `checkedBorderColor = primary` on a `primary` track, so the border is invisible whenever the
 * switch is on. A role it never plays when on cannot be the role that confuses on with off. What
 * the border does owe is visibility against its own track, and it is measured for exactly that.
 */
const val UNCHECKED_BORDER_ALPHA = 0.55f

fun solstoneSwitchColorValues(scheme: ColorScheme): SolstoneSwitchValues = SolstoneSwitchValues(
    // On: an accent track carrying a light thumb. Unchanged; this half always read correctly.
    checkedThumb = scheme.onPrimary,
    checkedTrack = scheme.primary,
    checkedBorder = scheme.primary,
    // Off: ink, not accent. ⛔ Do not reach for `outline`, `outlineVariant`, `onSurfaceVariant`
    // or `surfaceTint` here — in this palette all four ARE the accent, which is how the defect
    // arrived in the first place. Alpha on `onSurface` is not a role swap: it is still ink.
    uncheckedThumb = scheme.onSurface,
    uncheckedTrack = scheme.surfaceContainerHighest,
    uncheckedBorder = scheme.onSurface.copy(alpha = UNCHECKED_BORDER_ALPHA),
)

/** What the off border resolves to once drawn on its own track — the colour an eye actually sees. */
fun SolstoneSwitchValues.compositedUncheckedBorder(): Color =
    uncheckedBorder.compositeOver(uncheckedTrack)

@Composable
private fun SolstoneSwitchValues.toSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = checkedThumb,
    checkedTrackColor = checkedTrack,
    checkedBorderColor = checkedBorder,
    uncheckedThumbColor = uncheckedThumb,
    uncheckedTrackColor = uncheckedTrack,
    uncheckedBorderColor = uncheckedBorder,
)

/**
 * The scheme roles a switch's OFF state may never take, by name.
 *
 * ⚠ Exposed so a test can assert the rule against every scheme rather than against one colour
 * literal — a literal would pass the day the palette is retuned and the accent moves.
 */
val ACCENT_SCHEME_ROLES: List<String> =
    listOf("primary", "outline", "outlineVariant", "onSurfaceVariant", "surfaceTint", "tertiary")
