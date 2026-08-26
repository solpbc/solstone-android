// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellDetailRuntimeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun backFromSourceDetailReturnsToDeck() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            openAudioDetail()
            Espresso.pressBack()
            assertDeckWithoutSourceDetail()
        }
    }

    @Test
    fun upFromSourceDetailReturnsToDeck() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            openAudioDetail()
            composeRule.onNodeWithTag("phoneUp").performClick()
            assertDeckWithoutSourceDetail()
        }
    }

    private fun assertDeckWithoutSourceDetail() {
        composeRule.onNodeWithTag("deck").assertIsDisplayed()
        composeRule.onNodeWithTag("sourceDetailHomeTile", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun openAudioDetail() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("sourceTile-audio").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sourceBody-audio", useUnmergedTree = true).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(sourceDetailPaneMatcher(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNode(sourceDetailPaneMatcher(), useUnmergedTree = true).assertExists()
        composeRule.onNode(audioHeadingMatcher()).assertIsDisplayed()
    }

    private fun sourceDetailPaneMatcher() = SemanticsMatcher("audio source detail pane") { node ->
        node.config.getOrNull(SemanticsProperties.PaneTitle) == "audio" &&
            node.hasDescendantWithTag("sourceDetailHomeTile")
    }

    private fun audioHeadingMatcher() = SemanticsMatcher("audio heading") { node ->
        node.config.contains(SemanticsProperties.Heading) &&
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "audio" } == true
    }
}

private fun SemanticsNode.hasDescendantWithTag(testTag: String): Boolean =
    children.any { child ->
        child.config.getOrNull(SemanticsProperties.TestTag) == testTag ||
            child.hasDescendantWithTag(testTag)
    }
