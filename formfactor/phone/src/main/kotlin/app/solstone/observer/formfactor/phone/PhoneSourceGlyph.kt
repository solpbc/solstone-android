// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.annotation.DrawableRes
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SourceStatus

/**
 * The glyph that stands for a source, everywhere it appears.
 *
 * The shell had **no source iconography at all**: a tile was a state dot, a word and a
 * switch, so five different sources were distinguished only by their names and the
 * deck read as a settings list rather than as a set of things the owner owns. The
 * state dot beside each name is a pure function of *state*, so it says nothing about
 * *which source* — which is the same defect iOS shipped from the other direction,
 * where every row drew the state's symbol and five sources showed one power icon.
 *
 * One glyph per source, resolved here so the deck tile, the add-more row and the
 * source detail cannot drift apart. ⛔ A new source adds its glyph here, not at a call
 * site.
 */
@DrawableRes
fun sourceGlyph(sourceId: String): Int = when (sourceId) {
    "audio" -> R.drawable.phone_source_audio
    "location" -> R.drawable.phone_source_location
    "camera" -> R.drawable.phone_source_camera
    else -> R.drawable.phone_add_more
}

/**
 * The sub-line under a tile's state word — [`mobile-shell.md`] § 5.1's table, verbatim.
 *
 * These strings are **locked cross-platform copy**, not this platform's wording: § 5.1
 * says outright that authoring a platform-local word for any of these is the exact
 * defect the cross-platform contract exists to prevent. They existed in the contract
 * and were simply never drawn, which left every Android tile two lines tall and the
 * grid's rows wildly unequal — the same shape as the iOS defect, where the vocabulary
 * was in the code and the tile never rendered it.
 *
 * ⛔ **`ON` deliberately has no sub-line here, and that is not an omission.** § 5.1
 * gives `ON` "the source's own active sub-line", and of the three sources this app
 * ships: iOS's audio active subtext is the word `on`, which restates the state word
 * above it (the "off / off" defect the iOS pass fixed); `camera`'s sub-line is
 * explicitly **blocked** by § 5.2 pending a real cadence measurement on hardware; and
 * `location` has no approved active line on any platform. Inventing three would be
 * authoring platform-local copy for a slot the contract owns.
 */
fun sourceSubLine(status: SourceStatus, paired: Boolean): String? = when (status.state) {
    SourceState.OFF ->
        if (paired) "not sending to your journal. turn it on any time." else "turn it on any time."
    SourceState.PAUSED -> "you paused this. resume to start sending again."
    SourceState.SETTING_UP ->
        if (paired) "getting ready — connecting to your journal." else "getting ready…"
    SourceState.NEEDS_ATTENTION ->
        sourceDetailRule(status.reason).diagnosis
            // § 5.1's locked honest-unknown line. A source that reaches
            // `needs attention` with no diagnosis still owes the owner a sentence.
            ?: "the reason it couldn't reach your journal isn't clear."
    SourceState.ON -> null
}
