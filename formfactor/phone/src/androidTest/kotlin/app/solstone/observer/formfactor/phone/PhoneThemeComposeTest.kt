// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneThemeComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun phoneShellAppliesOneOfSixSolstoneSchemes() {
        var captured: ColorScheme? = null
        composeRule.setContent {
            PhoneShell { _ ->
                captured = MaterialTheme.colorScheme
                Text("probe")
            }
        }
        assertTrue(requireNotNull(captured).matchesAnySolstoneTable())
    }

    @Test
    fun forcedLightStandardMatchesTable() {
        var captured: ColorScheme? = null
        composeRule.setContent {
            PhoneTheme(ThemeVariant.LIGHT_STANDARD) {
                captured = MaterialTheme.colorScheme
                Text("probe")
            }
        }
        val scheme = requireNotNull(captured)
        ColorSchemeRoles.forEach { name ->
            assertEquals(
                SolstoneColorSchemes.lightStandard.colorForRole(name),
                scheme.colorForRole(name),
            )
        }
        assertNotNull(scheme)
    }
}

private fun androidx.compose.material3.ColorScheme.matchesAnySolstoneTable(): Boolean {
    val tables = listOf(
        SolstoneColorSchemes.lightStandard,
        SolstoneColorSchemes.lightMedium,
        SolstoneColorSchemes.lightHigh,
        SolstoneColorSchemes.darkStandard,
        SolstoneColorSchemes.darkMedium,
        SolstoneColorSchemes.darkHigh,
    )
    return tables.any { table ->
        ColorSchemeRoles.all { name -> colorForRole(name) == table.colorForRole(name) }
    }
}
