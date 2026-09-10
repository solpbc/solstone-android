// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.ColorScheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An off switch may carry no accent fill.
 *
 * 🔴 Every `Switch` in the shell took Material3's defaults, which draw the unchecked thumb and
 * border in `colorScheme.outline` — and in the light palettes `outline` **is** the accent. Measured:
 * `outline` sits **1.19:1** from `primary` in `lightStandard` and is *exactly equal* to it in
 * `lightMedium`. So a source the owner had turned off carried a filled ochre thumb, and on the
 * source detail screen it sat above a genuinely-on switch while being the more saturated of the two.
 *
 * ⚠ **The rule is scoped to schemes that HAVE a distinct accent, and that is not a loophole.** The
 * high-contrast and dark palettes deliberately collapse `primary` onto `onSurface` — ink or white —
 * so in those there is no accent to borrow, and on/off are told apart by which element carries the
 * fill and where the thumb sits. Asserting a hue rule there would be asserting against a palette
 * that does not exist, and the bar would have to be lowered until it caught nothing.
 */
class SolstoneSwitchColorsTest {
    /** A scheme where the accent is its own colour rather than collapsed onto ink or white. */
    private fun ColorScheme.hasDistinctAccent(): Boolean = primary != onSurface

    @Test
    fun theOffStateCarriesNoAccentFill() {
        val checked = allSolstoneSchemes().filter { it.hasDistinctAccent() }
        // ⛔ Non-vacuity: if a palette retune collapsed every scheme, this rule would silently stop
        // measuring anything and still pass.
        assertTrue(checked.isNotEmpty(), "no scheme has a distinct accent, so this asserts nothing")

        checked.forEach { scheme ->
            // What `solstoneSwitchColors()` uses for the off state.
            mapOf(
                "onSurface (off thumb + border)" to scheme.onSurface,
                "surfaceContainerHighest (off track)" to scheme.surfaceContainerHighest,
            ).forEach { (what, color) ->
                val ratio = contrastRatio(color, scheme.primary)
                assertTrue(
                    ratio >= 2.0,
                    "$what is $ratio from the on-state track, so off reads as on",
                )
            }
        }
    }

    @Test
    fun theMaterialDefaultOffThumbIsWhyThisFileExists() {
        // ✅ The positive control, and it is the defect measured rather than described. Without it
        // the rule above could be passing because the bar is meaningless.
        val failing = allSolstoneSchemes().filter { it.hasDistinctAccent() }
            .map { contrastRatio(it.outline, it.primary) }
        assertTrue(failing.isNotEmpty())
        failing.forEach { ratio ->
            assertTrue(ratio < 2.0, "outline is $ratio from primary — it would have passed")
        }
    }

    @Test
    fun bothStatesKeepTheirThumbVisibleAgainstTheirOwnTrack() {
        allSolstoneSchemes().forEach { scheme ->
            assertTrue(
                contrastRatio(scheme.onSurface, scheme.surfaceContainerHighest) >= 3.0,
                "off thumb invisible on its own track",
            )
            assertTrue(
                contrastRatio(scheme.onPrimary, scheme.primary) >= 3.0,
                "on thumb invisible on its own track",
            )
            // The dominant visual mass of the two states has to differ in every scheme, collapsed
            // palette or not — this is the glance-level assertion the hue rule cannot make.
            assertTrue(
                contrastRatio(scheme.surfaceContainerHighest, scheme.primary) >= 3.0,
                "the two tracks are indistinguishable",
            )
        }
    }

    @Test
    fun noSwitchInTheShellTakesTheDefaultColours() {
        // ⚠ Source-level, because `solstoneSwitchColors()` is @Composable and a unit test cannot
        // call it. What actually regresses is a NEW `Switch(` added without the colours.
        // ⛔ Matched as a call on its own line, not `contains("Switch(")` — that also matched
        // `sourceEarnsSwitch(` and named a file with no switch in it.
        val callSite = Regex("^\\s*Switch\\($", RegexOption.MULTILINE)
        val root = File("src/main/kotlin/app/solstone/observer/formfactor/phone")
        val switchFiles = root.walkTopDown().filter { it.extension == "kt" }
            .filter { callSite.containsMatchIn(it.readText()) }
            .toList()
        assertEquals(2, switchFiles.size, "switch call sites moved: ${switchFiles.map { it.name }}")
        switchFiles.forEach { file ->
            val text = file.readText()
            val switches = callSite.findAll(text).count()
            val colored = text.split("colors = solstoneSwitchColors(),").size - 1
            assertEquals(switches, colored, "${file.name}: $switches switches, $colored coloured")
            assertTrue(switches > 0, file.name)
            assertFalse(text.contains("SwitchDefaults"), "${file.name} builds its own palette")
        }
    }
}
