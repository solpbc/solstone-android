// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.core.layout.WindowSizeClass
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@RunWith(AndroidJUnit4::class)
class PhonePostureDirectiveTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun separatingHingeExcludesItsBoundsFromTheRenderedSplit() {
        var directive: PaneScaffoldDirective? = null
        var hingeBounds: Rect? = null

        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 800.dp)),
            ) {
                val density = LocalDensity.current
                val adaptiveInfo = adaptiveInfo(density)
                hingeBounds = adaptiveInfo.windowPosture.hingeList.single().bounds
                directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(adaptiveInfo)
                PhoneObserverScreen(
                    loadState = loaded(audioOn()),
                    status = connected(),
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                    windowAdaptiveInfo = adaptiveInfo,
                )
            }
        }

        composeRule.onNodeWithTag("phoneSplit").assertIsDisplayed()
        val excluded = directive!!.excludedBounds.singleOrNull()
        assertTrue("excludedBounds=${directive!!.excludedBounds}", excluded != null)
        val input = hingeBounds!!
        assertEquals("hingeIn=$input hingeOut=$excluded", input, excluded)
        val deckBounds = composeRule.onNodeWithTag("deck").fetchSemanticsNode().boundsInRoot
        val detailBounds = composeRule.onNodeWithTag("phoneDefaultDetail").fetchSemanticsNode().boundsInRoot

        assertFalse("deck=$deckBounds hingeIn=$input hingeOut=$excluded", deckBounds.crosses(excluded!!))
        assertFalse("detail=$detailBounds hingeIn=$input hingeOut=$excluded", detailBounds.crosses(excluded))
    }

    private fun adaptiveInfo(density: Density): WindowAdaptiveInfo {
        val hingeBounds = with(density) {
            Rect(
                left = 398.dp.toPx(),
                top = 0f,
                right = 402.dp.toPx(),
                bottom = 800.dp.toPx(),
            )
        }
        return WindowAdaptiveInfo(
            windowSizeClass = WindowSizeClass(800, 800),
            windowPosture = Posture(
                isTabletop = false,
                hingeList = listOf(
                    HingeInfo(
                        bounds = hingeBounds,
                        isFlat = false,
                        isVertical = true,
                        isSeparating = true,
                        isOccluding = true,
                    ),
                ),
            ),
        )
    }

    private fun Rect.crosses(excluded: Rect): Boolean =
        left < excluded.right - ROUNDING_TOLERANCE_PX &&
            right > excluded.left + ROUNDING_TOLERANCE_PX &&
            top < excluded.bottom - ROUNDING_TOLERANCE_PX &&
            bottom > excluded.top + ROUNDING_TOLERANCE_PX

    private fun audioOn() = SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE)

    private fun loaded(vararg sources: SourceStatus) = LoadState.Loaded(
        SourcesReadModel(
            observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
            sources = sources.toList(),
        ),
    )

    private fun connected() = PhoneStatusModel(
        paired = true,
        online = true,
        pendingCount = 0,
        hasContentPending = false,
    )

    private companion object {
        const val ROUNDING_TOLERANCE_PX = 1f
    }
}
