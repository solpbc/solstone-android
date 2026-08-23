// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class PhoneObserverScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun greetingSlotExistsAndRendersNoCopy() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        assertEquals(1, composeRule.onAllNodesWithTag("greetingSlot").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("good morning", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("good afternoon", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("good evening", substring = true).assertDoesNotExist()
    }

    @Test
    fun audioHasSwitchAndCameraDoesNot() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn(), cameraOn()),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        composeRule.onNodeWithTag("sourceSwitch-audio", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("sourceSwitch-camera", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun syntheticWatchIdHasNoSwitch() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(status("watch", SourceState.OFF, SourceWish.Off)),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        composeRule.onNodeWithTag("sourceSwitch-watch", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("sourceTile-watch").assertIsDisplayed()
    }

    @Test
    fun bodyTapDoesNotToggleSwitch() {
        var toggles = 0
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> toggles += 1 },
            )
        }
        composeRule.onNodeWithTag("sourceBody-audio", useUnmergedTree = true).performClick()
        assertEquals(0, toggles)
    }

    @Test
    fun pillConnectedCopy() {
        composeRule.setContent { PhoneStatusPill(model = connected(), onClick = {}) }
        composeRule.onNodeWithText("connected").assertIsDisplayed()
    }

    @Test
    fun pillSyncingCopy() {
        composeRule.setContent {
            PhoneStatusPill(model = PhoneStatusModel(true, true, 4, true), onClick = {})
        }
        composeRule.onNodeWithText("4 syncing").assertIsDisplayed()
    }

    @Test
    fun pillOfflineCopy() {
        composeRule.setContent {
            PhoneStatusPill(model = PhoneStatusModel(true, false, 2, true), onClick = {})
        }
        composeRule.onNodeWithText("offline · 2 waiting").assertIsDisplayed()
    }

    @Test
    fun pillNotPairedCopy() {
        composeRule.setContent {
            PhoneStatusPill(model = PhoneStatusModel(false, true, 0, false), onClick = {})
        }
        composeRule.onNodeWithText("not paired").assertIsDisplayed()
    }

    @Test
    fun statusPaneOpensAndBackReachesLadder() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = PhoneStatusModel(true, true, 1, true),
                waiting = listOf(audioOn()),
                onToggle = { _, _ -> },
            )
        }
        composeRule.onNodeWithTag("statusPill").performClick()
        composeRule.onNodeWithTag("statusPane").assertIsDisplayed()
        composeRule.onNodeWithTag("statusPaneHeading").assertIsDisplayed()
        composeRule.onNodeWithText("what is waiting").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.onNodeWithTag("statusPane").assertDoesNotExist()
    }

    @Test
    fun deckSemanticsHiddenWhilePaneOpen() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        composeRule.onNodeWithTag("statusPill").performClick()
        composeRule.onNodeWithTag("statusPane").assertIsDisplayed()
        composeRule.onNodeWithText("audio", useUnmergedTree = false).assertDoesNotExist()
    }

    @Test
    fun waitingRowCarriesSourceIdentity() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = PhoneStatusModel(true, false, 1, true),
                waiting = listOf(audioOn()),
                onToggle = { _, _ -> },
            )
        }
        composeRule.onNodeWithTag("statusPill").performClick()
        composeRule.onNodeWithTag("waitingRow-audio").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("statusPane").assertDoesNotExist()
    }

    @Test
    fun liveRegionTextDoesNotContainCount() {
        composeRule.setContent {
            PhoneStatusPill(
                model = PhoneStatusModel(true, true, 7, true),
                onClick = {},
            )
        }
        val live = composeRule.onNodeWithTag("statusLiveRegion", useUnmergedTree = true)
            .fetchSemanticsNode()
        val announced = live.config.toString()
        assertTrue("syncing" in announced || live.children.isNotEmpty())
        composeRule.onNodeWithTag("statusState", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("7 syncing").assertIsDisplayed()
    }

    @Test
    fun failedLoadIsNonTextAndHasNoOwnerProse() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = LoadState.Failed(IllegalStateException("boom")),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        composeRule.onNodeWithTag("sourcesFailed").assertIsDisplayed()
        composeRule.onNodeWithText("boom").assertDoesNotExist()
    }

    @Test
    fun switchMeetsMinimumTouchTarget() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        val node = composeRule.onNodeWithTag("sourceSwitch-audio", useUnmergedTree = true).fetchSemanticsNode()
        val density = composeRule.density
        val minPx = with(density) { MINIMUM_TOUCH_TARGET_DP.dp.toPx() }
        assertTrue(node.size.width >= minPx - 1f)
        assertTrue(node.size.height >= minPx - 1f)
    }

    @Test
    fun tileNotPresentedAsRunningForAwaitingSetup() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(status("audio", SourceState.SETTING_UP, SourceWish.On)),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        composeRule.onNodeWithText("taking it in", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("SETTING_UP", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("audio", tileStateDescription("audio"))
    }

    @Test
    fun pausedSourceRendersNoInventedCopy() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(status("audio", SourceState.PAUSED, SourceWish.On)),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        composeRule.onNodeWithText("paused", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("PAUSED", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("audio", tileStateDescription("audio"))
    }

    @Test
    fun capturingSourceRendersTakingItIn() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        composeRule.onNodeWithText("taking it in", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("audio taking it in", tileStateDescription("audio"))
    }

    private fun tileStateDescription(sourceId: String): String? =
        composeRule.onNodeWithTag("sourceTile-$sourceId")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)
}

private fun audioOn() = status("audio", SourceState.ON, SourceWish.On)

private fun cameraOn() = status("camera", SourceState.ON, SourceWish.On)

private fun status(id: String, state: SourceState, wish: SourceWish) =
    SourceStatus(id, wish, state, ReasonCode.NONE)

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
