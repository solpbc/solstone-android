// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

enum class ContrastClass {
    STANDARD,
    MEDIUM,
    HIGH,
}

fun classifyContrast(sdkInt: Int, contrast: Float): ContrastClass {
    if (sdkInt < 34) return ContrastClass.STANDARD
    return when {
        contrast < 0.5f -> ContrastClass.STANDARD
        contrast < 1.0f -> ContrastClass.MEDIUM
        else -> ContrastClass.HIGH
    }
}
