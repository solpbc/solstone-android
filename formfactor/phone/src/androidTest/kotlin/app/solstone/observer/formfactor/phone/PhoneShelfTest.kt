// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShelfTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sheetOwnsThreeHundredSixtyDpWidthForNarrowContent() {
        composeRule.setContent {
            PhoneTheme {
                PhoneShelfSheet(drawerState = rememberDrawerState(DrawerValue.Closed)) {
                    Text(text = "x", modifier = Modifier.testTag("narrowShelfContent"))
                }
            }
        }
        composeRule.waitForIdle()
        val sheet = composeRule.onNodeWithTag("phoneShelfSheet").fetchSemanticsNode()
        val narrow = composeRule.onNodeWithTag("narrowShelfContent").fetchSemanticsNode()
        val expected = with(composeRule.density) { 360.dp.toPx() }
        val tolerance = with(composeRule.density) { 1.dp.toPx() }
        assertTrue("sheet width ${sheet.size.width} differs from $expected", abs(sheet.size.width - expected) <= tolerance)
        assertTrue(narrow.size.width < sheet.size.width - tolerance)
    }

    @Test
    fun rowsMergeDescendantsAndKeepTextAtContentInset() {
        composeRule.setContent {
            PhoneTheme {
                PhoneShelfSheet(drawerState = rememberDrawerState(DrawerValue.Closed)) {
                    PhoneShelfContent(
                        shelfOpen = false,
                        onNavigate = {},
                        version = "version-sentinel",
                        firstRowFocusRequester = remember { FocusRequester() },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertTextEquals("your journal")
        composeRule.onNodeWithTag("shelfHeading").assertTextEquals("settings")
        composeRule.onNodeWithTag("shelfPrivacy").assertTextEquals("privacy")
        // `terms` is deliberately absent: the app owes no terms-of-service document
        // (CLO 2026-09-03), so the footer no longer names one. `licenses` took the
        // slot and, unlike either word before it, is a real control.
        composeRule.onNodeWithTag("shelfLicenses").assertTextEquals("licenses")
        composeRule.onNodeWithTag("shelfVersion").assertTextEquals("version-sentinel")
        val sheetLeft = composeRule.onNodeWithTag("phoneShelfSheet").fetchSemanticsNode().boundsInRoot.left
        val textLeft = composeRule
            .onNodeWithTag("shelfRowText-yourJournal", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        val expected = with(composeRule.density) { 24.dp.toPx() }
        val tolerance = with(composeRule.density) { 1.dp.toPx() }
        assertTrue("text inset ${textLeft - sheetLeft} differs from $expected", abs(textLeft - sheetLeft - expected) <= tolerance)
    }
}
