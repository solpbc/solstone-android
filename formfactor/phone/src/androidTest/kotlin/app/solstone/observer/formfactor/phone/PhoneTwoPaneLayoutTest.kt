// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
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
class PhoneTwoPaneLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deckTapsKeepDeckWhileBackWalksDetailStack() {
        setWideContent()

        composeRule.onNodeWithTag("importTile").performClick()
        composeRule.onNodeWithTag("addMoreTile").performClick()
        composeRule.onNodeWithTag("sourceBody-audio", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag(VERDICT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("deck").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-aboutSolstone").performClick()
        composeRule.onNodeWithTag("licencesRow").performClick()
        composeRule.onNodeWithTag("deck").assertIsDisplayed()

        Espresso.pressBack()

        composeRule.onNodeWithTag("licencesRow").assertIsDisplayed()
        composeRule.onNodeWithTag("deck").assertIsDisplayed()

        Espresso.pressBack()

        composeRule.onNodeWithTag("phoneDefaultDetail").assertIsDisplayed()
        composeRule.onNodeWithTag("deck").assertIsDisplayed()
    }

    @Test
    fun splitRemainsAfterSavedStateRestore() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(MEDIUM_SIZE),
            ) {
                PhoneObserverScreen(
                    loadState = loaded(audioOn()),
                    status = connected(),
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                )
            }
        }
        composeRule.onNodeWithTag("phoneSplit").assertIsDisplayed()

        tester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag("phoneSplit").assertIsDisplayed()
    }

    @Test
    fun deckColumnIsAtLeast360DpAtMediumWidth() {
        val mediumDeckWidth = renderedDeckWidthDp(MEDIUM_SIZE)
        assertDeckIsColumn(MEDIUM_SIZE, mediumDeckWidth)
    }

    @Test
    fun deckColumnIs412DpAtLargeWidth() {
        val largeDeckWidth = renderedDeckWidthDp(LARGE_SIZE)
        assertDeckIsColumn(LARGE_SIZE, largeDeckWidth)
        assertEquals(412f, largeDeckWidth, 1f)
    }

    private fun setWideContent() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(MEDIUM_SIZE),
            ) {
                PhoneObserverScreen(
                    loadState = loaded(audioOn()),
                    status = connected(),
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                )
            }
        }
        composeRule.onNodeWithTag("phoneSplit").assertIsDisplayed()
    }

    private fun renderedDeckWidthDp(size: DpSize): Float {
        var density = 0f
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size),
            ) {
                density = LocalDensity.current.density
                PhoneObserverScreen(
                    loadState = loaded(audioOn()),
                    status = connected(),
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                )
            }
        }
        composeRule.waitForIdle()
        return composeRule.onNodeWithTag("deck").fetchSemanticsNode().boundsInRoot.width / density
    }

    private fun assertDeckIsColumn(size: DpSize, deckWidth: Float) {
        composeRule.onNodeWithTag("phoneSplit").assertIsDisplayed()
        assertTrue("deck was $deckWidth dp", deckWidth >= 360f)
        assertTrue("deck was $deckWidth dp", deckWidth < size.width.value)
    }

    private companion object {
        val MEDIUM_SIZE = DpSize(800.dp, 800.dp)
        val LARGE_SIZE = DpSize(1_200.dp, 800.dp)
    }
}

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
