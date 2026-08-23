// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel

@Composable
fun PhoneDeck(
    loadState: LoadState<SourcesReadModel>,
    contentPadding: PaddingValues,
    widthClass: WidthClass,
    paneOpen: Boolean,
    onOpenSource: (String) -> Unit,
    onToggle: (String, SourceWish) -> Unit,
    hour: Int,
    modifier: Modifier = Modifier,
) {
    val hide = if (paneOpen) Modifier.clearAndSetSemantics { } else Modifier
    val margin = if (widthClass == WidthClass.COMPACT) 16.dp else 24.dp
    val columns = if (widthClass == WidthClass.COMPACT) 2 else 3
    Column(
        modifier
            .fillMaxSize()
            .then(hide)
            .testTag("deck"),
    ) {
        PhoneGreetingSlot(hour)
        when (loadState) {
            is LoadState.Failed -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("sourcesFailed"),
                )
            }
            is LoadState.Loading -> {
                Column(Modifier.testTag("sourcesLoading")) { }
            }
            is LoadState.Loaded -> {
                val sources = loadState.value.sources
                val attention = sources.count { it.state == SourceState.NEEDS_ATTENTION }
                if (attention > 0) {
                    Text(
                        text = if (attention == 1) "1 needs attention" else "$attention need attention",
                        modifier = Modifier.padding(horizontal = margin),
                    )
                }
                val layoutDirection = LocalLayoutDirection.current
                PhoneSourceGrid(
                    sources = sources,
                    columns = columns,
                    contentPadding = PaddingValues(
                        start = margin + contentPadding.calculateStartPadding(layoutDirection),
                        end = margin + contentPadding.calculateEndPadding(layoutDirection),
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                    onOpenSource = onOpenSource,
                    onToggle = onToggle,
                )
            }
        }
    }
}

@Composable
internal fun PhoneSourceGrid(
    sources: List<SourceStatus>,
    columns: Int,
    contentPadding: PaddingValues,
    onOpenSource: (String) -> Unit,
    onToggle: (String, SourceWish) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .testTag("sourceGrid"),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(sources, key = { _, item -> item.sourceId }) { index, status ->
            PhoneSourceTile(
                status = status,
                index = index,
                count = sources.size,
                onOpen = { onOpenSource(status.sourceId) },
                onToggle = { wish -> onToggle(status.sourceId, wish) },
            )
        }
    }
}
