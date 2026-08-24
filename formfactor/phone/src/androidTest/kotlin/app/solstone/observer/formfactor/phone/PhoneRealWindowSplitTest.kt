// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneRealWindowSplitTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun splitMatchesTheRealWindowWidthClass() {
        var expectedSplit = false
        composeRule.setContent {
            expectedSplit = classifyWindowWidth(
                currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
                    .windowSizeClass
                    .minWidthDp,
            ) != WidthClass.COMPACT
            PhoneObserverScreen(
                loadState = LoadState.Loaded(
                    SourcesReadModel(
                        observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
                        sources = emptyList(),
                    ),
                ),
                status = null,
                onToggle = { _, _: SourceWish -> },
                onStartObserving = {},
            )
        }
        composeRule.waitForIdle()

        if (expectedSplit) {
            composeRule.onNodeWithTag("phoneSplit").assertIsDisplayed()
        } else {
            composeRule.onNodeWithTag("phoneSplit").assertDoesNotExist()
        }
    }
}
