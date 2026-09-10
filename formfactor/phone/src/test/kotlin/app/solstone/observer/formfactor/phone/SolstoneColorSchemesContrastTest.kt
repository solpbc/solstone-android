// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

class SolstoneColorSchemesContrastTest {
    /**
     * Every surface-container role has to stay inside its own polarity.
     *
     * 🔴 `darkHigh.surfaceContainerHighest` was `surfaceCream` — a light cream at the top of a dark
     * ladder. `onSurface` there is white, so any panel filled with that role would have rendered
     * white text on cream **in the one mode a low-vision owner turns on deliberately**. It had no
     * consumer in the shell, so nothing rendered it and nothing caught it; the first composable to
     * reach for the role tripped straight over it.
     *
     * ⚠ The rule is `onSurface` legibility rather than a hue check, because that is what a fill role
     * actually owes: whatever the palette does, text on this surface has to be readable.
     */
    @Test
    fun everySurfaceContainerRoleCarriesOnSurfaceText() {
        val ladder = ColorSchemeRoles.filter { it.startsWith("surfaceContainer") } +
            listOf("surface", "surfaceBright", "surfaceDim", "surfaceVariant")
        assertTrue(ladder.size >= 8, "the ladder shrank: $ladder")
        for (scheme in allSolstoneSchemes()) {
            for (role in ladder) {
                val ratio = contrastRatio(scheme.onSurface, scheme.colorForRole(role))
                assertTrue(ratio >= 4.5, "onSurface on $role is $ratio")
            }
        }
        // ✅ Positive control: the value that was there fails this, so the rule is measuring.
        assertTrue(
            contrastRatio(SolstoneColors.inkOnDark, SolstoneColors.surfaceCream) < 4.5,
        )
    }

    @Test
    fun textBearingRolesMeetAa() {
        for (scheme in allSolstoneSchemes()) {
            for (role in TextBearingRoles) {
                val ink = scheme.colorForRole(role)
                val fill = scheme.colorForRole(pairedFill(role))
                val ratio = contrastRatio(ink, fill)
                assertTrue(
                    ratio >= 4.5,
                    "$role contrast $ratio against $fill",
                )
            }
        }
    }

    @Test
    fun textOrangeAaMeetsAaOnLightGrounds() {
        val ink = SolstoneColors.textOrangeAa
        assertTrue(contrastRatio(ink, SolstoneColors.surfaceCream) >= 4.5)
        assertTrue(contrastRatio(ink, SolstoneColors.surfaceCreamBright) >= 4.5)
        assertTrue(contrastRatio(ink, SolstoneColors.surfaceWhite) >= 4.5)
    }

    @Test
    fun textOrangeAaFailsAaOnDarkGround() {
        assertTrue(
            contrastRatio(SolstoneColors.textOrangeAa, SolstoneColors.surfaceDark) < 4.5,
        )
    }

    @Test
    fun solOrangeAccessibleStaysInLargeTextBandOnAllGrounds() {
        val ink = SolstoneColors.solOrangeAccessible
        val grounds = listOf(
            SolstoneColors.surfaceCream,
            SolstoneColors.surfaceCreamBright,
            SolstoneColors.surfaceWhite,
            SolstoneColors.surfaceDark,
        )
        for (ground in grounds) {
            val ratio = contrastRatio(ink, ground)
            assertTrue(ratio >= 3.0 && ratio < 4.5, "ratio $ratio")
        }
    }
}

private fun pairedFill(textRole: String): String = when (textRole) {
    "primary", "secondary", "tertiary", "error",
    "onBackground", "onSurface", "onSurfaceVariant",
    -> "surface"
    "onPrimary" -> "primary"
    "onPrimaryContainer" -> "primaryContainer"
    "onPrimaryFixed" -> "primaryFixed"
    "onPrimaryFixedVariant" -> "primaryFixedDim"
    "onSecondary" -> "secondary"
    "onSecondaryContainer" -> "secondaryContainer"
    "onSecondaryFixed" -> "secondaryFixed"
    "onSecondaryFixedVariant" -> "secondaryFixedDim"
    "onTertiary" -> "tertiary"
    "onTertiaryContainer" -> "tertiaryContainer"
    "onTertiaryFixed" -> "tertiaryFixed"
    "onTertiaryFixedVariant" -> "tertiaryFixedDim"
    "inverseOnSurface", "inversePrimary" -> "inverseSurface"
    "onError" -> "error"
    "onErrorContainer" -> "errorContainer"
    else -> error("no paired fill for $textRole")
}

internal fun relativeLuminance(color: Color): Double {
    fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    val r = linearize(color.red)
    val g = linearize(color.green)
    val b = linearize(color.blue)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

internal fun contrastRatio(a: Color, b: Color): Double {
    val l1 = relativeLuminance(a)
    val l2 = relativeLuminance(b)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}
