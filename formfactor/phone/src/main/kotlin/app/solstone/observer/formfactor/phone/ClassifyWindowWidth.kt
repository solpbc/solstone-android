// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.window.core.layout.WindowSizeClass

enum class WidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    LARGE,
    EXTRA_LARGE,
}

fun classifyWindowWidth(widthDp: Int): WidthClass {
    return when {
        widthDp >= WindowSizeClass.WIDTH_DP_EXTRA_LARGE_LOWER_BOUND -> WidthClass.EXTRA_LARGE
        widthDp >= WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND -> WidthClass.LARGE
        widthDp >= WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND -> WidthClass.EXPANDED
        widthDp >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
}
