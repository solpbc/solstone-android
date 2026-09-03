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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.solstone.core.model.ReasonCode
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
    isOnHome: (String) -> Boolean = { true },
) {
    val hide = if (paneOpen) Modifier.clearAndSetSemantics { } else Modifier
    val greeting = greetingFor(hour)
    val margin =
        if (widthClass == WidthClass.COMPACT) PHONE_CONTENT_MARGIN_DP.dp
        else PHONE_CONTENT_MARGIN_WIDE_DP.dp
    val columns = if (widthClass == WidthClass.COMPACT) 2 else 3
    val layoutDirection = LocalLayoutDirection.current
    val startInset = margin + contentPadding.calculateStartPadding(layoutDirection)
    val endInset = margin + contentPadding.calculateEndPadding(layoutDirection)
    val greetingInset = contentPadding.calculateTopPadding()
    val readModel = (loadState as? LoadState.Loaded)?.value
    val paired = readModel?.observer?.reason != ReasonCode.UNPAIRED
    Column(
        modifier
            .fillMaxSize()
            .semantics { paneTitle = greeting }
            .then(hide)
            .testTag("deck"),
    ) {
        PhoneGreetingSlot(
            hour,
            Modifier.padding(start = startInset, end = endInset, top = greetingInset),
        )
        val allSources = readModel?.sources.orEmpty()
        // ⚠ The deck showed EVERY source unconditionally while `give this a tile on
        // home` wrote to a store nothing read — a control that could not perform what
        // it names, which § 2.4 forbids outright. The deck reads the store now.
        //
        // The store's default is "on home": a source the owner has is on home until
        // they hide it, and § 5 is explicit that hiding never turns a source off.
        val sources = allSources.filter { isOnHome(it.sourceId) }
        if (loadState is LoadState.Loaded) {
            val attention = allSources.count { it.state == SourceState.NEEDS_ATTENTION }
            if (attention > 0) {
                Text(
                    text = if (attention == 1) "1 needs attention" else "$attention need attention",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = startInset, end = endInset, top = 4.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(top = ShellMetrics.sectionSpacing)) {
            PhoneSourceGrid(
                sources = sources,
                columns = columns,
                paired = paired,
                // The greeting above already consumes the top inset for this column.
                contentPadding = PaddingValues(
                    start = startInset,
                    end = endInset,
                    top = 0.dp,
                    // The journal pill floats over the bottom of the deck; without
                    // this the last row scrolls to rest underneath it.
                    bottom = contentPadding.calculateBottomPadding() + 96.dp,
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
    paired: Boolean = false,
) {
    LazyVerticalGrid(
        // ⚠ Fixed, never `GridCells.Adaptive`. An adaptive grid lets each row pick its
        // own column count, which is what left the iOS deck ragged. Evenness is § 2.1's
        // whole point: a surface whose job is parity between N configurable things must
        // not promote one of them structurally, and a row of three beside a row of two
        // does exactly that.
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("sourceGrid"),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(ShellMetrics.gutter),
        verticalArrangement = Arrangement.spacedBy(ShellMetrics.gutter),
    ) {
        item(key = "importTile") {
            PhoneNonSourceTile(
                label = "import",
                // ⚠ Was `photos, files, anything`. `anything` claims a category the
                // product does not offer — the import surface names photos and files
                // and nothing else — and the correction was already ruled for iOS on
                // 2026-08-23 (mobile-shell.md § "import is a tile"). Android had
                // carried the retired string since.
                subLine = "photos and files",
                glyph = R.drawable.phone_import,
                index = 0,
                count = sources.size + LEADING_NON_SOURCE_TILE_COUNT,
                testTag = "importTile",
                onOpen = onOpenImport,
            )
        }
        item(key = "addMoreTile") {
            PhoneNonSourceTile(
                label = "add more",
                // ⚠ Was `sources and devices`. `devices` is a category the product's
                // taxonomy has on NEITHER platform -- iOS's watch is a *source* in
                // § 5.2's source-label table, and `grep '"devices"'` over solstone-swift
                // returns nothing. The word had a home once and lost it: the pane's
                // original section headers were `on this phone` / `devices` / `not set
                // up yet`, replaced at v3/v5 by `not on home` / `already on home`
                // (copy-deck.md), and this sub-line survived the grouping's deletion.
                //
                // § 5's rule: a sub-line enumerates a set's members only when that set
                // is fixed and identical on every platform; where § 5.2 varies it by
                // platform, the sub-line names the CATEGORY. `photos and files`
                // enumerates because the import paths are shared and fixed; this one
                // categorizes. The non-parallelism is the rule working.
                subLine = "sources",
                glyph = R.drawable.phone_add_more,
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
                paired = paired,
            )
        }
    }
}

/**
 * `import` and `add more`.
 *
 * ⛔ **Neither carries a state word or a control**, and that is § 2.4 rather than a
 * layout choice: they are destinations, not sources — nothing about them can be on or
 * off, so a state or a switch here would claim something untrue. They share
 * [PhoneTileSurface] with a source tile so the grid stays one set of peers.
 */
@Composable
private fun PhoneNonSourceTile(
    label: String,
    subLine: String,
    @androidx.annotation.DrawableRes glyph: Int,
    index: Int,
    count: Int,
    testTag: String,
    onOpen: () -> Unit,
) {
    PhoneTileSurface(
        modifier = Modifier
            .semantics {
                collectionItemInfo = CollectionItemInfo(index, count, 0, 1)
            }
            .testTag(testTag),
        onClick = onOpen,
        dashed = true,
    ) {
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = shellSecondaryInk,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subLine,
            style = MaterialTheme.typography.bodySmall,
            color = shellSecondaryInk,
        )
    }
}
