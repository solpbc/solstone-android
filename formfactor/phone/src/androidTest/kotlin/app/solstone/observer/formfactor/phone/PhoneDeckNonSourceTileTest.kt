// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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
    fun importPanePickersAreActionsAndTheRecentLineIsNot() {
        setScreen(initial = PhoneRoute.Import)

        listOf("importRow-files", "importRow-screenshots").forEach { tag ->
            val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("$tag is not actionable", node.config.getOrNull(SemanticsActions.OnClick) != null)
        }
        val recent = composeRule.onNodeWithTag("importRow-recent", useUnmergedTree = true).fetchSemanticsNode()
        assertFalse("the recent line is actionable", recent.config.getOrNull(SemanticsActions.OnClick) != null)
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

    /**
     * ⚠ Sources come FIRST on the deck, so a source announces at index 0 and the two
     * destination tiles follow it. `mobile-shell.md` § 2.1 reads "one tile per source the owner
     * has, PLUS `import` and `add more`", and the shipped iOS deck is in that order; Android had
     * been leading with the two destinations. The count an item announces still covers all three,
     * because the collection a screen reader walks is the whole grid.
     */
    @Test
    fun sourceCollectionInfoCountsEveryTileAndSourcesComeFirst() {
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
        assertEquals("source row index", 0, announced.rowIndex)
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
    fun aFaultStaysOnItsTileAndImportIsSolid() {
        setScreen(
            loadState = loaded(
                SourceStatus("audio", SourceWish.On, SourceState.NEEDS_ATTENTION, ReasonCode.NONE),
            ),
        )

        composeRule.onNodeWithText("needs attention", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("1 needs attention").assertDoesNotExist()
        composeRule.onNodeWithText("need attention").assertDoesNotExist()
        composeRule.onNodeWithTag("importTile").assertIsDisplayed()
        composeRule.onNodeWithTag("addMoreTile").assertIsDisplayed()
        // These zero-size markers identify the border drawn by each visible tile.
        composeRule.onNodeWithTag("importTile-solid", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("addMoreTile-dashed", useUnmergedTree = true).assertExists()
    }

    @Test
    fun addMoreRowShowsTheSourceGlyphState() {
        setScreen(
            loadState = loaded(
                SourceStatus("audio", SourceWish.On, SourceState.NEEDS_ATTENTION, ReasonCode.NONE),
            ),
            initial = PhoneRoute.AddMore,
        )

        composeRule.onNodeWithTag("addMoreRow-audio").assertIsDisplayed()
        composeRule.onNodeWithText("needs attention").assertIsDisplayed()
    }

    @Test
    fun nonSourceTilesRemainDisplayedAfterFailure() {
        setScreen(loadState = LoadState.Failed(IllegalStateException("boom")))

        composeRule.onNodeWithTag("importTile").assertIsDisplayed()
        composeRule.onNodeWithTag("addMoreTile").assertIsDisplayed()
        composeRule.onNodeWithTag("sourcesFailed").assertIsDisplayed()
    }

    @Test
    fun importTileShowsWorstSignalWinsWithButtonRoleAndStateDescription() {
        val reviewState = PhoneImportPaneState(
            rows = listOf(
                PhoneImportRow("r1", "export.zip", ImportRowKind.READY_TO_REVIEW, savedAtJournal = true, importId = "20261122_120000", reviewKind = "body_confirmation_required"),
                PhoneImportRow("w1", "notes.pdf", ImportRowKind.WAITING),
            ),
            journalOpenable = true,
        )
        setScreen(imports = reviewState)
        val reviewNode = composeRule.onNodeWithTag("importTile", useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(Role.Button, reviewNode.config.getOrNull(SemanticsProperties.Role))
        assertEquals("ready to review. export.zip", reviewNode.config.getOrNull(SemanticsProperties.StateDescription))
        assertTileTexts("importTile", listOf("import", "ready to review", "export.zip"))
        composeRule.onNodeWithTag("importMarker-ready_to_review", useUnmergedTree = true).assertExists()

        // needs attention beats ready to review.
        val attentionSummary = importTileActiveSummary(
            reviewState.copy(
                rows = reviewState.rows + PhoneImportRow("a1", "broken.zip", ImportRowKind.REFUSED, error = "bad archive"),
            ),
        )
        assertNotNull(attentionSummary)
        assertEquals(ImportMarkerKind.NEEDS_ATTENTION, attentionSummary!!.marker)
        assertEquals("needs attention", attentionSummary.stateWord)
        assertEquals("broken.zip", attentionSummary.subLine)
    }

    @Test
    fun importPaneReviewExpiredRecentRemoveAndLargeFontRenderCleanly() {
        var reviewedId: String? = null
        var sentAgainId: String? = null
        var openedRecentId: String? = null
        var removedId: String? = null
        val now = 1_763_838_000_000L
        val state = PhoneImportPaneState(
            rows = listOf(
                PhoneImportRow(
                    id = "rev1",
                    name = "very-long-apple-health-export-archive-2026-11-22.zip",
                    kind = ImportRowKind.READY_TO_REVIEW,
                    savedAtJournal = true,
                    importId = "20261122_120000",
                    reviewKind = "body_confirmation_required",
                ),
                PhoneImportRow(
                    id = "exp1",
                    name = "old-health.zip",
                    kind = ImportRowKind.REVIEW_EXPIRED,
                    importId = "20261122_100000",
                    reviewKind = "body_confirmation_required",
                ),
                PhoneImportRow(
                    id = "att1",
                    name = "corrupt.zip",
                    kind = ImportRowKind.REFUSED,
                    error = "couldn't read archive",
                    retryable = false,
                ),
            ),
            recentReceipts = listOf(
                PhoneImportRecentReceipt("rcpt1", "field-notes.pdf", "20261122_115000", now - 10 * 60_000L),
                PhoneImportRecentReceipt("rcpt2", "already-there.jpg", null, now - 30 * 60_000L),
            ),
            journalOpenable = true,
        )
        val actions = PhoneImportActions(
            onReviewInJournal = { reviewedId = it },
            onSendAgain = { sentAgainId = it },
            onOpenRecentImport = { openedRecentId = it },
            onRemove = { removedId = it },
        )
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalPhoneStatusNow provides PhoneStatusNow { now },
                LocalDensity provides Density(baseDensity.density, fontScale = 2.0f),
            ) {
                PhoneTheme(ThemeVariant.LIGHT_STANDARD) {
                    PhoneImportPane(state = state, actions = actions)
                }
            }
        }
        composeRule.waitForIdle()

        // Review row has no try now button; tapping review opens the journal import ID.
        composeRule.onNodeWithTag("importItem-rev1-retry").assertDoesNotExist()
        composeRule.onNodeWithTag("importItem-rev1-review").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("20261122_120000", reviewedId)

        // Expired row has send again to review.
        composeRule.onNodeWithTag("importItem-exp1-sendAgain").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("exp1", sentAgainId)

        // Remove on READY_TO_REVIEW shows the updated waiting-copy dialog text.
        composeRule.onNodeWithTag("importItem-rev1-remove").performScrollTo().performClick()
        composeRule.onNodeWithText(
            "this removes the copy on this device. the waiting copy in your journal stays there until you discard it or it expires.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("importRemove-confirm").performClick()
        assertEquals("rev1", removedId)

        // Recent receipt with import_id is clickable; receipt without import_id renders calmly non-clickable.
        composeRule.onNodeWithTag("importRecent-rcpt1").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("20261122_115000", openedRecentId)
        val nonClickableRecent = composeRule.onNodeWithTag("importRecent-rcpt2", useUnmergedTree = true).fetchSemanticsNode()
        assertFalse(nonClickableRecent.config.getOrNull(SemanticsActions.OnClick) != null)
        composeRule.onNodeWithTag("importRow-allHistory").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun importPaneResumableProgressPausedAndCancelRenderCleanly() {
        val cancelled = mutableListOf<String>()
        val state = PhoneImportPaneState(
            rows = listOf(
                PhoneImportRow(
                    id = "send1",
                    name = "takeout-4gb.zip",
                    kind = ImportRowKind.SENDING,
                    bytesSent = 1_200_000_000L,
                    bytesTotal = 4_000_000_000L,
                    resumable = true,
                    uploadPhase = "sending",
                ),
                PhoneImportRow(
                    id = "pause1",
                    name = "photos-archive.zip",
                    kind = ImportRowKind.WAITING,
                    bytesSent = 500_000_000L,
                    bytesTotal = 2_000_000_000L,
                    resumable = true,
                    uploadPhase = "paused",
                ),
            ),
            journalOpenable = true,
        )
        val actions = PhoneImportActions(onCancel = { cancelled += it })
        composeRule.setContent {
            PhoneTheme(ThemeVariant.LIGHT_STANDARD) {
                PhoneImportPane(state = state, actions = actions)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("importItem-send1-progress").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("importItem-send1-cancel").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("importItem-pause1-progress").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("importItem-pause1-cancel").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("send1", "pause1"), cancelled)
    }

    private fun setScreen(
        loadState: LoadState<SourcesReadModel> = loaded(audioOn()),
        initial: PhoneRoute? = null,
        imports: PhoneImportPaneState = PhoneImportPaneState(),
        status: PhoneStatusModel = connected(),
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalPhoneImport provides PhoneImportBinding(state = imports)) {
                PhoneObserverScreen(
                    loadState = loadState,
                    status = status,
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                    onConfirmMark = {},
                    initial = initial?.let { PhoneRouteStack.Empty.showInDetail(it) }
                        ?: PhoneRouteStack.Empty,
                )
            }
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
    awaitingMarkConfirmation = false,
    recoveryCompleted = true,
    audioAwaitingCustody = false,
)
