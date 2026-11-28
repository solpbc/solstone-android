// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.folders.FacetOption
import app.solstone.core.folders.FacetsAvailability
import app.solstone.core.folders.FileKind
import app.solstone.core.folders.FolderKinds
import app.solstone.core.folders.FolderMode
import app.solstone.core.folders.spanStartMs
import app.solstone.core.folders.FolderSpan
import app.solstone.core.folders.FolderSummary
import app.solstone.core.folders.OverlapTarget
import app.solstone.core.folders.PreviewSummary
import app.solstone.core.i18n.phoneText
import app.solstone.core.model.SourceState

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.ZoneId

sealed class PhoneFoldersPickerNotice {
    data object None : PhoneFoldersPickerNotice()
    data class AlreadyAdded(val folderId: String, val folderName: String) : PhoneFoldersPickerNotice()
    data class ProviderRefused(val folderName: String?) : PhoneFoldersPickerNotice()
}

data class PhoneFoldersState(
    val folders: List<FolderSummary> = emptyList(),
    val facets: List<FacetOption> = emptyList(),
    val facetsState: FacetsAvailability = FacetsAvailability.LOADING,
    val capability: Boolean? = null,
    val pickerNotice: PhoneFoldersPickerNotice = PhoneFoldersPickerNotice.None,
    val checking: Boolean = false,
    val pendingCount: Int = 0,
    val newlyAddedFolderId: String? = null,
    val paired: Boolean = false,
    val offline: Boolean = false,
    val backgroundCheckProven: Boolean = false,
)

data class PhoneFoldersBinding(
    val state: PhoneFoldersState = PhoneFoldersState(),
    val onAddFolder: () -> Unit = {},
    val onRegrantFolder: (String) -> Unit = {},
    val onSelectSpan: (String, FolderSpan) -> Unit = { _, _ -> },
    val onRefreshPreview: (String, FolderSpan) -> Unit = { _, _ -> },
    val onSetKinds: (String, FolderKinds) -> Unit = { _, _ -> },
    val onSetFacet: (String, FacetOption?) -> Unit = { _, _ -> },
    val onConfirm: (String, FolderSpan) -> Unit = { _, _ -> },
    val onPause: (String) -> Unit = {},
    val onResume: (String) -> Unit = {},
    val onTurnOff: (String) -> Unit = {},
    val onTurnOn: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onCheckNow: (String?) -> Unit = {},
    val onClearPickerNotice: () -> Unit = {},
    val onConsumeNewlyAdded: () -> Unit = {},
)

val LocalPhoneFolders = staticCompositionLocalOf { PhoneFoldersBinding() }

/** Maps one folder summary onto the canonical source state vocabulary. */
fun folderSummaryState(folder: FolderSummary): SourceState = when {
    folder.attention != null -> SourceState.NEEDS_ATTENTION
    folder.mode == FolderMode.SETTING_UP -> SourceState.SETTING_UP
    folder.mode == FolderMode.PAUSED || folder.pausedByApp -> SourceState.PAUSED
    folder.mode == FolderMode.ON -> SourceState.ON
    else -> SourceState.OFF
}

/** Worst-signal-wins rollup for the single `folders` tile on Home. */
fun foldersTileState(state: PhoneFoldersState): SourceState {
    if (state.folders.isEmpty()) return SourceState.READY_TO_SET_UP
    val states = state.folders.map(::folderSummaryState)
    return when {
        SourceState.NEEDS_ATTENTION in states -> SourceState.NEEDS_ATTENTION
        SourceState.SETTING_UP in states -> SourceState.SETTING_UP
        SourceState.ON in states -> SourceState.ON
        SourceState.PAUSED in states -> SourceState.PAUSED
        else -> SourceState.OFF
    }
}

fun foldersSourceSubLine(state: PhoneFoldersState, paired: Boolean): String? {
    if (state.folders.isEmpty()) return null
    val attention = state.folders.filter { it.attention != null }
    if (attention.isNotEmpty()) {
        return if (attention.size >= 2) {
            phoneText("android.folders.tile.attention.many", "count" to attention.size)
        } else {
            phoneText("android.folders.android.cant.read.tile", "folder" to attention.single().name)
        }
    }
    val settingUp = state.folders.firstOrNull { it.mode == FolderMode.SETTING_UP }
    if (settingUp != null) {
        return phoneText("android.folders.finish.setup", "folder" to settingUp.name)
    }
    val onFolders = state.folders.filter { it.mode == FolderMode.ON && !it.pausedByApp }
    val pausedFolders = state.folders.filter { it.mode == FolderMode.PAUSED || (it.mode == FolderMode.ON && it.pausedByApp) }
    if (onFolders.isNotEmpty()) {
        if (!paired) return phoneText("android.folders.unpaired")
        if (state.capability == false) return phoneText("android.folders.journal.update")
        val backfilling = onFolders.firstOrNull { it.backfillTotal > 0L && it.backfillDone < it.backfillTotal }
        if (backfilling != null) {
            return phoneText(
                "android.folders.tile.backfill",
                "folder" to backfilling.name,
                "done" to backfilling.backfillDone,
                "total" to backfilling.backfillTotal,
            )
        }
        if (pausedFolders.isNotEmpty()) {
            return phoneText("android.folders.tile.split", "on" to onFolders.size, "paused" to pausedFolders.size)
        }
        val waiting = onFolders.sumOf { it.waiting }
        if (waiting > 0) {
            return phoneText("android.folders.tile.waiting", "count" to waiting)
        }
        return when (onFolders.size) {
            1 -> onFolders.single().name
            2 -> phoneText("android.folders.tile.two", "first" to onFolders[0].name, "second" to onFolders[1].name)
            else -> phoneText("android.folders.tile.many", "count" to onFolders.size)
        }
    }
    if (pausedFolders.isNotEmpty()) {
        return phoneText("android.folders.paused.reason")
    }
    return phoneText("android.folders.off.reason")
}

private fun overlapDescription(target: OverlapTarget): String = when (target) {
    is OverlapTarget.Obsidian -> phoneText("android.folders.overlap.obsidian", "name" to target.name)
    is OverlapTarget.Screenshots -> phoneText("android.folders.overlap.screenshots", "name" to target.name)
    is OverlapTarget.Folder -> phoneText("android.folders.overlap.folder", "name" to target.name)
}

private fun spanChoices(): List<Pair<FolderSpan, String>> = listOf(
    FolderSpan.LAST_30_DAYS to phoneText("android.screenshots.last30days"),
    FolderSpan.LAST_6_MONTHS to phoneText("android.screenshots.last6months"),
    FolderSpan.LAST_YEAR to phoneText("android.screenshots.lastyear"),
    FolderSpan.ALL to phoneText("android.screenshots.all"),
    FolderSpan.TODAY to phoneText("android.screenshots.nothingbeforetoday"),
)

@Composable
internal fun PhoneFoldersListPane(
    onOpenFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val binding = LocalPhoneFolders.current
    val state = binding.state

    LaunchedEffect(state.newlyAddedFolderId) {
        val added = state.newlyAddedFolderId
        if (added != null) {
            binding.onConsumeNewlyAdded()
            onOpenFolder(added)
        }
    }

    PhonePaneScaffold(
        modifier
            .semantics { paneTitle = spokenPaneTitle(PhoneRoute.SourceDetail("folders")) }
            .testTag("foldersListPane"),
    ) {
        SourceVerdict(foldersTileState(state), Modifier.padding(top = ShellMetrics.sectionGap))

        if (state.folders.isEmpty()) {
            PaneNote(phoneText("android.folders.intro"))
        }

        when (val notice = state.pickerNotice) {
            PhoneFoldersPickerNotice.None -> Unit
            is PhoneFoldersPickerNotice.AlreadyAdded -> {
                Spacer(Modifier.height(ShellMetrics.sectionSpacing))
                PaneCard(Modifier.testTag("foldersAlreadyAddedNotice")) {
                    Column(Modifier.padding(ShellMetrics.surfacePadding)) {
                        Text(
                            text = phoneText("android.folders.already.added", "folder" to notice.folderName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        TextButton(
                            onClick = {
                                binding.onClearPickerNotice()
                                onOpenFolder(notice.folderId)
                            },
                            modifier = Modifier.testTag("foldersOpenExisting"),
                        ) {
                            Text(phoneText("android.folders.open.folder", "folder" to notice.folderName))
                        }
                    }
                }
            }
            is PhoneFoldersPickerNotice.ProviderRefused -> {
                Spacer(Modifier.height(ShellMetrics.sectionSpacing))
                PaneCard(Modifier.testTag("foldersProviderRefusedNotice")) {
                    Column(Modifier.padding(ShellMetrics.surfacePadding)) {
                        Text(
                            text = if (notice.folderName == null) {
                                phoneText("android.folders.error.storage.refused")
                            } else {
                                phoneText("android.folders.error.cant.open")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = {
                                binding.onClearPickerNotice()
                                binding.onAddFolder()
                            },
                        ) {
                            Text(phoneText("android.folders.choose.another"))
                        }
                    }
                }
            }
        }

        if (state.folders.isNotEmpty()) {
            Spacer(Modifier.height(ShellMetrics.sectionSpacing))
            PaneCard(Modifier.testTag("foldersListCard")) {
                state.folders.forEachIndexed { index, folder ->
                    if (index > 0) PaneRowDivider()
                    val rowState = folderSummaryState(folder)
                    val rowSub = buildFolderRowSubLine(folder, rowState, state.paired, state.capability)
                    PaneNavRow(
                        label = folder.name,
                        subLine = rowSub,
                        subLineMarker = { PhoneTileDot(rowState) },
                        onClick = { onOpenFolder(folder.id) },
                        modifier = Modifier.testTag("folderRow-${folder.id}"),
                    )
                }
            }
        }

        Spacer(Modifier.height(ShellMetrics.sectionGap))
        Button(
            onClick = binding.onAddFolder,
            modifier = Modifier.testTag("foldersAddButton"),
        ) {
            Text(phoneText("android.folders.add"))
        }
        PaneNote(phoneText("android.folders.android.download.helper"))

        val anyOn = state.folders.any { it.mode == FolderMode.ON && it.attention == null && !it.pausedByApp }
        if (anyOn) {
            Spacer(Modifier.height(ShellMetrics.sectionSpacing))
            TextButton(
                onClick = { binding.onCheckNow(null) },
                enabled = !state.checking,
                modifier = Modifier.testTag("foldersCheckAllNow"),
            ) {
                Text(phoneText("android.folders.check.now"))
            }
        }
    }
}

private fun buildFolderRowSubLine(
    folder: FolderSummary,
    rowState: SourceState,
    paired: Boolean,
    capability: Boolean?,
): String {
    val stateWord = sourceStateCopy(rowState)
    val facet = folder.facet
    val detail = when {
        folder.attention != null -> phoneText("android.folders.android.cant.read.tile", "folder" to folder.name)
        folder.mode == FolderMode.SETTING_UP -> null
        folder.mode == FolderMode.ON && !paired -> phoneText("android.folders.unpaired")
        folder.mode == FolderMode.ON && capability == false -> phoneText("android.folders.journal.update")
        folder.mode == FolderMode.ON && folder.backfillTotal > 0L && folder.backfillDone < folder.backfillTotal ->
            phoneText("android.folders.page.backfill", "done" to folder.backfillDone, "total" to folder.backfillTotal)
        folder.mode == FolderMode.ON && folder.waiting > 0 ->
            phoneText("android.folders.tile.waiting", "count" to folder.waiting)
        facet != null -> facet.title
        else -> null
    }
    return if (detail != null) {
        phoneText("android.source.shared.pair", "first" to stateWord, "second" to detail)
    } else {
        stateWord
    }
}

@Composable
internal fun PhoneFolderDetailPane(
    folderId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val binding = LocalPhoneFolders.current
    val state = binding.state
    val folder = state.folders.firstOrNull { it.id == folderId }

    LaunchedEffect(folder == null) {
        if (folder == null) onBack()
    }
    if (folder == null) return

    val now = LocalPhoneStatusNow.current.nowEpochMs()
    val zone = ZoneId.systemDefault()
    val verdict = folderSummaryState(folder)
    var showKindsError by remember(folder.id) { mutableStateOf(false) }
    var overlapOverridden by remember(folder.id) { mutableStateOf(false) }
    var showRemoveDialog by remember(folder.id) { mutableStateOf(false) }
    var bringOlderOpen by remember(folder.id) { mutableStateOf(false) }

    PhonePaneScaffold(
        modifier
            .semantics { paneTitle = folder.name }
            .testTag("folderDetailPane-${folder.id}"),
    ) {
        SourceVerdict(verdict, Modifier.padding(top = ShellMetrics.sectionGap))
        PaneNote(phoneText("android.folders.detail.intro", "folder" to folder.name))

        // Lost grant / missing / unreadable attention recovery (AN5)
        if (folder.attention != null) {
            Spacer(Modifier.height(ShellMetrics.sectionSpacing))
            PaneCard(Modifier.testTag("folderAttentionCard")) {
                Column(Modifier.padding(ShellMetrics.surfacePadding)) {
                    Text(
                        text = phoneText("android.folders.android.cant.read.page", "folder" to folder.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = { binding.onRegrantFolder(folder.id) },
                        modifier = Modifier.testTag("folderRegrantButton"),
                    ) {
                        Text(phoneText("android.folders.choose.this"))
                    }
                }
            }
        }

        // Overlap warning during setup (O1-O4)
        val overlap = folder.overlap
        if (folder.mode == FolderMode.SETTING_UP && overlap != null && !overlapOverridden) {
            Spacer(Modifier.height(ShellMetrics.sectionSpacing))
            PaneCard(Modifier.testTag("folderOverlapWarning")) {
                Column(
                    Modifier.padding(ShellMetrics.surfacePadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = phoneText("android.folders.overlap.heading", "other" to overlapDescription(overlap)),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = phoneText("android.folders.overlap.body"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = shellSecondaryInk,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                binding.onRemove(folder.id)
                                binding.onAddFolder()
                            },
                            modifier = Modifier.testTag("folderOverlapChooseAnother"),
                        ) {
                            Text(phoneText("android.folders.choose.another"))
                        }
                        TextButton(
                            onClick = { overlapOverridden = true },
                            modifier = Modifier.testTag("folderOverlapOverride"),
                        ) {
                            Text(phoneText("android.folders.overlap.override"))
                        }
                    }
                }
            }
        }

        // Mode-specific body
        if (folder.mode == FolderMode.SETTING_UP) {
            FolderKindsSection(
                folder = folder,
                isEditingActive = false,
                showValidationError = showKindsError,
                onChange = { next ->
                    if (!next.anySelected()) {
                        showKindsError = true
                    } else {
                        showKindsError = false
                        binding.onSetKinds(folder.id, next)
                    }
                },
            )

            FolderFacetSection(
                folder = folder,
                facets = state.facets,
                facetsState = state.facetsState,
                paired = state.paired,
                offline = state.offline,
                isSetup = true,
                onSelectFacet = { binding.onSetFacet(folder.id, it) },
            )

            FolderSpanSection(
                selectedSpan = folder.selectedSpan,
                returning = folder.returning,
                onSelectSpan = { binding.onSelectSpan(folder.id, it) },
            )

            FolderPreviewCard(
                preview = folder.preview,
                paired = state.paired,
                offline = state.offline,
                nowEpochMs = now,
                zone = zone,
                onRetry = { binding.onRefreshPreview(folder.id, folder.selectedSpan) },
            )

            if (!folder.returning) {
                PaneNote(phoneText("android.folders.preview.before.confirm"))
            }

            // AN4 honesty line while setting up
            PaneNote(
                if (state.backgroundCheckProven) {
                    phoneText("android.folders.android.bg.proven")
                } else {
                    phoneText("android.folders.android.bg.default")
                },
            )

            Spacer(Modifier.height(ShellMetrics.sectionSpacing))
            val canConfirm = folder.kinds.anySelected() && (overlap == null || overlapOverridden)
            val confirmText = confirmButtonLabel(folder.selectedSpan, folder.preview)
            Button(
                onClick = { binding.onConfirm(folder.id, folder.selectedSpan) },
                enabled = canConfirm,
                modifier = Modifier.testTag("folderConfirmButton"),
            ) {
                Text(confirmText)
            }
        } else {
            // Steady-state status notes
            when {
                folder.mode == FolderMode.PAUSED || folder.pausedByApp ->
                    PaneNote(phoneText("android.folders.paused.reason"))
                folder.mode == FolderMode.OFF ->
                    PaneNote(phoneText("android.folders.off.reason"))
                !state.paired ->
                    PaneNote(phoneText("android.folders.unpaired"))
                state.capability == false ->
                    PaneNote(phoneText("android.folders.journal.update"))
                folder.backfillTotal > 0L && folder.backfillDone < folder.backfillTotal -> {
                    PaneNote(
                        phoneText(
                            "android.folders.page.backfill",
                            "done" to folder.backfillDone,
                            "total" to folder.backfillTotal,
                        ),
                    )
                    PaneNote(phoneText("android.folders.backfill.note"))
                }
            }

            // Honesty line while ON only (omitted while paused or off per AN3/AN4)
            if (folder.mode == FolderMode.ON && !folder.pausedByApp && folder.attention == null) {
                PaneNote(
                    if (state.backgroundCheckProven) {
                        phoneText("android.folders.android.bg.proven")
                    } else {
                        phoneText("android.folders.android.bg.default")
                    },
                )
            }

            // Non-fatal file-level notices (E3, E4)
            if (folder.cantRead > 0L) {
                Spacer(Modifier.height(ShellMetrics.sectionSpacing))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = phoneText("android.folders.error.cant.read.files", "count" to folder.cantRead),
                        style = MaterialTheme.typography.bodySmall,
                        color = shellSecondaryInk,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { binding.onCheckNow(folder.id) }) {
                        Text(phoneText("android.migration.try.again"))
                    }
                }
            }
            if (folder.fallbackToDetails > 0L) {
                PaneNote(phoneText("android.folders.error.fallback.details", "count" to folder.fallbackToDetails))
            }

            // Controls row
            Spacer(Modifier.height(ShellMetrics.sectionSpacing))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (folder.mode) {
                    FolderMode.ON -> {
                        OutlinedButton(
                            onClick = { binding.onPause(folder.id) },
                            modifier = Modifier.testTag("folderPauseButton"),
                        ) { Text(phoneText("android.screenshots.pause")) }
                        TextButton(
                            onClick = { binding.onTurnOff(folder.id) },
                            modifier = Modifier.testTag("folderTurnOffButton"),
                        ) { Text(phoneText("android.folders.turn.off")) }
                        TextButton(
                            onClick = { binding.onCheckNow(folder.id) },
                            enabled = !state.checking,
                            modifier = Modifier.testTag("folderCheckNowButton"),
                        ) { Text(phoneText("android.folders.check.now")) }
                    }
                    FolderMode.PAUSED -> {
                        Button(
                            onClick = { binding.onResume(folder.id) },
                            modifier = Modifier.testTag("folderResumeButton"),
                        ) { Text(phoneText("android.screenshots.resume")) }
                        TextButton(
                            onClick = { binding.onTurnOff(folder.id) },
                            modifier = Modifier.testTag("folderTurnOffButton"),
                        ) { Text(phoneText("android.folders.turn.off")) }
                    }
                    FolderMode.OFF -> {
                        Button(
                            onClick = { binding.onTurnOn(folder.id) },
                            modifier = Modifier.testTag("folderTurnOnButton"),
                        ) { Text(phoneText("android.folders.turn.on")) }
                    }
                    FolderMode.SETTING_UP -> Unit
                }
            }

            // Bring in older files (PV15)
            if (folder.mode == FolderMode.ON && folder.attention == null) {
                Spacer(Modifier.height(ShellMetrics.sectionSpacing))
                if (!bringOlderOpen) {
                    TextButton(
                        onClick = {
                            bringOlderOpen = true
                            binding.onRefreshPreview(folder.id, folder.selectedSpan)
                        },
                        modifier = Modifier.testTag("folderBringOlderButton"),
                    ) {
                        Text(phoneText("android.folders.bring.older"))
                    }
                } else {
                    FolderSpanSection(
                        selectedSpan = folder.selectedSpan,
                        returning = true,
                        onSelectSpan = { binding.onSelectSpan(folder.id, it) },
                    )
                    FolderPreviewCard(
                        preview = folder.preview,
                        paired = state.paired,
                        offline = state.offline,
                        nowEpochMs = now,
                        zone = zone,
                        onRetry = { binding.onRefreshPreview(folder.id, folder.selectedSpan) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                bringOlderOpen = false
                                binding.onConfirm(folder.id, folder.selectedSpan)
                            },
                            modifier = Modifier.testTag("folderBringOlderConfirm"),
                        ) {
                            Text(confirmButtonLabel(folder.selectedSpan, folder.preview))
                        }
                        TextButton(onClick = { bringOlderOpen = false }) {
                            Text(phoneText("android.phone.cancel"))
                        }
                    }
                }
            }

            // Editable kinds & facet
            FolderKindsSection(
                folder = folder,
                isEditingActive = true,
                showValidationError = showKindsError,
                onChange = { next ->
                    if (!next.anySelected()) {
                        showKindsError = true
                    } else {
                        showKindsError = false
                        binding.onSetKinds(folder.id, next)
                    }
                },
            )

            FolderFacetSection(
                folder = folder,
                facets = state.facets,
                facetsState = state.facetsState,
                paired = state.paired,
                offline = state.offline,
                isSetup = false,
                onSelectFacet = { binding.onSetFacet(folder.id, it) },
            )

            // Facts card
            PaneSectionTitle(phoneText("android.screenshots.folder"))
            PaneCard {
                PaneFactRow(
                    label = phoneText("android.screenshots.folder"),
                    value = folder.label,
                )
                if (overlap != null) {
                    PaneRowDivider()
                    PaneFactRow(
                        label = phoneText("android.folders.overlap.fact", "other" to overlapDescription(overlap)),
                        value = "",
                    )
                }
                PaneRowDivider()
                PaneFactRow(
                    label = phoneText("android.intake.sofar"),
                    value = if (folder.itemsTakenIn > 0L) {
                        phoneText("android.folders.items.count", "count" to folder.itemsTakenIn)
                    } else {
                        phoneText("android.intake.nothingyet")
                    },
                )
                folder.lastTakenInMs?.let { takenMs ->
                    PaneRowDivider()
                    PaneFactRow(
                        label = phoneText("android.intake.lasttaken"),
                        value = formatRelative(takenMs, now, zone),
                    )
                }
                folder.lastCheckedMs?.let { checkedMs ->
                    PaneRowDivider()
                    PaneFactRow(
                        label = phoneText("android.obsidian.lastchecked"),
                        value = formatRelative(checkedMs, now, zone),
                    )
                }
                if (folder.waiting > 0) {
                    PaneRowDivider()
                    PaneFactRow(
                        label = phoneText("android.screenshots.waitinggojournal"),
                        value = folder.waiting.toString(),
                    )
                }
            }

            PaneNote(phoneText("android.folders.fact.stays"))
            PaneNote(
                if (folder.kinds.other) {
                    phoneText("android.folders.fact.other.on")
                } else {
                    phoneText("android.folders.fact.other.off")
                },
            )
        }

        // Remove folder footer (F22 / F23)
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        TextButton(
            onClick = { showRemoveDialog = true },
            modifier = Modifier.testTag("folderRemoveButton"),
        ) {
            Text(phoneText("android.folders.remove"), color = MaterialTheme.colorScheme.error)
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(phoneText("android.folders.remove.title", "folder" to folder.name)) },
            text = { Text(phoneText("android.folders.remove.body", "folder" to folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        binding.onRemove(folder.id)
                        onBack()
                    },
                    modifier = Modifier.testTag("folderRemoveConfirm"),
                ) {
                    Text(phoneText("android.folders.remove"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(phoneText("android.phone.cancel"))
                }
            },
        )
    }
}

@Composable
private fun FolderKindsSection(
    folder: FolderSummary,
    isEditingActive: Boolean,
    showValidationError: Boolean,
    onChange: (FolderKinds) -> Unit,
) {
    PaneSectionTitle(phoneText("android.folders.kinds.heading"))
    val rows = listOf(
        Triple(FileKind.NOTE, phoneText("android.folders.kind.notes"), phoneText("android.folders.kind.notes.desc")),
        Triple(FileKind.DOCUMENT, phoneText("android.folders.kind.documents"), phoneText("android.folders.kind.documents.desc")),
        Triple(FileKind.TRANSCRIPT, phoneText("android.folders.kind.transcripts"), phoneText("android.folders.kind.transcripts.desc")),
        Triple(FileKind.SCREENSHOT, phoneText("android.folders.kind.screenshots"), phoneText("android.folders.kind.screenshots.desc")),
        Triple(FileKind.PHOTO, phoneText("android.folders.kind.photos"), phoneText("android.folders.kind.photos.desc")),
        Triple(FileKind.OTHER, phoneText("android.folders.kind.other"), phoneText("android.folders.kind.other.desc")),
    )
    PaneCard(Modifier.testTag("folderKindsCard")) {
        rows.forEachIndexed { index, (kind, label, desc) ->
            if (index > 0) PaneRowDivider()
            PaneSwitchRow(
                label = label,
                subLine = desc,
                checked = folder.kinds.enabledFor(kind),
                onCheckedChange = { checked -> onChange(folder.kinds.withKind(kind, checked)) },
                switchModifier = Modifier.testTag("folderKindSwitch-${kind.wireName}"),
            )
        }
    }
    if (showValidationError || !folder.kinds.anySelected()) {
        Text(
            text = phoneText("android.folders.kinds.validation"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(top = ShellMetrics.sectionSpacing)
                .testTag("folderKindsValidation"),
        )
    }
    if (isEditingActive) {
        PaneNote(phoneText("android.folders.kinds.edit.note"))
    }
}

@Composable
private fun FolderFacetSection(
    folder: FolderSummary,
    facets: List<FacetOption>,
    facetsState: FacetsAvailability,
    paired: Boolean,
    offline: Boolean,
    isSetup: Boolean,
    onSelectFacet: (FacetOption?) -> Unit,
) {
    PaneSectionTitle(phoneText("android.folders.facet.heading"))
    if (!paired) {
        PaneNote(phoneText("android.folders.facet.unpaired"))
        return
    }
    if (offline && facets.isEmpty()) {
        PaneNote(phoneText("android.folders.facet.offline"))
        return
    }
    if (facets.isEmpty() && facetsState == FacetsAvailability.UNAVAILABLE) {
        PaneNote(phoneText("android.folders.facet.offline"))
        return
    }
    PaneCard(Modifier.testTag("folderFacetCard")) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = folder.facet == null,
                    role = Role.RadioButton,
                    onClick = { onSelectFacet(null) },
                )
                .padding(horizontal = ShellMetrics.surfacePadding, vertical = 6.dp)
                .testTag("folderFacet-none"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = folder.facet == null, onClick = null)
            Text(
                text = phoneText("android.folders.facet.none"),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        facets.forEach { facet ->
            PaneRowDivider()
            val selected = folder.facet?.name == facet.name
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelectFacet(facet) },
                    )
                    .padding(horizontal = ShellMetrics.surfacePadding, vertical = 6.dp)
                    .testTag("folderFacet-${facet.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    text = facet.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    val chosen = folder.facet
    PaneNote(
        when {
            chosen == null -> phoneText("android.folders.facet.none.help")
            isSetup -> phoneText("android.folders.facet.setup.help", "facet" to chosen.title)
            else -> phoneText("android.folders.facet.active.help", "facet" to chosen.title)
        },
    )
}

@Composable
private fun FolderSpanSection(
    selectedSpan: FolderSpan,
    returning: Boolean,
    onSelectSpan: (FolderSpan) -> Unit,
) {
    PaneSectionTitle(phoneText("android.screenshots.beforetoday"))
    PaneCard(Modifier.testTag("folderSpanCard")) {
        spanChoices().forEachIndexed { index, (span, label) ->
            if (index > 0) PaneRowDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedSpan == span,
                        role = Role.RadioButton,
                        onClick = { onSelectSpan(span) },
                    )
                    .padding(horizontal = ShellMetrics.surfacePadding, vertical = 6.dp)
                    .testTag("folderSpan-${span.wire}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedSpan == span, onClick = null)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    if (returning) {
        PaneNote(phoneText("android.folders.preview.already.note"))
    }
}

@Composable
private fun FolderPreviewCard(
    preview: PreviewSummary?,
    paired: Boolean,
    offline: Boolean,
    nowEpochMs: Long,
    zone: ZoneId,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    PaneSectionTitle(phoneText("android.folders.preview.heading"))
    PaneCard(Modifier.testTag("folderPreviewCard")) {
        Column(
            Modifier.padding(ShellMetrics.surfacePadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (preview == null) {
                Text(
                    text = phoneText("android.folders.preview.checking"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = shellSecondaryInk,
                )
            } else if (preview.failed) {
                Text(
                    text = phoneText("android.folders.preview.failed"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) {
                    Text(phoneText("android.migration.try.again"))
                }
            } else {
                val itemsText = phoneText("android.folders.items.count", "count" to preview.totalItems)
                val oldestMs = if (preview.span == FolderSpan.ALL) preview.oldestMs else spanStartMs(preview.span, nowEpochMs, zone)
                val newestMs = if (preview.span == FolderSpan.ALL) preview.newestMs else nowEpochMs
                val header = if (oldestMs != null && newestMs != null) {
                    phoneText(
                        "android.folders.preview.header.range",
                        "items" to itemsText,
                        "start" to formatStarted(oldestMs, nowEpochMs, zone),
                        "end" to formatStarted(newestMs, nowEpochMs, zone),
                    )
                } else {
                    itemsText
                }
                Text(
                    text = header,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                val sizeText = formatPhoneByteSize(preview.contentBytes, context)
                val breakdown = when {
                    preview.contentItems > 0L && preview.metadataOnlyItems > 0L -> phoneText(
                        "android.folders.preview.both",
                        "content" to phoneText("android.folders.preview.content.only", "count" to preview.contentItems, "size" to sizeText),
                        "meta" to phoneText("android.folders.preview.meta.only", "count" to preview.metadataOnlyItems),
                    )
                    preview.contentItems > 0L ->
                        phoneText("android.folders.preview.content.only", "count" to preview.contentItems, "size" to sizeText)
                    preview.metadataOnlyItems > 0L ->
                        phoneText("android.folders.preview.meta.only", "count" to preview.metadataOnlyItems)
                    else -> null
                }
                if (breakdown != null) {
                    Text(text = breakdown, style = MaterialTheme.typography.bodySmall, color = shellSecondaryInk)
                }

                val est = preview.estimate
                if (!paired) {
                    Text(
                        text = phoneText("android.folders.preview.unpaired"),
                        style = MaterialTheme.typography.bodySmall,
                        color = shellSecondaryInk,
                    )
                } else if (offline && est?.fromJournal != true) {
                    Text(
                        text = phoneText("android.folders.preview.offline"),
                        style = MaterialTheme.typography.bodySmall,
                        color = shellSecondaryInk,
                    )
                } else if (est != null) {
                    Text(
                        text = phoneText("android.folders.preview.work.label"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = phoneText(
                            "android.folders.preview.estimate.line",
                            "segments" to phoneText("android.folders.preview.segments", "count" to est.segments),
                            "days" to phoneText("android.folders.preview.days", "count" to est.days),
                            "thoughts" to phoneText("android.folders.preview.thoughts", "count" to est.thoughts),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("folderEstimateLine"),
                    )
                    Text(
                        text = phoneText("android.folders.preview.gloss"),
                        style = MaterialTheme.typography.bodySmall,
                        color = shellSecondaryInk,
                    )
                }
            }
        }
    }
    PaneNote(phoneText("android.folders.preview.scope"))
}

private fun confirmButtonLabel(span: FolderSpan, preview: PreviewSummary?): String {
    if (span == FolderSpan.TODAY) return phoneText("android.folders.confirm.start.today")
    val count = preview?.takeIf { !it.failed }?.totalItems ?: 0L
    if (count <= 0L) return phoneText("android.folders.confirm.start.today")
    return phoneText("android.folders.confirm.bring.in", "count" to count)
}
