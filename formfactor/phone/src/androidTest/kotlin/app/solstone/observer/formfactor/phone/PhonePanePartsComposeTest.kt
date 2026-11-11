// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.solstone.core.model.SourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PhonePanePartsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aSwitchRowIsOneToggleStopThatCarriesItsLabel() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            PhoneTheme {
                PaneSwitchRow(label = "a source", checked = checked, onCheckedChange = { checked = it }, switchModifier = Modifier.testTag("row"))
            }
        }
        val switches = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .fetchSemanticsNodes()
        assertEquals("one switch stop, not a label stop plus an unlabelled switch", 1, switches.size)
        val text = switches.single().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
        assertEquals("a source", text)
        composeRule.onNodeWithTag("row").assertIsOff().performClick()
        composeRule.onNodeWithTag("row").assertIsOn()
    }

    @Test
    fun aNarrowFactRowPutsTheValueUnderTheLabelInsteadOfBreakingAWord() {
        composeRule.setContent {
            PhoneTheme {
                PaneFactRow(label = "checkedlabelword", value = "valuewordlong", modifier = Modifier.width(160.dp).testTag("fact"))
            }
        }
        val label = composeRule.onNodeWithTag("fact", useUnmergedTree = true).fetchSemanticsNode().children
        assertEquals(2, label.size)
        assertTrue("value sits below the label", label[1].boundsInRoot.top >= label[0].boundsInRoot.bottom)
    }

    @Test
    fun aCustomSourcePageLeadsWithTheVerdict() {
        composeRule.setContent { PhoneTheme { PhoneScreenshotsPane(verdict = SourceState.ON) } }
        composeRule.onNodeWithTag(VERDICT_TEST_TAG).fetchSemanticsNode()
    }
}
