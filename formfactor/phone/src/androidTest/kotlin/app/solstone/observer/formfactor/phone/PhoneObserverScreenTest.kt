// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneObserverScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun greetingSlotRendersGoodMorningAtHourFive() {
        composeRule.setContent {
            PhoneDeck(
                loadState = loaded(audioOn()),
                contentPadding = PaddingValues(0.dp),
                widthClass = WidthClass.COMPACT,
                paneOpen = false,
                onOpenSource = {},
                onToggle = { _, _ -> },
                onOpenImport = {},
                onOpenAddMore = {},
                hour = 5,
            )
        }
        assertEquals(1, composeRule.onAllNodesWithTag("greetingSlot").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("good morning").assertIsDisplayed()
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
        composeRule.onNodeWithTag("statusPaneHeading").assertTextEquals("status")
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
        val decrements = 3
        var pending by mutableStateOf(6)
        composeRule.setContent {
            PhoneStatusPill(
                model = PhoneStatusModel(true, true, pending, true),
                onClick = {},
            )
        }
        composeRule.waitForIdle()
        val live = liveRegionNode()
        val state = stateDescriptionNode()
        assertNotEquals(live.id, state.id)
        val firstLive = announcedText(live)
        val descriptions = mutableListOf(state.config.getOrNull(SemanticsProperties.StateDescription))
        assertFalse("live announced contains count $pending: $firstLive", pending.toString() in firstLive)
        repeat(decrements) {
            pending -= 1
            composeRule.waitForIdle()
            val liveNow = announcedText(liveRegionNode())
            assertEquals(firstLive, liveNow)
            assertFalse("live announced contains count $pending: $liveNow", pending.toString() in liveNow)
            descriptions += stateDescriptionNode().config.getOrNull(SemanticsProperties.StateDescription)
        }
        assertEquals(decrements, descriptions.zipWithNext().count { it.first != it.second })
    }

    @Test
    fun deckReachesUnderAppBarAfterScroll() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(*(0 until 40).map { status("s-$it", SourceState.ON, SourceWish.On) }.toTypedArray()),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        composeRule.waitForIdle()
        val appBarBottom = composeRule.onNodeWithTag("phoneAppBar").fetchSemanticsNode().boundsInRoot.bottom
        val firstTop = composeRule.onNodeWithTag("sourceTile-s-0", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertTrue(
            "at rest first tile top $firstTop should be at or below app bar $appBarBottom",
            firstTop >= appBarBottom,
        )
        composeRule.onNodeWithTag("sourceGrid").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val minTop = composeRule.onAllNodes(sourceTileMatcher(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .minOf { it.boundsInRoot.top }
        assertTrue(
            "some deck tile top $minTop should be above app bar $appBarBottom",
            minTop < appBarBottom,
        )
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
        composeRule.onNodeWithText("SETTING_UP", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("audio setting up", tileStateDescription("audio"))
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
        composeRule.onNodeWithText("paused", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("PAUSED", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("audio paused", tileStateDescription("audio"))
    }

    @Test
    fun capturingSourceRendersOn() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        assertEquals(listOf("audio", "on"), unmergedTexts("sourceBody-audio"))
        assertEquals("audio on", tileStateDescription("audio"))
    }

    @Test
    fun needsAttentionAudioTileRendersLabelAndStateOnly() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(status("audio", SourceState.NEEDS_ATTENTION, SourceWish.On)),
                status = connected(),
                onToggle = { _, _ -> },
            )
        }
        assertEquals(listOf("audio", "needs attention"), unmergedTexts("sourceBody-audio"))
        assertEquals("audio needs attention", tileStateDescription("audio"))
        composeRule.onNodeWithText("tap to fix", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun tileStateDescription(sourceId: String): String? =
        composeRule.onNodeWithTag("sourceTile-$sourceId")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)

    private fun unmergedTexts(tag: String): List<String> {
        val root = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val texts = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.let { annotated ->
                texts.addAll(annotated.map { it.text })
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return texts
    }

    private fun liveRegionNode() =
        composeRule.onNodeWithTag("statusLiveRegion", useUnmergedTree = true).fetchSemanticsNode()

    private fun stateDescriptionNode() =
        composeRule.onNodeWithTag("statusState", useUnmergedTree = true).fetchSemanticsNode()

    private fun announcedText(node: SemanticsNode): String {
        val parts = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { parts.addAll(it) }
            n.config.getOrNull(SemanticsProperties.Text)?.let { texts ->
                parts.addAll(texts.map { it.text })
            }
            n.config.getOrNull(SemanticsProperties.StateDescription)?.let { parts.add(it) }
            n.children.forEach(::walk)
        }
        walk(node)
        return parts.joinToString(" ")
    }

    private fun sourceTileMatcher() = SemanticsMatcher("source tile") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("sourceTile-") == true
    }
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
