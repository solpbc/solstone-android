// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

class SolstoneColorSchemesContrastTest {
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
