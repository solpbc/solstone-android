// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val ColorSchemeRoles: List<String> = listOf(
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground", "surface", "onSurface", "surfaceVariant",
    "onSurfaceVariant", "surfaceTint",
    "inverseSurface", "inverseOnSurface",
    "error", "onError", "errorContainer", "onErrorContainer",
    "outline", "outlineVariant", "scrim",
    "surfaceBright", "surfaceDim", "surfaceContainer", "surfaceContainerHigh",
    "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest",
    "primaryFixed", "onPrimaryFixed", "primaryFixedDim", "onPrimaryFixedVariant",
    "secondaryFixed", "onSecondaryFixed", "secondaryFixedDim", "onSecondaryFixedVariant",
    "tertiaryFixed", "onTertiaryFixed", "tertiaryFixedDim", "onTertiaryFixedVariant",
)

val TextBearingRoles: Set<String> = setOf(
    "primary", "onPrimary", "onPrimaryContainer",
    "onPrimaryFixed", "onPrimaryFixedVariant",
    "secondary", "onSecondary", "onSecondaryContainer",
    "onSecondaryFixed", "onSecondaryFixedVariant",
    "tertiary", "onTertiary", "onTertiaryContainer",
    "onTertiaryFixed", "onTertiaryFixedVariant",
    "onBackground", "onSurface", "onSurfaceVariant",
    "inverseOnSurface", "inversePrimary",
    "error", "onError", "onErrorContainer",
)

fun ColorScheme.colorForRole(name: String): Color = when (name) {
    "primary" -> primary
    "onPrimary" -> onPrimary
    "primaryContainer" -> primaryContainer
    "onPrimaryContainer" -> onPrimaryContainer
    "inversePrimary" -> inversePrimary
    "secondary" -> secondary
    "onSecondary" -> onSecondary
    "secondaryContainer" -> secondaryContainer
    "onSecondaryContainer" -> onSecondaryContainer
    "tertiary" -> tertiary
    "onTertiary" -> onTertiary
    "tertiaryContainer" -> tertiaryContainer
    "onTertiaryContainer" -> onTertiaryContainer
    "background" -> background
    "onBackground" -> onBackground
    "surface" -> surface
    "onSurface" -> onSurface
    "surfaceVariant" -> surfaceVariant
    "onSurfaceVariant" -> onSurfaceVariant
    "surfaceTint" -> surfaceTint
    "inverseSurface" -> inverseSurface
    "inverseOnSurface" -> inverseOnSurface
    "error" -> error
    "onError" -> onError
    "errorContainer" -> errorContainer
    "onErrorContainer" -> onErrorContainer
    "outline" -> outline
    "outlineVariant" -> outlineVariant
    "scrim" -> scrim
    "surfaceBright" -> surfaceBright
    "surfaceDim" -> surfaceDim
    "surfaceContainer" -> surfaceContainer
    "surfaceContainerHigh" -> surfaceContainerHigh
    "surfaceContainerHighest" -> surfaceContainerHighest
    "surfaceContainerLow" -> surfaceContainerLow
    "surfaceContainerLowest" -> surfaceContainerLowest
    "primaryFixed" -> primaryFixed
    "onPrimaryFixed" -> onPrimaryFixed
    "primaryFixedDim" -> primaryFixedDim
    "onPrimaryFixedVariant" -> onPrimaryFixedVariant
    "secondaryFixed" -> secondaryFixed
    "onSecondaryFixed" -> onSecondaryFixed
    "secondaryFixedDim" -> secondaryFixedDim
    "onSecondaryFixedVariant" -> onSecondaryFixedVariant
    "tertiaryFixed" -> tertiaryFixed
    "onTertiaryFixed" -> onTertiaryFixed
    "tertiaryFixedDim" -> tertiaryFixedDim
    "onTertiaryFixedVariant" -> onTertiaryFixedVariant
    else -> error("unknown ColorScheme role $name")
}

object SolstoneColorSchemes {
    val lightStandard: ColorScheme = lightColorScheme(
        primary = SolstoneColors.textOrangeAa,
        onPrimary = SolstoneColors.inkOnDark,
        primaryContainer = SolstoneColors.solOrange,
        onPrimaryContainer = SolstoneColors.surfaceDark,
        inversePrimary = SolstoneColors.inkOnDark,
        secondary = SolstoneColors.surfaceDark,
        onSecondary = SolstoneColors.inkOnDark,
        secondaryContainer = SolstoneColors.surfaceCreamBright,
        onSecondaryContainer = SolstoneColors.surfaceDark,
        tertiary = SolstoneColors.textOrangeAa,
        onTertiary = SolstoneColors.inkOnDark,
        tertiaryContainer = SolstoneColors.solGold,
        onTertiaryContainer = SolstoneColors.surfaceDark,
        background = SolstoneColors.surfaceCream,
        onBackground = SolstoneColors.surfaceDark,
        surface = SolstoneColors.surfaceCream,
        onSurface = SolstoneColors.surfaceDark,
        surfaceVariant = SolstoneColors.surfaceCreamBright,
        onSurfaceVariant = SolstoneColors.textOrangeAa,
        surfaceTint = SolstoneColors.textOrangeAa,
        inverseSurface = SolstoneColors.surfaceDark,
        inverseOnSurface = SolstoneColors.inkOnDark,
        error = SolstoneColors.errorRed,
        onError = SolstoneColors.inkOnDark,
        errorContainer = SolstoneColors.errorContainerLight,
        onErrorContainer = SolstoneColors.errorRed,
        outline = SolstoneColors.solOrangeAccessible,
        outlineVariant = SolstoneColors.solOrange,
        scrim = SolstoneColors.surfaceBlack,
        surfaceBright = SolstoneColors.surfaceCreamBright,
        surfaceDim = SolstoneColors.surfaceCream,
        surfaceContainer = SolstoneColors.surfaceCream,
        surfaceContainerHigh = SolstoneColors.surfaceCreamBright,
        surfaceContainerHighest = SolstoneColors.surfaceCreamBright,
        surfaceContainerLow = SolstoneColors.surfaceCream,
        surfaceContainerLowest = SolstoneColors.surfaceCream,
    ).withBrandFixed()

    val lightMedium: ColorScheme = lightColorScheme(
        primary = SolstoneColors.textOrangeAa,
        onPrimary = SolstoneColors.inkOnDark,
        primaryContainer = SolstoneColors.solOrange,
        onPrimaryContainer = SolstoneColors.surfaceDark,
        inversePrimary = SolstoneColors.inkOnDark,
        secondary = SolstoneColors.surfaceDark,
        onSecondary = SolstoneColors.inkOnDark,
        secondaryContainer = SolstoneColors.surfaceWhite,
        onSecondaryContainer = SolstoneColors.surfaceDark,
        tertiary = SolstoneColors.textOrangeAa,
        onTertiary = SolstoneColors.inkOnDark,
        tertiaryContainer = SolstoneColors.solGold,
        onTertiaryContainer = SolstoneColors.surfaceDark,
        background = SolstoneColors.surfaceCreamBright,
        onBackground = SolstoneColors.surfaceDark,
        surface = SolstoneColors.surfaceCreamBright,
        onSurface = SolstoneColors.surfaceDark,
        surfaceVariant = SolstoneColors.surfaceWhite,
        onSurfaceVariant = SolstoneColors.textOrangeAa,
        surfaceTint = SolstoneColors.textOrangeAa,
        inverseSurface = SolstoneColors.surfaceDark,
        inverseOnSurface = SolstoneColors.inkOnDark,
        error = SolstoneColors.errorRed,
        onError = SolstoneColors.inkOnDark,
        errorContainer = SolstoneColors.errorContainerLight,
        onErrorContainer = SolstoneColors.errorRed,
        outline = SolstoneColors.textOrangeAa,
        outlineVariant = SolstoneColors.solOrangeAccessible,
        scrim = SolstoneColors.surfaceBlack,
        surfaceBright = SolstoneColors.surfaceWhite,
        surfaceDim = SolstoneColors.surfaceCream,
        surfaceContainer = SolstoneColors.surfaceCreamBright,
        surfaceContainerHigh = SolstoneColors.surfaceWhite,
        surfaceContainerHighest = SolstoneColors.surfaceWhite,
        surfaceContainerLow = SolstoneColors.surfaceCream,
        surfaceContainerLowest = SolstoneColors.surfaceCream,
    ).withBrandFixed()

    val lightHigh: ColorScheme = lightColorScheme(
        primary = SolstoneColors.surfaceDark,
        onPrimary = SolstoneColors.inkOnDark,
        primaryContainer = SolstoneColors.solOrange,
        onPrimaryContainer = SolstoneColors.surfaceDark,
        inversePrimary = SolstoneColors.inkOnDark,
        secondary = SolstoneColors.surfaceDark,
        onSecondary = SolstoneColors.inkOnDark,
        secondaryContainer = SolstoneColors.surfaceCream,
        onSecondaryContainer = SolstoneColors.surfaceDark,
        tertiary = SolstoneColors.surfaceDark,
        onTertiary = SolstoneColors.inkOnDark,
        tertiaryContainer = SolstoneColors.solGold,
        onTertiaryContainer = SolstoneColors.surfaceDark,
        background = SolstoneColors.surfaceWhite,
        onBackground = SolstoneColors.surfaceDark,
        surface = SolstoneColors.surfaceWhite,
        onSurface = SolstoneColors.surfaceDark,
        surfaceVariant = SolstoneColors.surfaceCream,
        onSurfaceVariant = SolstoneColors.surfaceDark,
        surfaceTint = SolstoneColors.surfaceDark,
        inverseSurface = SolstoneColors.surfaceDark,
        inverseOnSurface = SolstoneColors.inkOnDark,
        error = SolstoneColors.errorRed,
        onError = SolstoneColors.inkOnDark,
        errorContainer = SolstoneColors.errorContainerLight,
        onErrorContainer = SolstoneColors.errorRed,
        outline = SolstoneColors.surfaceDark,
        outlineVariant = SolstoneColors.textOrangeAa,
        scrim = SolstoneColors.surfaceBlack,
        surfaceBright = SolstoneColors.surfaceWhite,
        surfaceDim = SolstoneColors.surfaceCreamBright,
        surfaceContainer = SolstoneColors.surfaceWhite,
        surfaceContainerHigh = SolstoneColors.surfaceCreamBright,
        surfaceContainerHighest = SolstoneColors.surfaceCream,
        surfaceContainerLow = SolstoneColors.surfaceWhite,
        surfaceContainerLowest = SolstoneColors.surfaceCreamBright,
    ).withBrandFixed()

        // The dark schemes ground on the WARM near-black usage values, not on the neutral
    // grey brand seed. See SolstoneColors.darkGround for why, and note which roles
    // deliberately keep `surfaceDark`: every `on*` role, where it is INK on a light or
    // brand fill rather than a ground.
val darkStandard: ColorScheme = darkColorScheme(
        primary = SolstoneColors.inkOnDark,
        onPrimary = SolstoneColors.surfaceDark,
        primaryContainer = SolstoneColors.solOrange,
        onPrimaryContainer = SolstoneColors.surfaceDark,
        inversePrimary = SolstoneColors.textOrangeAa,
        secondary = SolstoneColors.inkOnDark,
        onSecondary = SolstoneColors.surfaceDark,
        secondaryContainer = SolstoneColors.darkSurface,
        onSecondaryContainer = SolstoneColors.inkOnDark,
        tertiary = SolstoneColors.inkOnDark,
        onTertiary = SolstoneColors.surfaceDark,
        tertiaryContainer = SolstoneColors.solGold,
        onTertiaryContainer = SolstoneColors.surfaceDark,
        background = SolstoneColors.darkGround,
        onBackground = SolstoneColors.inkOnDark,
        surface = SolstoneColors.darkGround,
        onSurface = SolstoneColors.inkOnDark,
        surfaceVariant = SolstoneColors.darkSurface,
        onSurfaceVariant = SolstoneColors.inkOnDark,
        surfaceTint = SolstoneColors.inkOnDark,
        inverseSurface = SolstoneColors.surfaceCream,
        inverseOnSurface = SolstoneColors.surfaceDark,
        error = SolstoneColors.errorPink,
        onError = SolstoneColors.errorContainerDark,
        errorContainer = SolstoneColors.errorContainerDark,
        onErrorContainer = SolstoneColors.errorPink,
        outline = SolstoneColors.solOrangeAccessible,
        outlineVariant = SolstoneColors.darkSurface,
        scrim = SolstoneColors.surfaceBlack,
        surfaceBright = SolstoneColors.darkSurfaceRaised,
        surfaceDim = SolstoneColors.surfaceBlack,
        surfaceContainer = SolstoneColors.darkGround,
        surfaceContainerHigh = SolstoneColors.darkSurface,
        surfaceContainerHighest = SolstoneColors.darkSurface,
        surfaceContainerLow = SolstoneColors.darkGround,
        surfaceContainerLowest = SolstoneColors.surfaceBlack,
    ).withBrandFixed()

    val darkMedium: ColorScheme = darkColorScheme(
        primary = SolstoneColors.inkOnDark,
        onPrimary = SolstoneColors.surfaceDark,
        primaryContainer = SolstoneColors.solOrange,
        onPrimaryContainer = SolstoneColors.surfaceDark,
        inversePrimary = SolstoneColors.textOrangeAa,
        secondary = SolstoneColors.inkOnDark,
        onSecondary = SolstoneColors.surfaceDark,
        secondaryContainer = SolstoneColors.darkSurface,
        onSecondaryContainer = SolstoneColors.inkOnDark,
        tertiary = SolstoneColors.inkOnDark,
        onTertiary = SolstoneColors.surfaceDark,
        tertiaryContainer = SolstoneColors.solGold,
        onTertiaryContainer = SolstoneColors.surfaceDark,
        background = SolstoneColors.darkGround,
        onBackground = SolstoneColors.inkOnDark,
        surface = SolstoneColors.darkGround,
        onSurface = SolstoneColors.inkOnDark,
        surfaceVariant = SolstoneColors.darkSurface,
        onSurfaceVariant = SolstoneColors.inkOnDark,
        surfaceTint = SolstoneColors.inkOnDark,
        inverseSurface = SolstoneColors.surfaceCreamBright,
        inverseOnSurface = SolstoneColors.surfaceDark,
        error = SolstoneColors.errorPink,
        onError = SolstoneColors.errorContainerDark,
        errorContainer = SolstoneColors.errorContainerDark,
        onErrorContainer = SolstoneColors.errorPink,
        outline = SolstoneColors.solOrange,
        outlineVariant = SolstoneColors.solOrangeAccessible,
        scrim = SolstoneColors.surfaceBlack,
        surfaceBright = SolstoneColors.darkSurfaceRaised,
        surfaceDim = SolstoneColors.surfaceBlack,
        surfaceContainer = SolstoneColors.darkGround,
        surfaceContainerHigh = SolstoneColors.darkSurface,
        surfaceContainerHighest = SolstoneColors.darkSurface,
        surfaceContainerLow = SolstoneColors.surfaceBlack,
        surfaceContainerLowest = SolstoneColors.surfaceBlack,
    ).withBrandFixed()

    val darkHigh: ColorScheme = darkColorScheme(
        primary = SolstoneColors.inkOnDark,
        onPrimary = SolstoneColors.surfaceDark,
        primaryContainer = SolstoneColors.solOrange,
        onPrimaryContainer = SolstoneColors.surfaceDark,
        inversePrimary = SolstoneColors.surfaceDark,
        secondary = SolstoneColors.inkOnDark,
        onSecondary = SolstoneColors.surfaceDark,
        secondaryContainer = SolstoneColors.surfaceCream,
        onSecondaryContainer = SolstoneColors.surfaceDark,
        tertiary = SolstoneColors.inkOnDark,
        onTertiary = SolstoneColors.surfaceDark,
        tertiaryContainer = SolstoneColors.solGold,
        onTertiaryContainer = SolstoneColors.surfaceDark,
        background = SolstoneColors.darkGround,
        onBackground = SolstoneColors.inkOnDark,
        surface = SolstoneColors.darkGround,
        onSurface = SolstoneColors.inkOnDark,
        surfaceVariant = SolstoneColors.darkSurface,
        onSurfaceVariant = SolstoneColors.inkOnDark,
        surfaceTint = SolstoneColors.inkOnDark,
        inverseSurface = SolstoneColors.surfaceWhite,
        inverseOnSurface = SolstoneColors.surfaceDark,
        error = SolstoneColors.errorPink,
        onError = SolstoneColors.errorContainerDark,
        errorContainer = SolstoneColors.errorContainerDark,
        onErrorContainer = SolstoneColors.errorPink,
        outline = SolstoneColors.inkOnDark,
        outlineVariant = SolstoneColors.surfaceCream,
        scrim = SolstoneColors.surfaceBlack,
        surfaceBright = SolstoneColors.darkSurfaceRaised,
        surfaceDim = SolstoneColors.surfaceBlack,
        surfaceContainer = SolstoneColors.darkGround,
        surfaceContainerHigh = SolstoneColors.darkSurface,
        surfaceContainerHighest = SolstoneColors.surfaceCream,
        surfaceContainerLow = SolstoneColors.surfaceBlack,
        surfaceContainerLowest = SolstoneColors.surfaceBlack,
    ).withBrandFixed()

    fun scheme(variant: ThemeVariant): ColorScheme = when (variant) {
        ThemeVariant.LIGHT_STANDARD -> lightStandard
        ThemeVariant.LIGHT_MEDIUM -> lightMedium
        ThemeVariant.LIGHT_HIGH -> lightHigh
        ThemeVariant.DARK_STANDARD -> darkStandard
        ThemeVariant.DARK_MEDIUM -> darkMedium
        ThemeVariant.DARK_HIGH -> darkHigh
    }
}

private fun ColorScheme.withBrandFixed(): ColorScheme = copy(
    primaryFixed = SolstoneColors.solOrange,
    onPrimaryFixed = SolstoneColors.surfaceDark,
    primaryFixedDim = SolstoneColors.solOrange,
    onPrimaryFixedVariant = SolstoneColors.surfaceDark,
    secondaryFixed = SolstoneColors.solOrange,
    onSecondaryFixed = SolstoneColors.surfaceDark,
    secondaryFixedDim = SolstoneColors.solOrange,
    onSecondaryFixedVariant = SolstoneColors.surfaceDark,
    tertiaryFixed = SolstoneColors.solOrange,
    onTertiaryFixed = SolstoneColors.surfaceDark,
    tertiaryFixedDim = SolstoneColors.solOrange,
    onTertiaryFixedVariant = SolstoneColors.surfaceDark,
)
