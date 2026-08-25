// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel

private const val LEADING_NON_SOURCE_TILE_COUNT = 2

@Composable
fun PhoneDeck(
    loadState: LoadState<SourcesReadModel>,
    contentPadding: PaddingValues,
    gridState: LazyGridState,
    widthClass: WidthClass,
    paneOpen: Boolean,
    onOpenSource: (String) -> Unit,
    onToggle: (String, SourceWish) -> Unit,
    onOpenImport: () -> Unit,
    onOpenAddMore: () -> Unit,
    hour: Int,
    modifier: Modifier = Modifier,
) {
    val hide = if (paneOpen) Modifier.clearAndSetSemantics { } else Modifier
    val margin =
        if (widthClass == WidthClass.COMPACT) PHONE_CONTENT_MARGIN_DP.dp
        else PHONE_CONTENT_MARGIN_WIDE_DP.dp
    val columns = if (widthClass == WidthClass.COMPACT) 2 else 3
    val layoutDirection = LocalLayoutDirection.current
    val startInset = margin + contentPadding.calculateStartPadding(layoutDirection)
    val endInset = margin + contentPadding.calculateEndPadding(layoutDirection)
    val greetingInset = contentPadding.calculateTopPadding()
    Column(
        modifier
            .fillMaxSize()
            .then(hide)
            .testTag("deck"),
    ) {
        PhoneGreetingSlot(
            hour,
            Modifier.padding(start = startInset, end = endInset, top = greetingInset),
        )
        val sources = when (loadState) {
            is LoadState.Loaded -> loadState.value.sources
            is LoadState.Loading,
            is LoadState.Failed -> emptyList()
        }
        if (loadState is LoadState.Loaded) {
            val attention = sources.count { it.state == SourceState.NEEDS_ATTENTION }
            if (attention > 0) {
                Text(
                    text = if (attention == 1) "1 needs attention" else "$attention need attention",
                    modifier = Modifier.padding(start = startInset, end = endInset),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            PhoneSourceGrid(
                sources = sources,
                columns = columns,
                // The greeting above already consumes the top inset for this column.
                contentPadding = PaddingValues(
                    start = startInset,
                    end = endInset,
                    top = 0.dp,
                    bottom = contentPadding.calculateBottomPadding(),
                ),
                gridState = gridState,
                onOpenSource = onOpenSource,
                onToggle = onToggle,
                onOpenImport = onOpenImport,
                onOpenAddMore = onOpenAddMore,
            )
            when (loadState) {
                is LoadState.Failed -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("sourcesFailed"),
                    )
                }
                is LoadState.Loading -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("sourcesLoading"),
                    )
                }
                is LoadState.Loaded -> Unit
            }
        }
    }
}

@Composable
internal fun PhoneSourceGrid(
    sources: List<SourceStatus>,
    columns: Int,
    contentPadding: PaddingValues,
    gridState: LazyGridState,
    onOpenSource: (String) -> Unit,
    onToggle: (String, SourceWish) -> Unit,
    onOpenImport: () -> Unit,
    onOpenAddMore: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("sourceGrid"),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "importTile") {
            PhoneNonSourceTile(
                label = "import",
                subLine = "photos, files, anything",
                index = 0,
                count = sources.size + LEADING_NON_SOURCE_TILE_COUNT,
                testTag = "importTile",
                onOpen = onOpenImport,
            )
        }
        item(key = "addMoreTile") {
            PhoneNonSourceTile(
                label = "add more",
                subLine = "sources and devices",
                index = 1,
                count = sources.size + LEADING_NON_SOURCE_TILE_COUNT,
                testTag = "addMoreTile",
                onOpen = onOpenAddMore,
            )
        }
        itemsIndexed(sources, key = { _, item -> item.sourceId }) { index, status ->
            PhoneSourceTile(
                status = status,
                index = index + LEADING_NON_SOURCE_TILE_COUNT,
                count = sources.size + LEADING_NON_SOURCE_TILE_COUNT,
                onOpen = { onOpenSource(status.sourceId) },
                onToggle = { wish -> onToggle(status.sourceId, wish) },
            )
        }
    }
}

@Composable
private fun PhoneNonSourceTile(
    label: String,
    subLine: String,
    index: Int,
    count: Int,
    testTag: String,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onOpen)
            .semantics {
                collectionItemInfo = CollectionItemInfo(index, count, 0, 1)
            }
            .padding(12.dp)
            .testTag(testTag),
    ) {
        Text(label)
        Text(subLine)
    }
}
