// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable

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
fun solstoneSwitchColors(): SwitchColors {
    val scheme = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        // On: an accent track carrying a light thumb. Unchanged; this half always read correctly.
        checkedThumbColor = scheme.onPrimary,
        checkedTrackColor = scheme.primary,
        checkedBorderColor = scheme.primary,
        // Off: ink, not accent. ⛔ Do not reach for `outline`, `outlineVariant`, `onSurfaceVariant`
        // or `surfaceTint` here — in this palette all four ARE the accent, which is how the defect
        // arrived in the first place.
        uncheckedThumbColor = scheme.onSurface,
        uncheckedTrackColor = scheme.surfaceContainerHighest,
        uncheckedBorderColor = scheme.onSurface,
    )
}

/**
 * The scheme roles a switch's OFF state may never take, by name.
 *
 * ⚠ Exposed so a test can assert the rule against every scheme rather than against one colour
 * literal — a literal would pass the day the palette is retuned and the accent moves.
 */
val ACCENT_SCHEME_ROLES: List<String> =
    listOf("primary", "outline", "outlineVariant", "onSurfaceVariant", "surfaceTint", "tertiary")
