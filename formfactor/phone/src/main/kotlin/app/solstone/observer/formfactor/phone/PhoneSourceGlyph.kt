// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.annotation.DrawableRes
import app.solstone.core.model.ReasonCode
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
 * `ON` gives "the source's own active sub-line" ([sourceActiveLine]), and only camera has one.
 * iOS's audio active subtext is the word `on`, which restates the state word above it (the
 * "off / off" defect the iOS pass fixed), and `location` has no approved active line on any
 * platform. ⛔ Do not author one for either here: that is platform-local copy for a slot the
 * contract owns.
 */
fun sourceSubLine(status: SourceStatus, paired: Boolean): String? = when (status.state) {
    SourceState.OFF ->
        if (paired) "intake is off. turn it on any time." else "turn it on any time."
    SourceState.PAUSED ->
        // The owner's pause and the system's are different facts; only the first is "you".
        if (status.reason == ReasonCode.MICROPHONE_SILENCED) sourceDetailRule(status.reason).diagnosis
        else "you paused this. resume to start intake again."
    SourceState.SETTING_UP ->
        if (paired) "getting ready. connecting to your journal." else "getting ready…"
    SourceState.NEEDS_ATTENTION ->
        sourceDetailRule(status.reason).diagnosis
            // § 5.1's locked honest-unknown line. A source that reaches
            // `needs attention` with no diagnosis still owes the owner a sentence.
            ?: "the reason it couldn't reach your journal isn't clear."
    SourceState.ON -> sourceActiveLine(status.sourceId)
    // § 5.1 gives this state the source's own setup line, and no Android source supplies one,
    // so none renders. ⛔ Do not author one here.
    SourceState.READY_TO_SET_UP -> null
}

/**
 * What a source does while it is on, in the owner's words.
 *
 * Camera only. Its capture is unattended and leaves no trace on screen, so the owner is told what
 * it takes, from which camera and how often. The sentence opens with "while this is on" so it
 * stays true in every state, which is why the source page also shows it under its switch, before
 * the owner turns the camera on.
 *
 * 🔴 `every minute` is `StillCaptureEngine.STILL_EVERY_MS` (`platform/camera-still`) said in
 * words, and it was measured on hardware (backgrounded, screen off) before it was
 * written. A change to that constant is a change to this sentence: neither moves alone.
 */
fun sourceActiveLine(sourceId: String): String? = when (sourceId) {
    "camera" ->
        "while this is on, the solstone app takes a photo from your rear camera every minute " +
            "and adds it to your journal."
    else -> null
}
