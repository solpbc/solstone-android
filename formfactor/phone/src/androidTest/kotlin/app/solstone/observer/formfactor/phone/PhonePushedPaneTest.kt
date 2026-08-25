// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourcesReadModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonePushedPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun backAtDepthOneShowsDeck() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                initial = PhoneRouteStack.Empty.showInDetail(PhoneRoute.AboutSolstone),
            )
        }
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.onNodeWithTag("deck").assertIsDisplayed()
    }

    @Test
    fun backAtDepthTwoShowsParentNotDeck() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                initial = PhoneRouteStack.Empty
                    .showInDetail(PhoneRoute.AboutSolstone)
                    .pushInDetail(PhoneRoute.Licences),
            )
        }
        composeRule.waitForIdle()
        Espresso.pressBack()
        composeRule.onNodeWithText("about solstone").assertIsDisplayed()
        composeRule.onNodeWithTag("deck").assertDoesNotExist()
    }

    @Test
    fun upAtDepthOneShowsDeck() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                initial = PhoneRouteStack.Empty.showInDetail(PhoneRoute.AboutSolstone),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phoneUp").performClick()
        composeRule.onNodeWithTag("deck").assertIsDisplayed()
    }

    @Test
    fun upAtDepthTwoShowsParent() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                initial = PhoneRouteStack.Empty
                    .showInDetail(PhoneRoute.AboutSolstone)
                    .pushInDetail(PhoneRoute.Licences),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phoneUp").performClick()
        composeRule.onNodeWithText("about solstone").assertIsDisplayed()
        composeRule.onNodeWithTag("deck").assertDoesNotExist()
    }

    @Test
    fun deckHasNoUpAffordance() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phoneUp").assertDoesNotExist()
    }

    @Test
    fun pushedPaneShowsUpAffordance() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                initial = PhoneRouteStack.Empty.showInDetail(PhoneRoute.AboutSolstone),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("phoneUp").assertIsDisplayed()
    }

    @Test
    fun licencesTapIdentifiedByPaneTitle() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                initial = PhoneRouteStack.Empty.showInDetail(PhoneRoute.AboutSolstone),
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("licencesRow").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(
            SemanticsMatcher("licences pane") { node ->
                node.config.getOrNull(SemanticsProperties.PaneTitle) ==
                    spokenPaneTitle(PhoneRoute.Licences)
            },
        ).assertIsDisplayed()
    }
}

private fun loaded() = LoadState.Loaded(
    SourcesReadModel(
        observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
        sources = emptyList(),
    ),
)

private fun connected() = PhoneStatusModel(
    paired = true,
    online = true,
    pendingCount = 0,
    hasContentPending = false,
)
