// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun drawerPlacementScrimConsumesJournalPillTouchWhileShelfOpen() {
        var journalClicks = 0
        var openShelf: (() -> Unit)? = null
        composeRule.setContent {
            var shelfOpen by remember { mutableStateOf(false) }
            val drawerState = rememberDrawerState(
                initialValue = DrawerValue.Closed,
                confirmStateChange = { it != DrawerValue.Closed },
            )
            val scope = rememberCoroutineScope()
            openShelf = {
                shelfOpen = true
                scope.launch { drawerState.open() }
            }
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 500.dp)),
            ) {
                PhoneShell(
                    drawerState = drawerState,
                    shelfOpen = shelfOpen,
                    journalMark = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable { journalClicks += 1 },
                        )
                    },
                    drawerContent = { Text("sheet") },
                    content = { Text("destination", Modifier.testTag("placementDestination")) },
                )
            }
        }
        composeRule.waitForIdle()
        val journalCenter = composeRule.onNodeWithTag("journalPill").fetchSemanticsNode().boundsInRoot.center
        composeRule.runOnIdle { requireNotNull(openShelf).invoke() }
        composeRule.onNodeWithTag("phoneShelfSheet").assertIsDisplayed()
        val sheetBounds = composeRule.onNodeWithTag("phoneShelfSheet").fetchSemanticsNode().boundsInRoot
        assertTrue("journal pill must be outside the sheet", journalCenter.x > sheetBounds.right)
        // PLACEMENT pin: the scrim covers the pill before this press can reach
        // it. This does not prove inertness; it fails if the drawer is nested
        // inside the Scaffold instead of wrapping it.
        composeRule.onRoot().performTouchInput { click(journalCenter) }
        assertEquals(0, journalClicks)
        composeRule.onNodeWithTag("phoneShelfSheet").assertIsDisplayed()
        composeRule.onNodeWithTag("placementDestination").assertIsDisplayed()
    }
}
