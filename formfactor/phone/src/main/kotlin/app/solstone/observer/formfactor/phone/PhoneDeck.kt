// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.watch.courier.WatchCourierStrings
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel

private const val NON_SOURCE_TILE_COUNT = 2

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
    showWelcome: Boolean = false,
    onConnectJournal: () -> Unit = {},
    onSetOneUp: () -> Unit = {},
    onSendLink: () -> Unit = {},
    onAlreadyHaveOne: () -> Unit = onConnectJournal,
    hour: Int,
    modifier: Modifier = Modifier,
    isOnHome: (String) -> Boolean = { true },
    extraSourceIds: List<String> = emptyList(),
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
    val paired = readModel?.observer?.paired == true
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
        val extraSources = if (extraSourceIds.contains(WatchCourierStrings.WATCH)) {
            listOf(SourceStatus(sourceId = WatchCourierStrings.WATCH, wish = SourceWish.On, state = SourceState.ON, reason = ReasonCode.NONE))
        } else emptyList()
        val combinedSources = (allSources + extraSources).distinctBy { it.sourceId }
        val sources = combinedSources.filter { isOnHome(it.sourceId) }
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
                showWelcome = showWelcome,
                onConnectJournal = onConnectJournal,
                onSetOneUp = onSetOneUp,
                onSendLink = onSendLink,
                onAlreadyHaveOne = onAlreadyHaveOne,
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
    showWelcome: Boolean = false,
    onConnectJournal: () -> Unit = {},
    onSetOneUp: () -> Unit = {},
    onSendLink: () -> Unit = {},
    onAlreadyHaveOne: () -> Unit = onConnectJournal,
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
        if (showWelcome) {
            item(key = "welcome", span = { GridItemSpan(maxLineSpan) }) {
                PhoneWelcomeCard(
                    onSetOneUp = onSetOneUp,
                    onSendLink = onSendLink,
                    onAlreadyHaveOne = onAlreadyHaveOne,
                )
            }
        }
        // 🔴 Sources first, destinations after. § 2.1 reads "one tile per source the owner has,
        // PLUS `import` and `add more`", and the shipped iOS deck puts them in that order, so
        // opening home on Android led with two destinations and pushed the owner's own sources
        // below them.
        itemsIndexed(sources, key = { _, item -> item.sourceId }) { index, status ->
            if (status.sourceId == WatchCourierStrings.WATCH) {
                val watch = LocalPhoneWatchSource.current
                PhoneNonSourceTile(
                    label = sourceLabel(WatchCourierStrings.WATCH),
                    subLine = watchTileSubLine(watch),
                    glyph = R.drawable.phone_source_watch,
                    index = index,
                    count = sources.size + NON_SOURCE_TILE_COUNT,
                    testTag = "sourceTile-${WatchCourierStrings.WATCH}",
                    onOpen = { onOpenSource(WatchCourierStrings.WATCH) },
                    dashed = false,
                )
            } else PhoneSourceTile(
                status = status,
                index = index,
                count = sources.size + NON_SOURCE_TILE_COUNT,
                onOpen = { onOpenSource(status.sourceId) },
                onToggle = { wish -> onToggle(status.sourceId, wish) },
                paired = paired,
            )
        }
        item(key = "importTile") {
            PhoneImportHomeTile(
                state = LocalPhoneImport.current.state,
                index = sources.size,
                count = sources.size + NON_SOURCE_TILE_COUNT,
                onOpen = onOpenImport,
            )
        }
        item(key = "addMoreTile") {
            PhoneNonSourceTile(
                label = phoneText("android.setup.add.more"),
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
                subLine = phoneText("android.setup.sources"),
                glyph = R.drawable.phone_add_more,
                index = sources.size + 1,
                count = sources.size + NON_SOURCE_TILE_COUNT,
                testTag = "addMoreTile",
                dashed = true,
                onOpen = onOpenAddMore,
            )
        }
    }
}

@Composable
private fun PhoneWelcomeCard(
    onSetOneUp: () -> Unit,
    onSendLink: () -> Unit,
    onAlreadyHaveOne: () -> Unit,
) {
    // The doors are tiles of their own, so they sit beside the welcome card rather than in it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("welcomeCard"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ShellMetrics.gutter),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shellSurface(shellSurface, shellHairline, ShellMetrics.cardShape)
                .padding(ShellMetrics.surfacePadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = phoneText("android.push.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = ComfortaaBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(phoneText("android.setup.welcome.solstone"), style = MaterialTheme.typography.bodyLarge)
            Text(
                phoneText("android.setup.solstone.app.takes.what.share"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                phoneText("android.setup.journal.always.private.only.yours"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        PhoneJournalSetup(
            onSetOneUp = onSetOneUp,
            onSendLink = onSendLink,
            onAlreadyHaveOne = onAlreadyHaveOne,
        )
    }
}

/**
 * Active home-tile presentation for `import` using worst-signal-wins precedence:
 * `needs attention` > `ready to review` > `sending` > `waiting`.
 * Null when idle (quiet `import` / `photos and files`).
 */
internal data class ImportTileActiveSummary(
    val marker: ImportMarkerKind,
    val stateWord: String,
    val subLine: String,
)

internal fun importTileActiveSummary(state: PhoneImportPaneState): ImportTileActiveSummary? {
    if (state.rows.isEmpty()) return null
    val attentionRows = state.rows.filter {
        it.kind == ImportRowKind.REFUSED ||
            it.kind == ImportRowKind.MISSING_COPY ||
            it.kind == ImportRowKind.REVIEW_EXPIRED
    }
    val queueFaultHold = state.recent in setOf(
        ImportRecentState.PAIRING_REFUSED,
        ImportRecentState.PAIRING_INCOMPLETE,
        ImportRecentState.JOURNAL_TOO_OLD,
    )
    if (attentionRows.isNotEmpty() || queueFaultHold) {
        val sub = when {
            attentionRows.size == 1 -> attentionRows.single().name
            attentionRows.size > 1 -> phoneText("android.import.tile.attention.count", "count" to attentionRows.size)
            else -> recentText(state.recent)
        }
        return ImportTileActiveSummary(
            marker = ImportMarkerKind.NEEDS_ATTENTION,
            stateWord = phoneText("android.push.needs.attention"),
            subLine = sub,
        )
    }
    val reviewRows = state.rows.filter { it.kind == ImportRowKind.READY_TO_REVIEW }
    if (reviewRows.isNotEmpty()) {
        val sub = if (reviewRows.size == 1) {
            reviewRows.single().name
        } else {
            phoneText("android.import.tile.ready.count", "count" to reviewRows.size)
        }
        return ImportTileActiveSummary(
            marker = ImportMarkerKind.READY_TO_REVIEW,
            stateWord = phoneText("android.import.tile.ready.to.review"),
            subLine = sub,
        )
    }
    val sendingRows = state.rows.filter { it.kind == ImportRowKind.SENDING }
    if (sendingRows.isNotEmpty()) {
        val sub = if (sendingRows.size == 1) {
            sendingRows.single().name
        } else {
            phoneText("android.import.tile.sending.count", "count" to sendingRows.size)
        }
        return ImportTileActiveSummary(
            marker = ImportMarkerKind.SENDING,
            stateWord = phoneText("android.import.sending"),
            subLine = sub,
        )
    }
    val waitingRows = state.rows.filter { it.kind == ImportRowKind.WAITING || it.kind == ImportRowKind.GOING_AGAIN }
    if (waitingRows.isNotEmpty()) {
        val waitingForJournal = state.offline || state.recent in setOf(
            ImportRecentState.UNPAIRED,
            ImportRecentState.AWAITING_CONFIRMATION,
        )
        val sub = when {
            waitingForJournal -> phoneText("android.import.tile.waiting.journal")
            waitingRows.size == 1 -> waitingRows.single().name
            else -> phoneText("android.import.tile.waiting.count", "count" to waitingRows.size)
        }
        return ImportTileActiveSummary(
            marker = ImportMarkerKind.WAITING,
            stateWord = phoneText("android.import.waiting"),
            subLine = sub,
        )
    }
    return null
}

/**
 * Home `import` tile: solid tile, quiet when idle (`import` / `photos and files`, no switch, no
 * `on`/`off`), worst-signal-wins when active (`needs attention` > `ready to review` > `sending` >
 * `waiting`). Carries `Role.Button` always and `stateDescription` when active.
 */
@Composable
private fun PhoneImportHomeTile(
    state: PhoneImportPaneState,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
) {
    val active = importTileActiveSummary(state)
    val label = phoneText("android.setup.import")
    val iconTint = when (active?.marker) {
        ImportMarkerKind.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
        ImportMarkerKind.READY_TO_REVIEW, ImportMarkerKind.SENDING -> MaterialTheme.colorScheme.outline
        else -> shellSecondaryInk
    }
    PhoneTileSurface(
        modifier = Modifier
            .semantics {
                role = Role.Button
                collectionItemInfo = CollectionItemInfo(index, count, 0, 1)
                if (active != null) {
                    stateDescription = phoneText(
                        "android.source.statecopysubline",
                        "statecopy" to active.stateWord,
                        "subline" to active.subLine,
                    )
                }
            }
            .testTag("importTile"),
        onClick = onOpen,
        dashed = false,
    ) {
        Box(Modifier.size(0.dp).testTag("importTile-solid"))
        Icon(
            painter = painterResource(R.drawable.phone_import),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        WholeWordText(
            text = label,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (active == null) {
            WholeWordText(
                text = phoneText("android.setup.photos.files"),
                style = MaterialTheme.typography.bodySmall,
                color = shellSecondaryInk,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ImportStateMarker(kind = active.marker)
                Spacer(Modifier.width(8.dp))
                WholeWordText(
                    text = active.stateWord,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (active.marker) {
                        ImportMarkerKind.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
                        ImportMarkerKind.READY_TO_REVIEW -> MaterialTheme.colorScheme.outline
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Text(
                text = active.subLine,
                style = MaterialTheme.typography.bodySmall,
                color = shellSecondaryInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * `add more` and non-switch destination tiles.
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
    dashed: Boolean = true,
) {
    PhoneTileSurface(
        modifier = Modifier
            .semantics {
                role = Role.Button
                collectionItemInfo = CollectionItemInfo(index, count, 0, 1)
            }
            .testTag(testTag),
        onClick = onOpen,
        dashed = dashed,
    ) {
        Box(Modifier.size(0.dp).testTag(if (dashed) "$testTag-dashed" else "$testTag-solid"))
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = shellSecondaryInk,
            modifier = Modifier.size(22.dp),
        )
        WholeWordText(
            text = label,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        WholeWordText(
            text = subLine,
            style = MaterialTheme.typography.bodySmall,
            color = shellSecondaryInk,
        )
    }
}
