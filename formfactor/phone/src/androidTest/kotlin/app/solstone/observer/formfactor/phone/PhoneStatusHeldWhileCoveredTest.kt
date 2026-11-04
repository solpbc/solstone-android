// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneStatusHeldWhileCoveredTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var value by mutableStateOf("before")
    private var covered by mutableStateOf(false)

    private fun show() {
        composeRule.setContent {
            Text(heldWhileCovered(value, covered), Modifier.testTag("shown"))
        }
    }

    private fun assertShown(expected: String) {
        composeRule.onNodeWithTag("shown").assertTextEquals(expected)
    }

    // Falsified by the pill returning to the accessibility tree already changed: a live region speaks
    // only a change it can see, so the state after a covered change would never be announced.
    @Test
    fun aChangeWhileCoveredWaitsUntilAfterTheCoverIsGone() {
        show()
        composeRule.mainClock.autoAdvance = false
        covered = true
        composeRule.mainClock.advanceTimeByFrame()
        value = "after"
        composeRule.mainClock.advanceTimeBy(5_000)
        assertShown("before")

        covered = false
        composeRule.mainClock.advanceTimeByFrame()
        assertShown("before")

        composeRule.mainClock.advanceTimeBy(1_000)
        assertShown("after")
    }

    @Test
    fun aChangeWithNoCoverShowsAtOnce() {
        show()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeByFrame()
        value = "after"
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        assertShown("after")
    }
}
