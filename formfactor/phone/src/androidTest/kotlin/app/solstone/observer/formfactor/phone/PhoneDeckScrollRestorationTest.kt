// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneDeckScrollRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deckScrollPositionSurvivesRestoreWhileSourceDetailIsOpen() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                PhoneObserverScreen(
                    loadState = loadedSources(),
                    status = connected(),
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                )
            }
        }

        composeRule.onNodeWithTag("sourceGrid").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val beforeRoute = sourceGridScrollOffset()
        assertTrue("source grid did not scroll: $beforeRoute", beforeRoute > 0f)

        val tile = composeRule.onNodeWithTag(visibleSourceTileTag()).fetchSemanticsNode()
        assertTrue(tile.config[SemanticsActions.CustomActions].first().action?.invoke() == true)
        composeRule.onNodeWithTag("phoneUp").assertIsDisplayed()

        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("phoneUp").performClick()
        composeRule.waitForIdle()
        val afterRestoreAndPop = sourceGridScrollOffset()
        assertEquals(beforeRoute, afterRestoreAndPop, 0.5f)
    }

    private fun sourceGridScrollOffset(): Float = composeRule
        .onNodeWithTag("sourceGrid")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()

    private fun visibleSourceTileTag(): String {
        val sourceTile = SemanticsMatcher("visible source tile") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("sourceTile-source-") == true
        }
        return composeRule
            .onAllNodes(sourceTile, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first { it.boundsInRoot.bottom > 0f }
            .config[SemanticsProperties.TestTag]
    }

    private fun loadedSources() = LoadState.Loaded(
        SourcesReadModel(
            observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
            sources = (0 until 40).map { index ->
                SourceStatus("source-$index", SourceWish.On, SourceState.ON, ReasonCode.NONE)
            },
        ),
    )

    private fun connected() = PhoneStatusModel(
        paired = true,
        online = true,
        pendingCount = 0,
        hasContentPending = false,
    )
}
