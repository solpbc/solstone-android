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

    /**
     * The dark GROUND, and the surface that sits on it. Warm, not grey.
     *
     * `mobile-shell.md` section 2.6: "the ground is warm in both appearances -- sol
     * cream by day, a warm near-black by night." [surfaceDark] and [surfaceDarkLift]
     * are pure neutral greys (R=G=B), so the whole dark shell read as a competent
     * generic Android app -- which is the same defect iOS shipped from
     * `systemGroupedBackground`, arrived at by a different route.
     *
     * These are USAGE values, not identity: [surfaceDark] is a CMO brand seed and is
     * unchanged, and it stays the ink on light grounds, where it belongs. Same split
     * iOS made -- `Colors.swift` keeps the seeds, `DeckStyle.swift` owns the ground --
     * and the same two values it landed on, so the two platforms' dark grounds match.
     *
     * Both are DARKER than the greys they replace, so every white-ink contrast ratio
     * moves up rather than down.
     */
    val darkGround = Color(0xFF121010)

    /** A tile, a card, a grouped row on the dark ground. Warm sibling of [surfaceDarkLift]. */
    val darkSurface = Color(0xFF201D19)

    /** One step above [darkSurface]: a raised container, a bright surface. */
    val darkSurfaceRaised = Color(0xFF2B2721)

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

    // -- journal-mark values --------------------------------------------------
    // NOT theme colours, and deliberately non-adapting. `journal-mark.md` is a locked
    // cross-platform contract whose whole premise is that the mark renders identically
    // on web, iOS, Android, macOS and Windows, so an owner can match the mark their
    // device shows against the one their journal shows. They live in this object
    // because this module allows exactly one home for a colour literal, not because
    // they belong to the scheme -- and they stay out of `palette` for the same reason.

    /**
     * `journal-mark.md` section 4.3 -- the generic mark's upright chip, dashed.
     *
     * Reads [solOrange], NOT the `#E8923A` the mark spec prints. That hex is the
     * retired 260629 pair: CMO G1 moved sol orange to `#E8913A` on 2026-08-19, and G5
     * the next day ruled that everything derived from the mark palette follows it --
     * naming the journal mark's chip 1 explicitly in its own decision table
     * (`#E8923A` -> `#E8913A`). The icon GENERATOR was swept then; the mark spec's
     * prose in sections 4.3 and 8.5 was not, so it still prints the old value.
     *
     * Do not "restore" the literal from the document. One green-channel unit apart and
     * visually identical, which is exactly why it survived the sweep.
     */
    val markGenericChipOne = solOrange

    /** `journal-mark.md` section 4.3 -- the generic mark's 45 degree chip: mark-palette gold. */
    val markGenericChipTwo = Color(0xFFD4A017)

    /** `journal-mark.md` section 2.2 -- `mark.words.sep`, the middot's muted warm ink. */
    val markMiddotInk = Color(0xFF6E6453)

    /** `journal-mark.md` section 3 -- `mark.card.fill`, fixed warm-bright in every appearance. */
    val markCardFill = Color(0xFFFFFDF9)

    /** `journal-mark.md` section 3 -- `mark.card.border`, the warm hairline. */
    val markCardBorder = Color(0xFFE7D8C6)

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
            darkGround,
            darkSurface,
            darkSurfaceRaised,
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
