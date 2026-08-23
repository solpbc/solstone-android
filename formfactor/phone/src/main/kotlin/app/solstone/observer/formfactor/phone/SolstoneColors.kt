// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.graphics.Color

/**
 * Single source of every colour literal in this module.
 * Brand seeds are unaltered; extras are neutrals and error inks the seeds cannot supply.
 */
object SolstoneColors {
    // Brand seeds
    /** sol.orange — decoration only */
    val solOrange = Color(0xFFE8913A)

    /** sol.gold — decoration only */
    val solGold = Color(0xFFFFCC33)

    /** sol.orange.accessible — focus, non-text components, large text */
    val solOrangeAccessible = Color(0xFFB06A1A)

    /** text.orange.aa — only orange for normal-size text, light grounds only */
    val textOrangeAa = Color(0xFFA15F17)

    /** surface.cream — warm ground */
    val surfaceCream = Color(0xFFFCF3E4)

    /** surface.cream.bright — bright warm ground */
    val surfaceCreamBright = Color(0xFFFEFCF8)

    /** surface.dark — dark ground; also ink on light grounds */
    val surfaceDark = Color(0xFF1A1A1A)

    /** ink.on.dark — only designated AA-safe normal-size text ink on dark */
    val inkOnDark = Color(0xFFFFFFFF)

    // Extras (not brand seeds)
    /** Light/high ground. Same hex as [inkOnDark]; a surface role, not text-on-dark. */
    val surfaceWhite = Color(0xFFFFFFFF)

    /** Dark surfaceDim / surfaceContainerLowest / scrim. Darker than the dark seed. */
    val surfaceBlack = Color(0xFF000000)

    /** Dark surfaceBright and raised containers. */
    val surfaceDarkLift = Color(0xFF2E2E2E)

    /** Light error / onErrorContainer. Error cannot be brand orange. */
    val errorRed = Color(0xFFB3261E)

    /** Dark error / onErrorContainer. */
    val errorPink = Color(0xFFF2B8B5)

    /** Light errorContainer. */
    val errorContainerLight = Color(0xFFF9DEDC)

    /** Dark onError and errorContainer. */
    val errorContainerDark = Color(0xFF601410)

    /** Status-on green, light standard. */
    val statusOnGreenLightStandard = Color(0xFF1C7440)

    /** Status-on green, light medium. */
    val statusOnGreenLightMedium = Color(0xFF187444)

    /** Status-on green, light high. */
    val statusOnGreenLightHigh = Color(0xFF147448)

    /** Status-on green, dark standard. */
    val statusOnGreenDarkStandard = Color(0xFF14A41C)

    /** Status-on green, dark medium. */
    val statusOnGreenDarkMedium = Color(0xFF18A414)

    /** Status-on green, dark high. */
    val statusOnGreenDarkHigh = Color(0xFF00A434)

    val palette: Set<Color>
        get() = setOf(
            solOrange,
            solGold,
            solOrangeAccessible,
            textOrangeAa,
            surfaceCream,
            surfaceCreamBright,
            surfaceDark,
            inkOnDark,
            surfaceWhite,
            surfaceBlack,
            surfaceDarkLift,
            errorRed,
            errorPink,
            errorContainerLight,
            errorContainerDark,
            statusOnGreenLightStandard,
            statusOnGreenLightMedium,
            statusOnGreenLightHigh,
            statusOnGreenDarkStandard,
            statusOnGreenDarkMedium,
            statusOnGreenDarkHigh,
        )
}
