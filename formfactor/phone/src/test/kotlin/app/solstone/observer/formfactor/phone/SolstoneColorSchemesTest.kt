// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolstoneColorSchemesTest {
    @Test
    fun colorSchemeRolesIs48() {
        assertEquals(48, ColorSchemeRoles.size)
    }

    @Test
    fun sixTablesArePairwiseDistinctByRoleColor() {
        val schemes = allSolstoneSchemes()
        for (i in schemes.indices) {
            for (j in i + 1 until schemes.size) {
                val differs = ColorSchemeRoles.any { name ->
                    schemes[i].colorForRole(name) != schemes[j].colorForRole(name)
                }
                assertTrue(differs, "schemes $i and $j match on every role")
            }
        }
    }

    @Test
    fun eachRoleReadsFromConstantsObject() {
        val palette = SolstoneColors.palette
        for (scheme in allSolstoneSchemes()) {
            for (name in ColorSchemeRoles) {
                assertTrue(scheme.colorForRole(name) in palette)
            }
        }
    }

    @Test
    fun fixedAccentRolesMatchAcrossLightAndDark() {
        val roles = listOf(
            "primaryFixed",
            "primaryFixedDim",
            "secondaryFixed",
            "secondaryFixedDim",
            "tertiaryFixed",
            "tertiaryFixedDim",
            "onPrimaryFixed",
            "onPrimaryFixedVariant",
            "onSecondaryFixed",
            "onSecondaryFixedVariant",
            "onTertiaryFixed",
            "onTertiaryFixedVariant",
        )
        val pairs = listOf(
            SolstoneColorSchemes.lightStandard to SolstoneColorSchemes.darkStandard,
            SolstoneColorSchemes.lightMedium to SolstoneColorSchemes.darkMedium,
            SolstoneColorSchemes.lightHigh to SolstoneColorSchemes.darkHigh,
        )
        for ((light, dark) in pairs) {
            for (role in roles) {
                assertEquals(
                    light.colorForRole(role),
                    dark.colorForRole(role),
                    role,
                )
            }
        }
    }
}
