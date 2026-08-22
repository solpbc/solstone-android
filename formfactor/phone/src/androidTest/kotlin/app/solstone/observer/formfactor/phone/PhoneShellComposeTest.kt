// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun minWidthDpIsDisplayed() {
        composeRule.setContent { PhoneShell() }
        composeRule.onNodeWithTag("minWidthDp").assertIsDisplayed()
    }
}
