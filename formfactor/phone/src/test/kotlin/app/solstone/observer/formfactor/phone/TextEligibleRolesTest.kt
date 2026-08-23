// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextEligibleRolesTest {
    @Test
    fun ac6aMembershipIsExact() {
        val expected = setOf(
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
        assertEquals(expected, TextBearingRoles)
        assertTrue(TextBearingRoles.all { it in ColorSchemeRoles })
    }

    @Test
    fun ac6bDecorationOrangesExcluded() {
        val forbidden = setOf(SolstoneColors.solOrange, SolstoneColors.solGold)
        for (scheme in allSolstoneSchemes()) {
            for (role in TextBearingRoles) {
                assertTrue(
                    scheme.colorForRole(role) !in forbidden,
                    "$role is a decoration orange",
                )
            }
            for (name in ColorSchemeRoles) {
                if (!name.startsWith("on")) continue
                assertTrue(
                    scheme.colorForRole(name) !in forbidden,
                    "$name is a decoration orange",
                )
            }
        }
    }
}
