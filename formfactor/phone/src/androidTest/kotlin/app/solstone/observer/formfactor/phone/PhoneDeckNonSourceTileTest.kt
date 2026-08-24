// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.semantics.SemanticsNode
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

        composeRule.onNode(paneTitleMatcher(PhoneRoute.Import.paneTitle)).assertIsDisplayed()
    }

    @Test
    fun addMoreTileOpensAddMorePane() {
        setScreen()

        composeRule.onNodeWithTag("addMoreTile").assertIsDisplayed().performClick()

        composeRule.onNode(paneTitleMatcher(PhoneRoute.AddMore.paneTitle)).assertIsDisplayed()
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

        assertTileTexts("importTile", listOf("import", "photos, files, anything"))
        assertTileTexts("addMoreTile", listOf("add more", "sources and devices"))
    }

    @Test
    fun importPaneShowsApprovedRows() {
        setScreen(initial = PhoneRoute.Import)

        assertTileTexts("importRow-photos", listOf("photos", "pick from your library"))
        assertTileTexts("importRow-files", listOf("files", "documents, audio, PDFs"))
        assertTileTexts("importRow-recentlyImported", listOf("recently imported"))
    }

    @Test
    fun importPaneShowsJournalReceipt() {
        setScreen(initial = PhoneRoute.Import)

        composeRule.onNodeWithText("in your journal").assertIsDisplayed()
    }

    @Test
    fun addMoreRowOpensSourceDetail() {
        setScreen(initial = PhoneRoute.AddMore)

        composeRule.onNodeWithTag("addMoreRow-audio").assertIsDisplayed().performClick()

        composeRule.onNode(paneTitleMatcher(PhoneRoute.SourceDetail("audio").paneTitle))
            .assertIsDisplayed()
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
                initial = initial?.let { PhoneRouteStack.Empty.showInDetail(it) }
                    ?: PhoneRouteStack.Empty,
            )
        }
        composeRule.waitForIdle()
    }

    private fun assertSingleAppBarHeading(expected: String) {
        val headingMatcher = SemanticsMatcher("heading") { node ->
            node.config.getOrNull(SemanticsProperties.Heading) != null
        }
        val headings = composeRule.onAllNodes(headingMatcher, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, headings.size)
        composeRule.onNode(headingMatcher, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(listOf(expected), headings.single().texts())
        val appBar = composeRule.onNodeWithTag("phoneAppBar", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(appBar.hasDescendant(headings.single().id))
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

private fun SemanticsNode.hasDescendant(id: Int): Boolean =
    children.any { it.id == id || it.hasDescendant(id) }

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
