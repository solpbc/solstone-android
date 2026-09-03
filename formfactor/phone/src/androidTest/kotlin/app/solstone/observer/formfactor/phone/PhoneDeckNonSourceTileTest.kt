// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
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
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneDeckNonSourceTileTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun importTileOpensImportPane() {
        setScreen()

        composeRule.onNodeWithTag("importTile").assertIsDisplayed().performClick()

        composeRule.onNode(paneTitleMatcher(spokenPaneTitle(PhoneRoute.Import))).assertIsDisplayed()
    }

    @Test
    fun addMoreTileOpensAddMorePane() {
        setScreen()

        composeRule.onNodeWithTag("addMoreTile").assertIsDisplayed().performClick()

        composeRule.onNode(paneTitleMatcher(spokenPaneTitle(PhoneRoute.AddMore))).assertIsDisplayed()
    }

    @Test
    fun importPaneHasOneApprovedAppBarHeading() {
        setScreen(initial = PhoneRoute.Import)

        assertSingleAppBarHeading("import")
    }

    @Test
    fun addMorePaneHasOneApprovedAppBarHeading() {
        setScreen(initial = PhoneRoute.AddMore)

        assertSingleAppBarHeading("add more")
    }

    @Test
    fun backAtImportDepthOneShowsDeck() {
        setScreen(initial = PhoneRoute.Import)

        Espresso.pressBack()

        composeRule.onNodeWithTag("deck").assertIsDisplayed()
    }

    @Test
    fun backAtAddMoreDepthOneShowsDeck() {
        setScreen(initial = PhoneRoute.AddMore)

        Espresso.pressBack()

        composeRule.onNodeWithTag("deck").assertIsDisplayed()
    }

    @Test
    fun upAtImportDepthOneShowsDeck() {
        setScreen(initial = PhoneRoute.Import)

        composeRule.onNodeWithTag("phoneUp").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("deck").assertIsDisplayed()
    }

    @Test
    fun upAtAddMoreDepthOneShowsDeck() {
        setScreen(initial = PhoneRoute.AddMore)

        composeRule.onNodeWithTag("phoneUp").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("deck").assertIsDisplayed()
    }

    @Test
    fun nonSourceTilesHaveNoSourceChrome() {
        setScreen()

        assertNoSourceChrome("importTile")
        assertNoSourceChrome("addMoreTile")
    }

    @Test
    fun nonSourceTileSublinesMatchApprovedCopy() {
        setScreen()

        assertTileTexts("importTile", listOf("import", "photos and files"))
        // `sources`, not `sources and devices`: the product has no device category on
        // either platform, and the word survived the deletion of the pane grouping it
        // named. See PhoneDeck.kt and mobile-shell.md § 5.
        assertTileTexts("addMoreTile", listOf("add more", "sources"))
    }

    @Test
    fun importPaneShowsApprovedRows() {
        setScreen(initial = PhoneRoute.Import)

        assertTileTexts("importRow-photos", listOf("photos", "not available"))
        assertTileTexts("importRow-files", listOf("files", "not available"))
        assertTileTexts("importRow-recentlyImported", listOf("recently imported", "nothing to show"))
    }

    @Test
    fun importPaneRowsAreInformationalRatherThanActions() {
        setScreen(initial = PhoneRoute.Import)

        listOf("importRow-photos", "importRow-files", "importRow-recentlyImported").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            assertFalse("$tag is actionable", node.config.getOrNull(SemanticsActions.OnClick) != null)
        }
    }

    @Test
    fun unavailableImportPaneDoesNotClaimJournalReceipt() {
        setScreen(initial = PhoneRoute.Import)

        composeRule.onNodeWithText("in your journal", substring = true).assertDoesNotExist()
    }

    @Test
    fun addMoreCameraOpensDetailWithoutTemplateWhenCameraIsUnregistered() {
        setScreen(initial = PhoneRoute.AddMore)

        composeRule.onNodeWithTag("addMoreRow-camera").assertIsDisplayed().performClick()

        composeRule.onNode(paneTitleMatcher(spokenPaneTitle(PhoneRoute.SourceDetail("camera"))))
            .assertIsDisplayed()
        composeRule.onNodeWithText("give this a tile on home").assertIsDisplayed()
        composeRule.onNodeWithTag(VERDICT_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(REASON_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(ACTION_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(FACTS_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun sourceCollectionInfoIncludesLeadingTiles() {
        setScreen(loadState = loaded(audioOn()))

        composeRule.onNodeWithTag("sourceGrid", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("sourceTile-audio", useUnmergedTree = true).assertIsDisplayed()
        val grid = composeRule.onNodeWithTag("sourceGrid", useUnmergedTree = true).fetchSemanticsNode()
        val childCount = grid.children.count { node ->
            node.config.getOrNull(SemanticsProperties.TestTag).isGridItemTag()
        }
        assertEquals(3, childCount)
        val source = composeRule.onNodeWithTag("sourceTile-audio", useUnmergedTree = true)
            .fetchSemanticsNode()
        val itemInfo = source.config.getOrNull(SemanticsProperties.CollectionItemInfo)
        assertNotNull(itemInfo)
        val announced = requireNotNull(itemInfo)
        assertEquals("source row index", 2, announced.rowIndex)
        assertEquals("source row span", childCount, announced.rowSpan)
        assertEquals("source column index", 0, announced.columnIndex)
        assertEquals("source column span", 1, announced.columnSpan)
    }

    @Test
    fun nonSourceTilesRemainDisplayedWhileLoading() {
        setScreen(loadState = LoadState.Loading)

        composeRule.onNodeWithTag("importTile").assertIsDisplayed()
        composeRule.onNodeWithTag("addMoreTile").assertIsDisplayed()
        composeRule.onNodeWithTag("sourcesLoading").assertIsDisplayed()
    }

    @Test
    fun nonSourceTilesRemainDisplayedAfterFailure() {
        setScreen(loadState = LoadState.Failed(IllegalStateException("boom")))

        composeRule.onNodeWithTag("importTile").assertIsDisplayed()
        composeRule.onNodeWithTag("addMoreTile").assertIsDisplayed()
        composeRule.onNodeWithTag("sourcesFailed").assertIsDisplayed()
    }

    private fun setScreen(
        loadState: LoadState<SourcesReadModel> = loaded(audioOn()),
        initial: PhoneRoute? = null,
    ) {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loadState,
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                initial = initial?.let { PhoneRouteStack.Empty.showInDetail(it) }
                    ?: PhoneRouteStack.Empty,
            )
        }
        composeRule.waitForIdle()
    }

    private fun assertSingleAppBarHeading(expected: String) {
        composeRule.onNodeWithTag("phoneAppBar", useUnmergedTree = true).assertIsDisplayed()
        val appBar = composeRule.onNodeWithTag("phoneAppBar", useUnmergedTree = true)
            .fetchSemanticsNode()
        val headings = appBar.descendants().filter { node ->
            node.config.getOrNull(SemanticsProperties.Heading) != null
        }
        assertEquals(1, headings.size)
        assertEquals(listOf(expected), headings.single().texts())
    }

    private fun assertNoSourceChrome(testTag: String) {
        composeRule.onNodeWithTag(testTag, useUnmergedTree = true).assertIsDisplayed()
        val tile = composeRule.onNodeWithTag(testTag, useUnmergedTree = true).fetchSemanticsNode()
        val nodes = tile.descendants()
        val texts = nodes.flatMap(SemanticsNode::texts)
        listOf("off", "setting up", "on", "paused", "needs attention").forEach { state ->
            assertFalse("$testTag contains state $state", texts.contains(state))
        }
        val tags = nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        assertFalse(tags.any { it.startsWith("sourceSwitch-") })
        assertFalse("tileDot" in tags)
        assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.StateDescription) != null })
    }

    private fun assertTileTexts(testTag: String, expected: List<String>) {
        composeRule.onNodeWithTag(testTag, useUnmergedTree = true).assertIsDisplayed()
        val tile = composeRule.onNodeWithTag(testTag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(expected, tile.descendants().flatMap(SemanticsNode::texts))
    }

    private fun paneTitleMatcher(title: String) = SemanticsMatcher("pane $title") { node ->
        node.config.getOrNull(SemanticsProperties.PaneTitle) == title
    }
}

private fun String?.isGridItemTag(): Boolean =
    this == "importTile" || this == "addMoreTile" || this?.startsWith("sourceTile-") == true

private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap {
    it.descendants()
}

private fun SemanticsNode.texts(): List<String> =
    config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()

private fun audioOn() = SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE)

private fun loaded(vararg sources: SourceStatus) = LoadState.Loaded(
    SourcesReadModel(
        observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
        sources = sources.toList(),
    ),
)

private fun connected() = PhoneStatusModel(
    paired = true,
    online = true,
    pendingCount = 0,
    hasContentPending = false,
)
