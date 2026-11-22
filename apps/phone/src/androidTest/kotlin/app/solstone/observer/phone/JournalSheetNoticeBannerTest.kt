// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.observer.formfactor.phone.PhoneTheme
import app.solstone.observer.formfactor.phone.ThemeVariant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalSheetNoticeBannerTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noticeBannerIsOpaquePoliteLiveRegionAndDismissible() {
        var shown by mutableStateOf<String?>("file selection isn't available here. use the import tile or share.")
        composeRule.setContent {
            PhoneTheme(ThemeVariant.LIGHT_STANDARD) {
                shown?.let { message ->
                    JournalSheetNoticeBanner(
                        message = message,
                        onDismiss = { shown = null },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val node = composeRule.onNodeWithTag("journalSheetNotice", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        composeRule.onNodeWithText("file selection isn't available here. use the import tile or share.").assertIsDisplayed()
        composeRule.onNodeWithTag("journalSheetNotice-dismiss").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("journalSheetNotice").assertDoesNotExist()
    }
}
