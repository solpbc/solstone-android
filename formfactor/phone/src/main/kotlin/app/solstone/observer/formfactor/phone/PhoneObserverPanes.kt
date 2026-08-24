// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourcesReadModel

// 2 x 48dp tiles + 8dp grid gap + 2 x 16dp margins = 136dp.
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
    onOpenLicences: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    leadingSlot: (@Composable () -> Unit)? = null,
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
            onOpenLicences = onOpenLicences,
            onOpenSource = onOpenSource,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun PhoneDetailContent(
    top: PhoneRoute?,
    loadState: LoadState<SourcesReadModel>,
    homeTileStore: PhoneHomeTileStore,
    onStartObserving: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier,
) {
    when (top) {
        null -> Box(
            modifier
                .fillMaxSize()
                .testTag("phoneDefaultDetail"),
        )
        PhoneRoute.AboutSolstone -> PhoneAboutPane(
            onOpenLicences = onOpenLicences,
            modifier = modifier,
        )
        PhoneRoute.Licences -> PhoneLicencesPane(modifier = modifier)
        PhoneRoute.Import -> PhoneImportPane(modifier = modifier)
        PhoneRoute.AddMore -> PhoneAddMorePane(
            onOpenSource = onOpenSource,
            modifier = modifier,
        )
        PhoneRoute.RouteA,
        PhoneRoute.RouteB,
        PhoneRoute.RouteC,
        PhoneRoute.RouteCChild,
        PhoneRoute.YourJournal,
        PhoneRoute.ThisDevice,
        PhoneRoute.Notifications,
        PhoneRoute.Help -> {
            Box(
                modifier
                    .fillMaxSize()
                    .semantics { paneTitle = top.paneTitle },
            )
        }
        is PhoneRoute.SourceDetail -> Box(
            modifier
                .fillMaxSize()
                .semantics { paneTitle = top.paneTitle },
        ) {
            PhoneSourceDetail(
                loadState = loadState,
                sourceId = top.sourceId,
                homeTileStore = homeTileStore,
                onStartObserving = onStartObserving,
            )
        }
    }
}
