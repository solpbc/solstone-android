// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneNavigationRestorationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun routeStackAndPaneFlagsSurviveSavedInstanceStateRestore() {
        val tester = StateRestorationTester(composeRule)
        var stackState: MutableState<PhoneRouteStack>? = null
        var paneState: MutableState<PaneStates>? = null
        tester.setContent {
            stackState = rememberPhoneRouteStack()
            paneState = rememberPaneStates()
        }
        composeRule.runOnIdle {
            assertEquals(PhoneRouteStack.Empty, stackState!!.value)
            assertEquals(PaneStates.Empty, paneState!!.value)
            stackState!!.value = PhoneRouteStack.Empty
                .showInDetail(PhoneRoute.RouteA)
                .pushInDetail(PhoneRoute.RouteCChild)
            paneState!!.value = PaneStates.Empty.open(PhonePane.SHELF)
        }
        tester.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            assertEquals(
                listOf(PhoneRoute.RouteA, PhoneRoute.RouteCChild),
                stackState!!.value.toList(),
            )
            assertEquals(2, stackState!!.value.depth)
            assertTrue(paneState!!.value.isOpen(PhonePane.SHELF))
            assertFalse(paneState!!.value.isOpen(PhonePane.JOURNAL))
            assertFalse(paneState!!.value.isOpen(PhonePane.STATUS))
        }
    }
}
