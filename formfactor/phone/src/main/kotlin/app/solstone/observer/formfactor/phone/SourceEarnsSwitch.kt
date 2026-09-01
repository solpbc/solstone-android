// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

/**
 * Which sources carry a switch.
 *
 * § 2.4: a control that cannot perform what it names does not exist. The device can
 * start and stop what runs *on the device*, so those tiles carry switches; it cannot
 * start a session on a wrist or a pendant, so those carry none, anywhere.
 *
 * ⚠ **`camera` was excluded, and that was a transcription gap rather than a ruling.**
 * Both the cross-platform contract's § 2.4 and the Android bible's § 4.2 state the rule
 * as an enumeration — *"audio, screen and location carry switches"* — and that list is
 * **iOS's source set**. Android has no `screen` source and iOS has no `camera` one, so
 * reading a list written for the other platform silently dropped the one source the
 * sentence never had a chance to name.
 *
 * ✅ Camera is registered in `CaptureFactory` with the same `SourceRegistration`, the
 * same `SourceWish` store and the same engine contract as audio and location, so the
 * switch performs exactly what it names. Without it an owner had **no control anywhere
 * in the app** over a source that takes a photo on a timer — not on the tile, not in
 * its detail view. That is the wrong side of § 2.4 to err on.
 */
fun sourceEarnsSwitch(sourceId: String): Boolean =
    sourceId == "audio" || sourceId == "location" || sourceId == "camera"
