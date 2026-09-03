// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneObserverScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun greetingSlotRendersGoodMorningAtHourFive() {
        composeRule.setContent {
            PhoneDeck(
                loadState = loaded(audioOn()),
                contentPadding = PaddingValues(0.dp),
                gridState = rememberLazyGridState(),
                widthClass = WidthClass.COMPACT,
                paneOpen = false,
                onOpenSource = {},
                onToggle = { _, _ -> },
                onOpenImport = {},
                onOpenAddMore = {},
                hour = 5,
            )
        }
        assertEquals(1, composeRule.onAllNodesWithTag("greetingSlot").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("good morning").assertIsDisplayed()
    }

    @Test
    fun deckPaneTitleMatchesTheGreetingItRendersInEveryBand() {
        var hour by mutableStateOf(5)
        composeRule.setContent {
            PhoneDeck(
                loadState = loaded(audioOn()),
                contentPadding = PaddingValues(0.dp),
                gridState = rememberLazyGridState(),
                widthClass = WidthClass.COMPACT,
                paneOpen = false,
                onOpenSource = {},
                onToggle = { _, _ -> },
                onOpenImport = {},
                onOpenAddMore = {},
                hour = hour,
            )
        }

        listOf(5, 12, 17).forEach { expectedHour ->
            composeRule.runOnIdle { hour = expectedHour }
            val greeting = greetingFor(expectedHour)
            composeRule.onNodeWithText(greeting).assertIsDisplayed()
            composeRule.onNode(paneTitleMatcher(greeting)).assertIsDisplayed()
        }
    }

    @Test
    fun audioAndCameraHaveSwitches() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn(), cameraOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("sourceSwitch-audio", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("sourceSwitch-camera", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun syntheticWatchIdHasNoSwitch() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(status("watch", SourceState.OFF, SourceWish.Off)),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("sourceSwitch-watch", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("sourceTile-watch").assertIsDisplayed()
    }

    @Test
    fun bodyTapDoesNotToggleSwitch() {
        var toggles = 0
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> toggles += 1 },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("sourceTile-audio").performTouchInput { click(center) }
        composeRule.onNode(paneTitleMatcher("audio")).assertIsDisplayed()
        assertEquals(0, toggles)
    }

    @Test
    fun pillConnectedCopy() {
        composeRule.setContent { PhoneStatusPill(model = connected(), onClick = {}) }
        composeRule.onNodeWithText("connected").assertIsDisplayed()
    }

    @Test
    fun pillSyncingCopy() {
        composeRule.setContent {
            PhoneStatusPill(model = PhoneStatusModel(true, true, 4, true), onClick = {})
        }
        composeRule.onNodeWithText("4 syncing").assertIsDisplayed()
    }

    @Test
    fun pillOfflineCopy() {
        composeRule.setContent {
            PhoneStatusPill(model = PhoneStatusModel(true, false, 2, true), onClick = {})
        }
        composeRule.onNodeWithText("offline · 2 waiting").assertIsDisplayed()
    }

    @Test
    fun pillNotPairedCopy() {
        composeRule.setContent {
            PhoneStatusPill(model = PhoneStatusModel(false, true, 0, false), onClick = {})
        }
        composeRule.onNodeWithText("not paired").assertIsDisplayed()
    }

    @Test
    fun statusPaneOpensAndBackReachesLadder() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = PhoneStatusModel(true, true, 1, true),
                waiting = listOf(audioOn()),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("statusPill").performClick()
        composeRule.onNodeWithTag("statusPane").assertIsDisplayed()
        composeRule.onNodeWithTag("statusPaneHeading").assertIsDisplayed()
        composeRule.onNodeWithTag("statusPaneHeading").assertTextEquals("status")
        Espresso.pressBack()
        composeRule.onNodeWithTag("statusPane").assertDoesNotExist()
    }

    @Test
    fun deckSemanticsHiddenWhilePaneOpen() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("statusPill").performClick()
        composeRule.onNodeWithTag("statusPane").assertIsDisplayed()
        composeRule.onNodeWithText("audio", useUnmergedTree = false).assertDoesNotExist()
    }

    @Test
    fun statusPaneKeepsShellControlsReachable() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("statusPill").performClick()
        composeRule.onNodeWithTag("statusPane").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneAppBar").assertIsDisplayed()
        composeRule.onNodeWithTag("journalPill").assertIsDisplayed()
    }

    @Test
    fun shelfMakesOnlyShellControlsAndDeckInert() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                version = "version-sentinel",
            )
        }
        composeRule.onNodeWithTag("phoneAppBar").assertIsDisplayed()
        composeRule.onNodeWithTag("sourceTile-audio").assertIsDisplayed()
        composeRule.onNodeWithTag("journalPill").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
        composeRule.onNodeWithTag("shelfPrivacy").assertTextEquals("privacy")
        composeRule.onNodeWithTag("shelfTerms").assertTextEquals("terms")
        composeRule.onNodeWithTag("shelfVersion").assertTextEquals("version-sentinel")
        composeRule.onNodeWithTag("phoneAppBar").assertDoesNotExist()
        composeRule.onNodeWithTag("sourceTile-audio").assertDoesNotExist()
        composeRule.onNodeWithTag("journalPill").assertDoesNotExist()
    }

    @Test
    fun drawerDismissActionClosesAndShelfReopens() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
        waitForFocus("shelfRow-yourJournal")
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss)
        composeRule.onNodeWithTag("phoneShelfOpener").assertIsDisplayed()
        // Input focus does not return to the opener when ModalNavigationDrawer
        // closes; both Back and dismiss leave it reset after a five-second wait.
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
    }

    @Test
    fun drawerBackClosesAndShelfReopens() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.onNodeWithTag("phoneShelfOpener").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
    }

    @Test
    fun drawerBackClosesAndShelfReopensWithFocusReturnLimitation() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
        waitForFocus("shelfRow-yourJournal")
        Espresso.pressBack()
        composeRule.onNodeWithTag("phoneShelfOpener").assertIsDisplayed()
        // Input focus does not return to the opener when ModalNavigationDrawer
        // closes; both Back and dismiss leave it reset after a five-second wait.
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
    }

    @Test
    fun drawerSwipeClosesAndShelfReopens() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("phoneShelfSheet").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("phoneShelfOpener").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
    }

    @Test
    fun drawerScrimClosesAndShelfReopens() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(
                    DpSize(500.dp, 800.dp),
                ),
            ) {
                PhoneObserverScreen(
                    loadState = loaded(audioOn()),
                    status = connected(),
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                )
            }
        }
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val sheet = composeRule.onNodeWithTag("phoneShelfSheet").fetchSemanticsNode().boundsInRoot
        composeRule.onRoot().performTouchInput {
            click(Offset((sheet.right + root.right) / 2f, root.center.y))
        }
        composeRule.onNodeWithTag("phoneShelfOpener").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
    }

    @Test
    fun shelfCanOpenOverTwoDetailRoutesAndReturnToDetailBack() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                // initialShelfOpen starts this fixture open; production reaches
                // the same state by dragging the gestures-enabled drawer over
                // this detail stack.
                initial = PhoneRouteStack.Empty
                    .showInDetail(PhoneRoute.ThisDevice)
                    .pushInDetail(PhoneRoute.Help),
                initialShelfOpen = true,
            )
        }
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.onNodeWithTag("phoneShelfSheet").assertIsNotDisplayed()
        composeRule.onNode(paneTitleMatcher(spokenPaneTitle(PhoneRoute.Help))).assertIsDisplayed()
        composeRule.onNodeWithTag("phoneUp").assertIsDisplayed()
        Espresso.pressBack()
        composeRule.onNode(paneTitleMatcher(spokenPaneTitle(PhoneRoute.ThisDevice))).assertIsDisplayed()
    }

    @Test
    fun restoredOpenShelfKeepsTheShellInert() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("phoneShelfOpener").performClick()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("shelfRow-yourJournal").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneAppBar").assertDoesNotExist()
        Espresso.pressBack()
        composeRule.onNodeWithTag("phoneShelfSheet").assertIsNotDisplayed()
    }

    @Test
    fun shelfRowsCloseAndReachTheirPanes() {
        val rows = listOf(
            "shelfRow-yourJournal" to PhoneRoute.YourJournal,
            "shelfRow-thisDevice" to PhoneRoute.ThisDevice,
            "shelfRow-notifications" to PhoneRoute.Notifications,
            "shelfRow-help" to PhoneRoute.Help,
            "shelfRow-aboutSolstone" to PhoneRoute.AboutSolstone,
        )
        var screenGeneration by mutableStateOf(0)
        composeRule.setContent {
            key(screenGeneration) {
                PhoneObserverScreen(
                    loadState = loaded(audioOn()),
                    status = connected(),
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                )
            }
        }
        rows.forEachIndexed { index, (rowTag, route) ->
            composeRule.onNodeWithTag("phoneShelfOpener").performClick()
            composeRule.onNodeWithTag(rowTag).assertIsDisplayed().performClick()
            composeRule.onNodeWithTag("phoneShelfSheet").assertIsNotDisplayed()
            if (route == PhoneRoute.AboutSolstone) {
                composeRule.onNodeWithTag("licencesRow").assertIsDisplayed()
            } else {
                composeRule.onNode(paneTitleMatcher(spokenPaneTitle(route))).assertIsDisplayed()
            }
            if (index != rows.lastIndex) {
                composeRule.runOnIdle { screenGeneration += 1 }
            }
        }
    }

    @Test
    fun shelfRoutesRenderHeadingsAndPaneTitles() {
        val routes = listOf(
            PhoneRoute.YourJournal to "your journal",
            PhoneRoute.ThisDevice to "this device",
            PhoneRoute.Notifications to "notifications",
            PhoneRoute.Help to "help",
        )
        var routeIndex by mutableStateOf(0)
        composeRule.setContent {
            val route = routes[routeIndex].first
            key(route) {
                PhoneObserverScreen(
                    loadState = loaded(audioOn()),
                    status = connected(),
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                    initial = PhoneRouteStack.Empty.showInDetail(route),
                )
            }
        }
        routes.forEachIndexed { index, (route, heading) ->
            composeRule.onNodeWithText(heading).assertIsDisplayed()
            composeRule.onNode(paneTitleMatcher(spokenPaneTitle(route))).assertIsDisplayed()
            if (index != routes.lastIndex) {
                composeRule.runOnIdle { routeIndex += 1 }
            }
        }
    }

    @Test
    fun licensesActionIsVisibleAccessibleAndOpensItsPane() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                initial = PhoneRouteStack.Empty.showInDetail(PhoneRoute.AboutSolstone),
            )
        }

        composeRule.onNodeWithText("licenses").assertIsDisplayed()
        val action = composeRule.onNodeWithTag("licencesRow").fetchSemanticsNode()
        assertEquals(
            listOf("licenses"),
            action.config.getOrNull(SemanticsProperties.Text)?.map { it.text },
        )
        assertTrue(action.config.getOrNull(SemanticsActions.OnClick) != null)
        val minPx = with(composeRule.density) { MINIMUM_TOUCH_TARGET_DP.dp.toPx() }
        assertTrue(action.size.width >= minPx - 1f)
        assertTrue(action.size.height >= minPx - 1f)

        composeRule.onNodeWithTag("licencesRow").performClick()
        composeRule.onNode(paneTitleMatcher("licenses")).assertIsDisplayed()
    }

    @Test
    fun shelfRowsDoNotExposeTabRoles() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
                initialShelfOpen = true,
            )
        }
        listOf(
            "shelfRow-yourJournal",
            "shelfRow-thisDevice",
            "shelfRow-notifications",
            "shelfRow-help",
            "shelfRow-aboutSolstone",
        ).forEach { rowTag ->
            val role = composeRule.onNodeWithTag(rowTag).fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Role)
            assertNotEquals(Role.Tab, role)
        }
    }

    @Test
    fun waitingRowCarriesSourceIdentity() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = PhoneStatusModel(true, false, 1, true),
                waiting = listOf(audioOn()),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("statusPill").performClick()
        composeRule.onNodeWithTag("waitingRow-audio").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("statusPane").assertDoesNotExist()
    }

    @Test
    fun liveRegionTextDoesNotContainCount() {
        val decrements = 3
        var pending by mutableStateOf(6)
        composeRule.setContent {
            PhoneStatusPill(
                model = PhoneStatusModel(true, true, pending, true),
                onClick = {},
            )
        }
        composeRule.waitForIdle()
        val live = liveRegionNode()
        val state = stateDescriptionNode()
        assertNotEquals(live.id, state.id)
        val firstLive = announcedText(live)
        val descriptions = mutableListOf(state.config.getOrNull(SemanticsProperties.StateDescription))
        assertFalse("live announced contains count $pending: $firstLive", pending.toString() in firstLive)
        repeat(decrements) {
            pending -= 1
            composeRule.waitForIdle()
            val liveNow = announcedText(liveRegionNode())
            assertEquals(firstLive, liveNow)
            assertFalse("live announced contains count $pending: $liveNow", pending.toString() in liveNow)
            descriptions += stateDescriptionNode().config.getOrNull(SemanticsProperties.StateDescription)
        }
        assertEquals(decrements, descriptions.zipWithNext().count { it.first != it.second })
    }

    @Test
    fun deckScrollsBelowFixedAppBar() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(*(0 until 40).map { status("s-$it", SourceState.ON, SourceWish.On) }.toTypedArray()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.waitForIdle()
        val appBarBottom = composeRule.onNodeWithTag("phoneAppBar").fetchSemanticsNode().boundsInRoot.bottom
        val firstTop = composeRule.onNodeWithTag("sourceTile-s-0", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        assertTrue(
            "at rest first tile top $firstTop should be at or below app bar $appBarBottom",
            firstTop >= appBarBottom,
        )
        val atRestOffset = composeRule.onNodeWithTag("sourceGrid")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()
        assertEquals("source grid should start at rest", 0f, atRestOffset, 0f)
        composeRule.onNodeWithTag("sourceGrid").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val scrolledOffset = composeRule.onNodeWithTag("sourceGrid")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()
        assertTrue("source grid did not scroll: $scrolledOffset", scrolledOffset > 0f)
        val appBarBottomAfterScroll = composeRule.onNodeWithTag("phoneAppBar")
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        assertEquals("app bar moved after deck scroll", appBarBottom, appBarBottomAfterScroll, 0f)
        val sourceTiles = composeRule.onAllNodes(sourceTileMatcher(), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("expected rendered source tiles after scroll", sourceTiles.isNotEmpty())
        val minTop = sourceTiles.minOf { it.boundsInRoot.top }
        assertTrue(
            "deck tile top $minTop should remain below app bar $appBarBottomAfterScroll",
            minTop >= appBarBottomAfterScroll,
        )
    }

    @Test
    fun failedLoadIsNonTextAndHasNoOwnerProse() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = LoadState.Failed(IllegalStateException("boom")),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("sourcesFailed").assertIsDisplayed()
        composeRule.onNodeWithText("boom").assertDoesNotExist()
    }

    @Test
    fun switchMeetsMinimumTouchTarget() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        val node = composeRule.onNodeWithTag("sourceSwitch-audio", useUnmergedTree = true).fetchSemanticsNode()
        val density = composeRule.density
        val minPx = with(density) { MINIMUM_TOUCH_TARGET_DP.dp.toPx() }
        assertTrue(node.size.width >= minPx - 1f)
        assertTrue(node.size.height >= minPx - 1f)
    }

    @Test
    fun locationLabelStaysBelowItsSwitchAtCompactFontScales() {
        var toggles = 0
        var fontScale by mutableStateOf(1.3f)
        var screenGeneration by mutableStateOf(0)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale),
            ) {
                key(screenGeneration) {
                    PhoneObserverScreen(
                        loadState = loaded(status("location", SourceState.ON, SourceWish.On)),
                        status = connected(),
                        onToggle = { sourceId, _ ->
                            if (sourceId == "location") toggles += 1
                        },
                        onStartObserving = {},
                    )
                }
            }
        }

        listOf(1.3f, 2f).forEachIndexed { index, scale ->
            composeRule.runOnIdle { fontScale = scale }
            val label = composeRule.onNodeWithTag("sourceLabel-location", useUnmergedTree = true)
                .fetchSemanticsNode()
            val toggle = composeRule.onNodeWithTag("sourceSwitch-location", useUnmergedTree = true)
                .fetchSemanticsNode()
            val tile = composeRule.onNodeWithTag("sourceTile-location", useUnmergedTree = true)
                .fetchSemanticsNode()
            val density = composeRule.density
            val minPx = with(density) { MINIMUM_TOUCH_TARGET_DP.dp.toPx() }

            assertTrue("$scale label must follow its switch", toggle.boundsInRoot.bottom <= label.boundsInRoot.top)
            assertTrue(
                "$scale switch must remain inside the tile",
                tile.boundsInRoot.left <= toggle.boundsInRoot.left &&
                    tile.boundsInRoot.top <= toggle.boundsInRoot.top &&
                    tile.boundsInRoot.right >= toggle.boundsInRoot.right &&
                    tile.boundsInRoot.bottom >= toggle.boundsInRoot.bottom,
            )
            assertTrue(
                "$scale label must remain inside the tile",
                tile.boundsInRoot.left <= label.boundsInRoot.left &&
                    tile.boundsInRoot.top <= label.boundsInRoot.top &&
                    tile.boundsInRoot.right >= label.boundsInRoot.right &&
                    tile.boundsInRoot.bottom >= label.boundsInRoot.bottom,
            )
            assertTrue("$scale switch width", toggle.size.width >= minPx - 1f)
            assertTrue("$scale switch height", toggle.size.height >= minPx - 1f)

            composeRule.onNodeWithTag("sourceTile-location").performTouchInput { click(center) }
            composeRule.onNode(paneTitleMatcher("location")).assertIsDisplayed()
            composeRule.runOnIdle { screenGeneration += 1 }
            composeRule.onNodeWithTag("sourceSwitch-location", useUnmergedTree = true).performClick()
            assertEquals("$scale switch action", index + 1, toggles)
        }
    }

    @Test
    fun tileNotPresentedAsRunningForAwaitingSetup() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(status("audio", SourceState.SETTING_UP, SourceWish.On)),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithText("SETTING_UP", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("setting up", tileStateDescription("audio"))
        assertEquals("audio", tileAccessibleName("audio"))
    }

    @Test
    fun pausedSourceRendersNoInventedCopy() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(status("audio", SourceState.PAUSED, SourceWish.On)),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithText("paused", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("PAUSED", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("paused", tileStateDescription("audio"))
        assertEquals("audio", tileAccessibleName("audio"))
    }

    @Test
    fun capturingSourceRendersOn() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(audioOn()),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("sourceLabel-audio", useUnmergedTree = true)
            .assertTextEquals("audio")
        composeRule.onNodeWithText("on", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("on", tileStateDescription("audio"))
        assertEquals("audio", tileAccessibleName("audio"))
    }

    @Test
    fun needsAttentionAudioTileRendersHonestUnknownDiagnosis() {
        composeRule.setContent {
            PhoneObserverScreen(
                loadState = loaded(status("audio", SourceState.NEEDS_ATTENTION, SourceWish.On)),
                status = connected(),
                onToggle = { _, _ -> },
                onStartObserving = {},
            )
        }
        composeRule.onNodeWithTag("sourceLabel-audio", useUnmergedTree = true)
            .assertTextEquals("audio")
        composeRule.onNodeWithText("needs attention", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "the reason it couldn't reach your journal isn't clear.",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        assertEquals("needs attention", tileStateDescription("audio"))
        assertEquals("audio", tileAccessibleName("audio"))
        composeRule.onNodeWithText("tap to fix", useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * The tile's accessible NAME. Section 5.5 splits it from the value: the name is the
     * source's own label, the value is the state word alone. Both were previously merged
     * into the value, which left the tile with no name at all.
     */
    private fun tileAccessibleName(sourceId: String): String? =
        composeRule.onNodeWithTag("sourceTile-$sourceId", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ContentDescription)
            ?.firstOrNull()

    private fun tileStateDescription(sourceId: String): String? =
        composeRule.onNodeWithTag("sourceTile-$sourceId")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)

    private fun liveRegionNode() =
        composeRule.onNodeWithTag("statusLiveRegion", useUnmergedTree = true).fetchSemanticsNode()

    private fun stateDescriptionNode() =
        composeRule.onNodeWithTag("statusState", useUnmergedTree = true).fetchSemanticsNode()

    private fun announcedText(node: SemanticsNode): String {
        val parts = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { parts.addAll(it) }
            n.config.getOrNull(SemanticsProperties.Text)?.let { texts ->
                parts.addAll(texts.map { it.text })
            }
            n.config.getOrNull(SemanticsProperties.StateDescription)?.let { parts.add(it) }
            n.children.forEach(::walk)
        }
        walk(node)
        return parts.joinToString(" ")
    }

    private fun sourceTileMatcher() = SemanticsMatcher("source tile") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("sourceTile-") == true
    }

    private fun waitForFocus(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.Focused) == true
        }
        composeRule.onNodeWithTag(tag).assertIsFocused()
    }

    private fun paneTitleMatcher(title: String) = SemanticsMatcher("pane $title") { node ->
        node.config.getOrNull(SemanticsProperties.PaneTitle) == title
    }
}

private fun audioOn() = status("audio", SourceState.ON, SourceWish.On)

private fun cameraOn() = status("camera", SourceState.ON, SourceWish.On)

private fun status(id: String, state: SourceState, wish: SourceWish) =
    SourceStatus(id, wish, state, ReasonCode.NONE)

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
