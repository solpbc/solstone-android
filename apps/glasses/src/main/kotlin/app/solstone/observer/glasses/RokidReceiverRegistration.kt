// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

enum class RokidReceiverRegistrationMode {
    NotExportedFlag,
    LegacyExportedTwoArg,
}

fun rokidReceiverRegistrationMode(sdkInt: Int): RokidReceiverRegistrationMode =
    if (sdkInt >= 33) {
        RokidReceiverRegistrationMode.NotExportedFlag
    } else {
        RokidReceiverRegistrationMode.LegacyExportedTwoArg
    }
