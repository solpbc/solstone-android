// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

/** How far this activity has got with asking the owner for the camera, for the pairing scanner. */
enum class CameraAsk { NotAsked, Awaiting, Answered }

/** What opening the pairing scanner does next. */
enum class ScanEntry { Scan, AskForCamera, AwaitAnswer, CameraOff }

/**
 * Where an entry into the pairing scanner goes, given whether the camera is granted and how far the
 * ask has got.
 *
 * 🔴 **The scanner is not a source, so nothing else asks for its permission.** Permissions are
 * requested one at a time from the source being set up, and an owner whose first tap is `connect a
 * journal` has set up none. Opening the camera anyway fails inside the platform and used to put the
 * platform's own exception text on screen.
 *
 * ⚠ **One ask per entry.** The routing extra is re-read from the same intent after a rotation, so
 * the state has to survive one: `Awaiting` must not raise a second prompt over the first, and
 * `Answered` without the grant must land on the screen that says what to do, not ask again. A new
 * tap from the shell is a new entry and asks again; when the platform has stopped showing the
 * prompt it answers `denied` at once, which lands on the same screen rather than nowhere.
 *
 * Takes primitives rather than a `Context` so the decision is testable off-device.
 */
fun scanEntry(hasCamera: Boolean, ask: CameraAsk): ScanEntry = when {
    hasCamera -> ScanEntry.Scan
    ask == CameraAsk.NotAsked -> ScanEntry.AskForCamera
    ask == CameraAsk.Awaiting -> ScanEntry.AwaitAnswer
    else -> ScanEntry.CameraOff
}
