// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.window.core.layout.WindowSizeClass
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import java.time.LocalTime
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// INTERIM: owner-facing accessible name for the up control; pending copy ruling.
private const val UP_CONTENT_DESCRIPTION = "up"
private const val SHELF_CONTENT_DESCRIPTION = "settings"

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val permanentDeckValue = ThreePaneScaffoldValue(
    PaneAdaptedValue.Expanded,
    PaneAdaptedValue.Expanded,
    PaneAdaptedValue.Hidden,
)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun PhoneObserverScreen(
    loadState: LoadState<SourcesReadModel>,
    status: PhoneStatusModel?,
    waiting: List<app.solstone.observer.harness.SourceStatus> = emptyList(),
    defaultDetailStatus: PhoneDefaultDetailStatus = PhoneDefaultDetailStatus.Loading,
    onRefreshStatus: (() -> Unit)? = null,
    onToggle: (String, SourceWish) -> Unit,
    onStartObserving: () -> Unit,
    onConnectJournal: () -> Unit = {},
    modifier: Modifier = Modifier,
    initial: PhoneRouteStack = PhoneRouteStack.Empty,
    initialShelfOpen: Boolean = false,
    initialStatusOpen: Boolean = false,
    version: String = "",
    captureWidthDp: Int? = null,
) {
    val windowAdaptiveInfo = currentWindowAdaptiveInfo(
        supportLargeAndXLargeWidth = true,
    )
    PhoneObserverScreen(
        loadState = loadState,
        status = status,
        waiting = waiting,
        defaultDetailStatus = defaultDetailStatus,
        onRefreshStatus = onRefreshStatus,
        onToggle = onToggle,
        onStartObserving = onStartObserving,
        onConnectJournal = onConnectJournal,
        modifier = modifier,
        initial = initial,
        initialShelfOpen = initialShelfOpen,
        initialStatusOpen = initialStatusOpen,
        version = version,
        windowAdaptiveInfo = captureWidthDp?.let { widthDp ->
            WindowAdaptiveInfo(
                windowSizeClass = WindowSizeClass(widthDp, CAPTURE_HEIGHT_DP),
                windowPosture = Posture(),
            )
        } ?: windowAdaptiveInfo,
    )
}

private const val CAPTURE_HEIGHT_DP = 800

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun PhoneObserverScreen(
    loadState: LoadState<SourcesReadModel>,
    status: PhoneStatusModel?,
    waiting: List<app.solstone.observer.harness.SourceStatus> = emptyList(),
    defaultDetailStatus: PhoneDefaultDetailStatus = PhoneDefaultDetailStatus.Loading,
    onRefreshStatus: (() -> Unit)? = null,
    onToggle: (String, SourceWish) -> Unit,
    onStartObserving: () -> Unit,
    onConnectJournal: () -> Unit = {},
    modifier: Modifier = Modifier,
    initial: PhoneRouteStack = PhoneRouteStack.Empty,
    initialShelfOpen: Boolean = false,
    initialStatusOpen: Boolean = false,
    version: String = "",
    windowAdaptiveInfo: WindowAdaptiveInfo,
) {
    var paneStates by rememberPaneStates(
        initial = PaneStates.Empty
            .let { if (initialShelfOpen) it.open(PhonePane.SHELF) else it }
            .let { if (initialStatusOpen) it.open(PhonePane.STATUS) else it },
    )
    var detailStack by rememberPhoneRouteStack(initial)
    val drawerState = rememberDrawerState(
        if (initialShelfOpen) DrawerValue.Open else DrawerValue.Closed,
    )
    val latestPaneStates by rememberUpdatedState(paneStates)
    val scope = rememberCoroutineScope()
    val applicationContext = LocalContext.current.applicationContext
    val homeTileStore = remember(applicationContext) {
        SharedPreferencesPhoneHomeTileStore(applicationContext)
    }
    val openerFocusRequester = remember { FocusRequester() }
    val firstShelfRowFocusRequester = remember { FocusRequester() }
    val hour = remember { LocalTime.now().hour }
    val minWidthDp = windowAdaptiveInfo
        .windowSizeClass
        .minWidthDp
    val widthClass = classifyWindowWidth(minWidthDp)
    val directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(windowAdaptiveInfo)
    val deckWidth = deckPaneWidth(widthClass)
    val deckGridState = rememberLazyGridState()
    val renderSplit = widthClass != WidthClass.COMPACT && shouldRenderSplit(
        maxHorizontalPartitions = directive.maxHorizontalPartitions,
        deckPaneWidthDp = deckWidth.value.toInt(),
    )
    val statusOpen = paneStates.isOpen(PhonePane.STATUS)
    val shelfOpen = paneStates.isOpen(PhonePane.SHELF)
    val deckPaneOpen = statusOpen || shelfOpen
    val top = detailStack.toList().lastOrNull()
    val popDetail = {
        val remaining = detailStack.toList().dropLast(1)
        detailStack = remaining.fold(PhoneRouteStack.Empty) { stack, route ->
            if (stack.depth == 0) stack.showInDetail(route) else stack.pushInDetail(route)
        }
    }
    val popsDetail = resolveBack(paneStates, detailStack, widthClass).popsDetail
    LaunchedEffect(drawerState) {
        var previousTarget: DrawerValue? = null
        snapshotFlow { drawerState.targetValue }.collectLatest { target ->
            val previous = previousTarget
            previousTarget = target
            if (previous == null || previous == target) return@collectLatest
            // targetValue changes when drawer intent changes; currentValue waits
            // for animation completion and would leave back handling behind.
            val updatedPaneStates = if (target == DrawerValue.Open) {
                latestPaneStates.open(PhonePane.SHELF)
            } else {
                latestPaneStates.close(PhonePane.SHELF)
            }
            paneStates = updatedPaneStates
        }
    }
    PhoneShell(
        title = {
            val text = top?.let(::headingText)
            if (text != null) {
                Text(
                    text = text,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        statusAction = {
            status?.let { model ->
                Box {
                    PhoneStatusPill(
                        model = model,
                        onClick = {
                            paneStates = if (statusOpen) {
                                paneStates.close(PhonePane.STATUS)
                            } else {
                                paneStates.open(PhonePane.STATUS)
                            }
                        },
                    )
                    if (statusOpen) {
                        PhoneStatusPane(
                            model = model,
                            waiting = waiting,
                            onDismiss = { paneStates = paneStates.close(PhonePane.STATUS) },
                            onOpenSource = { id ->
                                paneStates = paneStates.close(PhonePane.STATUS)
                                detailStack = detailStack.showInDetail(PhoneRoute.SourceDetail(id))
                            },
                            onConnectJournal = onConnectJournal,
                        )
                    }
                }
            }
        },
        // § 2.5: the mark appears on the home pill and inside the journal pane, and
        // NOWHERE else in the shell — "one screen never shows the mark twice". The
        // shell draws the pill over whatever the content slot holds, so without this
        // the pill floated over every source detail and every shelf pane, and on
        // `your journal` it sat under the pane's own mark card.
        journalMark = {
            val marksOwnPane = top == PhoneRoute.YourJournal
            val showsDeck = top == null || renderSplit
            if (showsDeck && !marksOwnPane) {
                PhoneJournalMarkPill(onClick = onConnectJournal)
            }
        },
        navigationIcon = {
            if (!renderSplit && popsDetail) {
                IconButton(
                    onClick = popDetail,
                    modifier = Modifier.testTag("phoneUp"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.phone_navigation_up),
                        contentDescription = UP_CONTENT_DESCRIPTION,
                    )
                }
            } else {
                var previousShelfOpen by remember { mutableStateOf<Boolean?>(null) }
                // This implements the requirement that input focus return to the opener after close.
                // ModalNavigationDrawer currently resets this request on both Back and dismiss, so the
                // committed test verifies closure, opener visibility, and reopening instead.
                LaunchedEffect(shelfOpen) {
                    val previous = previousShelfOpen
                    previousShelfOpen = shelfOpen
                    if (previous == true && !shelfOpen) {
                        openerFocusRequester.requestFocus()
                    }
                }
                // This branch is only composed while the opener is attached,
                // so a detail depth never requests focus on an absent opener.
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier
                        .focusRequester(openerFocusRequester)
                        .testTag("phoneShelfOpener"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.phone_settings),
                        contentDescription = SHELF_CONTENT_DESCRIPTION,
                    )
                }
            }
        },
        drawerState = drawerState,
        shelfOpen = shelfOpen,
        drawerContent = {
            PhoneShelfContent(
                shelfOpen = shelfOpen,
                onNavigate = { route ->
                    scope.launch {
                        drawerState.close()
                        detailStack = detailStack.showInDetail(route)
                    }
                },
                version = version,
                firstRowFocusRequester = firstShelfRowFocusRequester,
                onOpenPrivacy = { openPrivacyPolicy(applicationContext) },
            )
        },
        content = { paddingValues ->
            val deckContent: @Composable (WidthClass, Modifier) -> Unit = { deckWidthClass, deckModifier ->
                PhoneDeck(
                    loadState = loadState,
                    contentPadding = paddingValues,
                    gridState = deckGridState,
                    widthClass = deckWidthClass,
                    paneOpen = deckPaneOpen,
                    onOpenSource = { id ->
                        detailStack = detailStack.showInDetail(PhoneRoute.SourceDetail(id))
                    },
                    onToggle = onToggle,
                    onOpenImport = {
                        detailStack = detailStack.showInDetail(PhoneRoute.Import)
                    },
                    onOpenAddMore = {
                        detailStack = detailStack.showInDetail(PhoneRoute.AddMore)
                    },
                    hour = hour,
                    isOnHome = homeTileStore::hasTile,
                    modifier = deckModifier,
                )
            }
            if (renderSplit) {
                ListDetailPaneScaffold(
                    directive = directive,
                    value = permanentDeckValue,
                    listPane = {
                        AnimatedPane(
                            modifier = Modifier
                                .preferredWidth(deckWidth)
                                .fillMaxSize(),
                        ) {
                            BoxWithConstraints {
                                deckContent(
                                    classifyWindowWidth(maxWidth.value.toInt()),
                                    Modifier,
                                )
                            }
                        }
                    },
                    detailPane = {
                        AnimatedPane(modifier = Modifier.fillMaxSize()) {
                            PhoneDetailPane(
                                top = top,
                                loadState = loadState,
                                homeTileStore = homeTileStore,
                                onStartObserving = onStartObserving,
                                onConnectJournal = onConnectJournal,
                                onOpenLicences = {
                                    detailStack = detailStack.pushInDetail(PhoneRoute.Licences)
                                },
                                onOpenSource = { id ->
                                    detailStack = detailStack.showInDetail(PhoneRoute.SourceDetail(id))
                                },
                                defaultDetailStatus = defaultDetailStatus,
                                onRefreshStatus = onRefreshStatus,
                                version = version,
                                isOnHome = homeTileStore::hasTile,
                                onToggle = onToggle,
                                modifier = Modifier.padding(paddingValues),
                                leadingSlot = if (top != null) {
                                    {
                                        IconButton(
                                            onClick = popDetail,
                                            modifier = Modifier.testTag("phoneUp"),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.phone_navigation_up),
                                                contentDescription = UP_CONTENT_DESCRIPTION,
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    },
                    modifier = modifier.testTag("phoneSplit"),
                )
            } else if (top == null) {
                deckContent(widthClass, modifier)
            } else {
                PhoneDetailPane(
                    top = top,
                    loadState = loadState,
                    homeTileStore = homeTileStore,
                    onStartObserving = onStartObserving,
                    onConnectJournal = onConnectJournal,
                    onOpenLicences = {
                        detailStack = detailStack.pushInDetail(PhoneRoute.Licences)
                    },
                    onOpenSource = { id ->
                        detailStack = detailStack.showInDetail(PhoneRoute.SourceDetail(id))
                    },
                    defaultDetailStatus = defaultDetailStatus,
                    onRefreshStatus = onRefreshStatus,
                    version = version,
                    isOnHome = homeTileStore::hasTile,
                    onToggle = onToggle,
                    modifier = modifier.padding(paddingValues),
                )
            }
        },
    )
    PhoneBackLadder(
        paneStates = paneStates,
        detailStack = detailStack,
        widthClass = widthClass,
        onClosePane = { pane -> paneStates = paneStates.close(pane) },
        onPopDetail = popDetail,
    )
}
