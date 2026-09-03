// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourcesReadModel

// Guards the selected preferred deck width: 2 x 48dp tiles + 8dp grid gap + 2 x 16dp compact
// margins = 136dp. The split collapses entirely rather than shrinking the leading pane below it.
private const val MINIMUM_SPLIT_DECK_WIDTH_DP = 136

internal fun deckPaneWidth(widthClass: WidthClass): Dp = when (widthClass) {
    WidthClass.LARGE,
    WidthClass.EXTRA_LARGE -> 412.dp
    else -> 360.dp
}

internal fun shouldRenderSplit(
    maxHorizontalPartitions: Int,
    deckPaneWidthDp: Int,
): Boolean = maxHorizontalPartitions >= 2 && deckPaneWidthDp >= MINIMUM_SPLIT_DECK_WIDTH_DP

@Composable
internal fun PhoneDetailPane(
    top: PhoneRoute?,
    loadState: LoadState<SourcesReadModel>,
    homeTileStore: PhoneHomeTileStore,
    onStartObserving: () -> Unit,
    onConnectJournal: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenSource: (String) -> Unit,
    defaultDetailStatus: PhoneDefaultDetailStatus,
    onRefreshStatus: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leadingSlot: (@Composable () -> Unit)? = null,
    version: String = "",
    isOnHome: (String) -> Boolean = { true },
    onToggle: (String, app.solstone.observer.harness.SourceWish) -> Unit = { _, _ -> },
) {
    Column(modifier.fillMaxSize()) {
        leadingSlot?.let { slot ->
            Box(Modifier.fillMaxWidth()) {
                slot()
            }
        }
        PhoneDetailContent(
            top = top,
            loadState = loadState,
            homeTileStore = homeTileStore,
            onStartObserving = onStartObserving,
            onConnectJournal = onConnectJournal,
            onOpenLicences = onOpenLicences,
            onOpenSource = onOpenSource,
            defaultDetailStatus = defaultDetailStatus,
            onRefreshStatus = onRefreshStatus,
            version = version,
            isOnHome = isOnHome,
            onToggle = onToggle,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = PHONE_CONTENT_MARGIN_DP.dp),
        )
    }
}

@Composable
private fun PhoneDetailContent(
    top: PhoneRoute?,
    loadState: LoadState<SourcesReadModel>,
    homeTileStore: PhoneHomeTileStore,
    onStartObserving: () -> Unit,
    onConnectJournal: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenSource: (String) -> Unit,
    defaultDetailStatus: PhoneDefaultDetailStatus,
    onRefreshStatus: (() -> Unit)?,
    modifier: Modifier,
    version: String = "",
    isOnHome: (String) -> Boolean = { true },
    onToggle: (String, app.solstone.observer.harness.SourceWish) -> Unit = { _, _ -> },
) {
    when (top) {
        null -> PhoneDefaultDetailPane(
            status = defaultDetailStatus,
            onConnectJournal = onConnectJournal,
            onOpenSource = onOpenSource,
            onRefreshStatus = onRefreshStatus,
            modifier = modifier,
        )
        PhoneRoute.AboutSolstone -> PhoneAboutPane(
            onOpenLicences = onOpenLicences,
            version = version,
            modifier = modifier,
        )
        PhoneRoute.Licences -> PhoneLicencesPane(modifier = modifier)
        PhoneRoute.Import -> PhoneImportPane(modifier = modifier)
        PhoneRoute.AddMore -> PhoneAddMorePane(
            onOpenSource = onOpenSource,
            isOnHome = isOnHome,
            modifier = modifier,
        )
        PhoneRoute.YourJournal -> PhoneYourJournalPane(
            onConnectJournal = onConnectJournal,
            modifier = modifier,
        )
        PhoneRoute.ThisDevice -> PhoneThisDevicePane(
            version = version,
            modifier = modifier,
        )
        PhoneRoute.Notifications -> PhoneNotificationsPane(modifier = modifier)
        PhoneRoute.Help -> PhoneHelpPane(modifier = modifier)
        // Deliberately unimplemented placeholder routes, kept for the navigation
        // tests. They are the only surfaces allowed to expose an identifier.
        PhoneRoute.RouteA,
        PhoneRoute.RouteB,
        PhoneRoute.RouteC,
        PhoneRoute.RouteCChild -> {
            Box(
                modifier
                    .fillMaxSize()
                    .semantics { paneTitle = spokenPaneTitle(top) },
            )
        }
        is PhoneRoute.SourceDetail -> Box(
            modifier
                .fillMaxSize()
                .semantics { paneTitle = spokenPaneTitle(top) },
        ) {
            PhoneSourceDetail(
                loadState = loadState,
                sourceId = top.sourceId,
                homeTileStore = homeTileStore,
                onStartObserving = onStartObserving,
                onConnectJournal = onConnectJournal,
                onToggle = { wish -> onToggle(top.sourceId, wish) },
            )
        }
    }
}
