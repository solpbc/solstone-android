// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets as LayoutWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.WindowInsets
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellInsetInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gridReachesUnderAppBarAfterScroll() {
        var firstTopAtRest = Float.NaN
        var minTopAfterScroll = Float.POSITIVE_INFINITY
        val ids = (0 until 20).map { "item-$it" }
        composeRule.setContent {
            PhoneShell(
                title = { Text("bar") },
            ) { padding ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("probeGrid"),
                    contentPadding = padding,
                ) {
                    items(ids) { id ->
                        Text(
                            text = id,
                            modifier = Modifier
                                .height(120.dp)
                                .testTag(id)
                                .onGloballyPositioned { coords ->
                                    val top = coords.boundsInRoot().top
                                    if (id == "item-0" && firstTopAtRest.isNaN()) firstTopAtRest = top
                                    if (top < minTopAfterScroll) minTopAfterScroll = top
                                },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val appBarBottom = composeRule.onNodeWithTag("phoneAppBar").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "at rest first tile top $firstTopAtRest should be at or below app bar $appBarBottom",
            firstTopAtRest >= appBarBottom,
        )
        minTopAfterScroll = Float.POSITIVE_INFINITY
        composeRule.onNodeWithTag("probeGrid").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertTrue(
            "some tile top $minTopAfterScroll should be above app bar $appBarBottom",
            minTopAfterScroll < appBarBottom,
        )
    }

    @Test
    fun appBarDoesNotExceedDefaultHeight() {
        var statusBarPx = 0
        composeRule.setContent {
            PhoneShell { _ ->
                statusBarPx = LayoutWindowInsets.statusBars.getTop(LocalDensity.current)
                Box(Modifier.fillMaxSize())
            }
        }
        composeRule.waitForIdle()
        val bar = composeRule.onNodeWithTag("phoneAppBar").fetchSemanticsNode()
        val maxPx = with(composeRule.density) { 64.dp.toPx() } + statusBarPx
        assertTrue("app bar height ${bar.size.height} > $maxPx", bar.size.height <= maxPx + 2f)
    }

    /**
     * ⚠ **Needs a GESTURE-NAVIGATION device, and says so now instead of failing.**
     *
     * `safeGestures` exceeds `navigationBars` only where the system reserves a gesture inset.
     * On a 3-button-navigation device both resolve to the nav bar's height and are equal, so this
     * assertion is false there by configuration rather than by regression. The GMD `pixel5api35`
     * the device gate runs on uses gesture navigation, which is what the method name records.
     *
     * ⛔ It previously just failed. Run on the bench Galaxy A36 (3-button nav) it reported
     * `Values should be different. Actual: 135` — a red that reads as a real inset regression and
     * is not one, in a module whose other 96 tests pass on the same hardware. `navigation_mode`
     * is `0` three-button, `1` two-button, `2` gesture; an unreadable value assumes gesture so
     * the gate keeps asserting rather than silently skipping where it matters.
     */
    @Test
    fun safeGesturesDiffersFromNavigationBarsOnGmd() {
        val navigationMode = runCatching {
            android.provider.Settings.Secure.getInt(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation().targetContext.contentResolver,
                "navigation_mode",
            )
        }.getOrDefault(GESTURE_NAVIGATION_MODE)
        org.junit.Assume.assumeTrue(
            "needs gesture navigation; safeGestures == navigationBars on button nav",
            navigationMode == GESTURE_NAVIGATION_MODE,
        )
        var gestures = -1
        var nav = -1
        composeRule.setContent {
            PhoneShell { _ ->
                val density = LocalDensity.current
                gestures = LayoutWindowInsets.safeGestures.getBottom(density)
                nav = LayoutWindowInsets.navigationBars.getBottom(density)
                Box(Modifier.fillMaxSize())
            }
        }
        composeRule.waitForIdle()
        assertNotEquals(gestures, nav)
        assertTrue(gestures > 0)
    }

    @Test
    fun landscapeHorizontalPaddingIsNonZeroWithCutout() {
        var captured: PaddingValues? = null
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(80, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(80, 24, 0, 48))
            .build()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 400.dp))
                    then DeviceConfigurationOverride.WindowInsets(insets),
            ) {
                PhoneShell { padding ->
                    captured = padding
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        composeRule.waitForIdle()
        val padding = requireNotNull(captured)
        val start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
        assertTrue("horizontal cutout leg should be non-zero, was $start", start > 0.dp)
    }

    private companion object {
        /** `Settings.Secure.navigation_mode`: 0 three-button, 1 two-button, 2 gesture. */
        const val GESTURE_NAVIGATION_MODE = 2
    }
}
