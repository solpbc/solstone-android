// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalStatusOnGreen = compositionLocalOf { SolstoneColors.statusOnGreenLightStandard }

fun statusOnGreen(variant: ThemeVariant): Color = when (variant) {
    ThemeVariant.LIGHT_STANDARD -> SolstoneColors.statusOnGreenLightStandard
    ThemeVariant.LIGHT_MEDIUM -> SolstoneColors.statusOnGreenLightMedium
    ThemeVariant.LIGHT_HIGH -> SolstoneColors.statusOnGreenLightHigh
    ThemeVariant.DARK_STANDARD -> SolstoneColors.statusOnGreenDarkStandard
    ThemeVariant.DARK_MEDIUM -> SolstoneColors.statusOnGreenDarkMedium
    ThemeVariant.DARK_HIGH -> SolstoneColors.statusOnGreenDarkHigh
}
