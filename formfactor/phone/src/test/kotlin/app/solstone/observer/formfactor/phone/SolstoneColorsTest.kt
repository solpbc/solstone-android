// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertTrue

class SolstoneColorsTest {
    @Test
    fun brandSeedNamesExistAndEveryRoleReadsFromObject() {
        SolstoneColors.solOrange
        SolstoneColors.solGold
        SolstoneColors.solOrangeAccessible
        SolstoneColors.textOrangeAa
        SolstoneColors.surfaceCream
        SolstoneColors.surfaceCreamBright
        SolstoneColors.surfaceDark
        SolstoneColors.inkOnDark
        val palette = SolstoneColors.palette
        for (scheme in allSolstoneSchemes()) {
            for (name in ColorSchemeRoles) {
                assertTrue(
                    scheme.colorForRole(name) in palette,
                    "$name is not a SolstoneColors property",
                )
            }
        }
    }
}

internal fun allSolstoneSchemes() = listOf(
    SolstoneColorSchemes.lightStandard,
    SolstoneColorSchemes.lightMedium,
    SolstoneColorSchemes.lightHigh,
    SolstoneColorSchemes.darkStandard,
    SolstoneColorSchemes.darkMedium,
    SolstoneColorSchemes.darkHigh,
)
