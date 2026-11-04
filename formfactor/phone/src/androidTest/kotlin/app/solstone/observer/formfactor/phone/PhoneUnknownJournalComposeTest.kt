// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.pl.ClaimedJournalMark
import app.solstone.core.pl.UnknownJournalSighting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneUnknownJournalComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val answered = mark("afoot", "unfixed")
    private val own = mark("bramble", "quokka")

    private fun spokenBeside(tag: String): List<String> =
        composeRule.onNode(hasTestTag("journalMarkCard") and hasAnyAncestor(hasTestTag(tag)))
            .fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty()

    // Falsified by collapsing sightings into a summary, or by one row opening another's detail.
    @Test
    fun everySightingHasItsOwnRowOpeningItsOwnDetail() {
        val opened = mutableListOf<String?>()
        composeRule.setContent {
            Column {
                PhoneUnknownJournalRows(
                    listOf(
                        UnknownJournalSighting("10.0.0.1:7657", "a", ClaimedJournalMark.Derived(answered)),
                        UnknownJournalSighting("10.0.0.2:7657", null, ClaimedJournalMark.NotPresented),
                        UnknownJournalSighting(null, "a", ClaimedJournalMark.Derived(answered)),
                    ),
                    onOpen = { opened += it },
                )
            }
        }
        composeRule.onNodeWithTag("unknownJournalRow-10.0.0.2:7657").performClick()
        composeRule.onNodeWithTag("unknownJournalRow-relay").performClick()
        composeRule.onNodeWithTag("unknownJournalRow-10.0.0.1:7657").performClick()
        assertEquals(listOf("10.0.0.2:7657", null, "10.0.0.1:7657"), opened)
        composeRule.onAllNodesWithTag("unknownJournalPairAgain").fetchSemanticsNodes().also { assertEquals(0, it.size) }
    }

    @Test
    fun bothMarksAreNamedAndTheClaimIsCaptionedAsOne() {
        var pairAgain = 0
        composeRule.setContent {
            PhoneUnknownJournalPane(
                UnknownJournalSighting("10.0.0.1:7657", "a", ClaimedJournalMark.Derived(answered)),
                JournalMarkPresentation.Identified(own),
                onPairAgain = { pairAgain++ },
            )
        }
        val whatAnswered = spokenBeside("unknownJournalWhatAnswered")
        val yourJournal = spokenBeside("unknownJournalYourJournal")
        assertEquals(listOf(journalMarkSpokenForm(JournalMarkPresentation.Identified(answered))), whatAnswered)
        assertEquals(listOf(journalMarkSpokenForm(JournalMarkPresentation.Identified(own))), yourJournal)
        composeRule.onNodeWithTag("unknownJournalCaption", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("unknownJournalPairAgain").performClick()
        assertEquals(1, pairAgain)
    }

    // Falsified by the slot borrowing the mark's dashed "your journal" form or name for a peer that
    // is not ours.
    @Test
    fun aPeerWithNoIdentityGetsTheNeutralSlotAndItsOwnName() {
        composeRule.setContent {
            PhoneUnknownJournalPane(
                UnknownJournalSighting("10.0.0.1:7657", null, ClaimedJournalMark.NotPresented),
                JournalMarkPresentation.Identified(own),
                onPairAgain = {},
            )
        }
        assertEquals(listOf(JournalMarkTokens.NOT_PRESENTED_ACCESSIBLE_NAME), spokenBeside("unknownJournalWhatAnswered"))
        assertNotNull(unknownJournalCaption(ClaimedJournalMark.NotPresented))
    }

    @Test
    fun theDetailClosesItselfWhenItsSightingEnds() {
        var gone = 0
        composeRule.setContent {
            PhoneUnknownJournalRoute(
                route = PhoneRoute.UnknownJournal("10.0.0.1:7657"),
                binding = UnknownJournalsBinding(sightings = emptyList(), onGone = { gone++ }),
                yourJournal = JournalMarkPresentation.Identified(own),
                onPairAgain = {},
            )
        }
        composeRule.waitForIdle()
        assertEquals(1, gone)
        composeRule.onAllNodesWithTag("unknownJournalPane").fetchSemanticsNodes().also { assertEquals(0, it.size) }
    }

    private fun mark(first: String, second: String) = JournalMark(
        icon1 = JournalMarkIcon("piano", "<svg></svg>", "blue", "#3B82F6", 0),
        icon2 = JournalMarkIcon("key", "<svg></svg>", "purple", "#8B5CF6", 45),
        words = listOf(first, second),
    )
}
