// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the import pane's recent line says, decided from the store and the pairing. */
enum class ImportRecentState {
    UNPAIRED,
    AWAITING_CONFIRMATION,
    PAIRING_REFUSED,
    PAIRING_INCOMPLETE,
    CHECKING_JOURNAL,
    JOURNAL_TOO_OLD,
    SENDING,
    WAITING,

    /** Everything left needs the owner; nothing will move on its own. */
    NEEDS_YOU,

    /** Nothing left here, and the journal this device is paired with now has had an import. */
    DELIVERED,
    NOTHING_YET,
}

/**
 * Whether what's left waits on the owner rather than on sending: each item needs them, or the
 * journal needs an update first. These aren't in the status count, which only counts what can
 * still go on its own.
 */
val ImportRecentState.needsOwner: Boolean
    get() = this == ImportRecentState.NEEDS_YOU || this == ImportRecentState.JOURNAL_TOO_OLD

/** Why imports wait, as the delivery side reports it (most important first). */
enum class ImportWaiting { UNPAIRED, AWAITING_CONFIRMATION, PAIRING_REFUSED, PAIRING_INCOMPLETE, CHECKING_JOURNAL, JOURNAL_TOO_OLD, SENDING, WAITING }

/**
 * The recent line. When anything waits that can still move on its own, the waiting reason says
 * why. When all that is left needs the owner, it says so instead of "waiting". With nothing
 * left, "delivered" is said only for the journal this device is paired with now.
 */
fun importRecentState(reason: ImportWaiting, pendingCount: Int, attentionCount: Int, deliveredToCurrentJournal: Boolean): ImportRecentState =
    when {
        pendingCount > 0 -> when (reason) {
            ImportWaiting.UNPAIRED -> ImportRecentState.UNPAIRED
            ImportWaiting.AWAITING_CONFIRMATION -> ImportRecentState.AWAITING_CONFIRMATION
            ImportWaiting.PAIRING_REFUSED -> ImportRecentState.PAIRING_REFUSED
            ImportWaiting.PAIRING_INCOMPLETE -> ImportRecentState.PAIRING_INCOMPLETE
            ImportWaiting.CHECKING_JOURNAL -> ImportRecentState.CHECKING_JOURNAL
            ImportWaiting.JOURNAL_TOO_OLD -> ImportRecentState.JOURNAL_TOO_OLD
            ImportWaiting.SENDING -> ImportRecentState.SENDING
            ImportWaiting.WAITING -> ImportRecentState.WAITING
        }
        attentionCount > 0 -> ImportRecentState.NEEDS_YOU
        deliveredToCurrentJournal -> ImportRecentState.DELIVERED
        else -> ImportRecentState.NOTHING_YET
    }

enum class ImportRowKind {
    WAITING,
    SENDING,

    /** The journal it would go to now refused it; [PhoneImportRow.error] says why. */
    REFUSED,

    /** Another journal or an older version refused it; it is going again, so it reads as waiting. */
    GOING_AGAIN,

    /** The copy on this device is missing. */
    MISSING_COPY,
}

data class PhoneImportRow(
    val id: String,
    val name: String,
    val kind: ImportRowKind,
    val error: String? = null,
    /** A retry could plausibly go differently; a refusal like a file too large never does. */
    val retryable: Boolean = false,
    /** The journal already holds a staged copy, so removing it here may not keep it out. */
    val savedAtJournal: Boolean = false,
)

/** Why an item wasn't taken in, for the owner. */
enum class IntakeRefusalReason { UNSUPPORTED_TYPE, CANNOT_CONVERT, NOT_SHAREABLE, UNREADABLE, TOO_LARGE, NO_SPACE }

data class IntakeRefusalLine(val label: String, val reason: IntakeRefusalReason)

sealed interface PhoneIntakeLine {
    data class Copying(val done: Int, val total: Int) : PhoneIntakeLine
    data class Finished(val accepted: Int, val refused: List<IntakeRefusalLine>) : PhoneIntakeLine
}

data class PhoneImportPaneState(
    val recent: ImportRecentState = ImportRecentState.NOTHING_YET,
    val rows: List<PhoneImportRow> = emptyList(),
    val intake: PhoneIntakeLine? = null,
    /** An earlier share or pick was cut short before it finished. */
    val interrupted: Boolean = false,
)

class PhoneImportActions(
    val onPickPhotos: () -> Unit = {},
    val onPickFiles: () -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onDismissInterrupted: () -> Unit = {},
    /** The finished intake's result has been shown; it is cleared once the owner leaves it. */
    val onIntakeSeen: () -> Unit = {},
    val onOpenScreenshots: () -> Unit = {},
)

/** The import pane's state and actions, provided once by the activity that hosts the shell. */
data class PhoneImportBinding(
    val state: PhoneImportPaneState = PhoneImportPaneState(),
    val actions: PhoneImportActions = PhoneImportActions(),
)

val LocalPhoneImport = compositionLocalOf { PhoneImportBinding() }

internal fun refusalText(reason: IntakeRefusalReason): String =
    when (reason) {
        IntakeRefusalReason.UNSUPPORTED_TYPE -> phoneText("android.import.journal.can.t.take.kind")
        IntakeRefusalReason.CANNOT_CONVERT -> phoneText("android.import.device.can.t.convert.photo")
        IntakeRefusalReason.NOT_SHAREABLE -> phoneText("android.import.couldn.t.be.read")
        IntakeRefusalReason.UNREADABLE -> phoneText("android.import.couldn.t.be.read.add")
        IntakeRefusalReason.TOO_LARGE -> phoneText("android.import.s.larger.than.journal.takes")
        IntakeRefusalReason.NO_SPACE -> phoneText("android.import.there.isn.t.enough.room")
    }

internal fun recentText(state: ImportRecentState): String =
    when (state) {
        ImportRecentState.UNPAIRED -> phoneText("android.import.imports.wait.device.until.connect")
        ImportRecentState.AWAITING_CONFIRMATION -> phoneText("android.import.imports.wait.until.confirm.journal")
        ImportRecentState.PAIRING_REFUSED -> phoneText("android.import.journal.stopped.accepting.device.imports")
        ImportRecentState.PAIRING_INCOMPLETE -> phoneText("android.import.device.not.paired.with.journal")
        ImportRecentState.CHECKING_JOURNAL -> phoneText("android.import.checking.journal")
        ImportRecentState.JOURNAL_TOO_OLD -> phoneText("android.import.imports.wait.until.journal.updated")
        ImportRecentState.SENDING -> phoneText("android.import.sending.journal")
        ImportRecentState.WAITING -> phoneText("android.import.waiting.go.journal")
        ImportRecentState.NEEDS_YOU -> phoneText("android.import.notgoingjournalright")
        ImportRecentState.DELIVERED -> phoneText("android.import.journal")
        ImportRecentState.NOTHING_YET -> phoneText("android.import.nothing.yet")
    }

internal fun intakeText(line: PhoneIntakeLine): String =
    when (line) {
        is PhoneIntakeLine.Copying -> phoneText("android.import.saving.device.line.done.n1", "done" to (line.done + 1), "total" to (line.total))
        is PhoneIntakeLine.Finished -> if (line.accepted == 0) phoneText("android.import.nothingwassaved") else phoneText("android.phone.import.saved", "count" to (line.accepted))
    }

/** The fallback when the journal refused an item without a sentence of its own. */
internal val IMPORT_REFUSED_FALLBACK get() = phoneText("android.import.journal.couldn.t.accept")

/** `import`: what it adds, the two pickers, the recent line, and what is still on this device. */
@Composable
fun PhoneImportPane(
    modifier: Modifier = Modifier,
    state: PhoneImportPaneState = PhoneImportPaneState(),
    actions: PhoneImportActions = PhoneImportActions(),
) {
    var confirmingRemove by remember { mutableStateOf<PhoneImportRow?>(null) }
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Import) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneNote(phoneText("android.import.adds.pdfs.audio.images.text"))
        PhoneIntakeFactRows("import")
        PaneCard {
            PaneNavRow(label = phoneText("android.import.files"), onClick = actions.onPickFiles, modifier = Modifier.testTag("importRow-files"))
        }
        val screenshotsOn = LocalPhoneScreenshots.current.state.enabled
        PaneNavRow(
            label = phoneText("android.import.screenshotscometheirown"),
            value = if (screenshotsOn) phoneText("android.import.openscreenshots") else phoneText("android.import.setupscreenshots"),
            onClick = actions.onOpenScreenshots,
            modifier = Modifier.testTag("importRow-screenshots"),
        )
        state.intake?.let { line ->
            IntakeResult(line, Modifier.testTag("importIntake"))
            if (line is PhoneIntakeLine.Finished) {
                DisposableEffect(line) { onDispose { actions.onIntakeSeen() } }
            }
        }
        if (state.interrupted) {
            PaneCard(Modifier.testTag("importInterrupted")) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ShellMetrics.surfacePadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        phoneText("android.import.something.were.adding.didn.t"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = actions.onDismissInterrupted, modifier = Modifier.testTag("importInterrupted-dismiss")) { Text(phoneText("android.import.ok")) }
                }
            }
        }
        PaneSectionTitle(phoneText("android.import.recent"))
        PaneCard {
            Text(
                recentText(state.recent),
                style = MaterialTheme.typography.bodyMedium,
                color = shellSecondaryInk,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ShellMetrics.rowMinHeight)
                    .padding(horizontal = ShellMetrics.surfacePadding, vertical = 12.dp)
                    .testTag("importRow-recent")
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (state.rows.isNotEmpty()) {
            PaneSectionTitle(phoneText("android.import.device"))
            PaneCard {
                state.rows.forEachIndexed { index, row ->
                    if (index > 0) PaneRowDivider()
                    ImportItemRow(row, actions, onAskRemove = { confirmingRemove = it })
                }
            }
        }
    }
    confirmingRemove?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmingRemove = null },
            title = { Text(phoneText("android.import.remove.row.name" , "name" to (row.name))) },
            text = {
                Text(
                    when {
                        row.kind == ImportRowKind.MISSING_COPY -> phoneText("android.import.copy.already.gone.so.only")
                        row.savedAtJournal -> phoneText("android.import.removes.copy.device.journal.may")
                        else -> phoneText("android.import.removes.copy.device.so.won")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRemove = null
                        actions.onRemove(row.id)
                    },
                    modifier = Modifier.testTag("importRemove-confirm"),
                ) { Text(phoneText("android.import.remove")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = null }, modifier = Modifier.testTag("importRemove-cancel")) { Text(phoneText("android.phone.cancel")) }
            },
        )
    }
}

@Composable
private fun IntakeResult(line: PhoneIntakeLine, modifier: Modifier = Modifier) {
    PaneCard(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(horizontal = ShellMetrics.surfacePadding, vertical = 10.dp)) {
            Text(intakeText(line), style = MaterialTheme.typography.bodyMedium)
            if (line is PhoneIntakeLine.Finished) RefusalLines(line.refused)
        }
    }
}

@Composable
private fun RefusalLines(refused: List<IntakeRefusalLine>) {
    refused.forEach { line ->
        Text(
            phoneText("android.import.line.label.refusaltext.line.reason" , "label" to (line.label), "reason" to (refusalText(line.reason))),
            style = MaterialTheme.typography.bodySmall,
            color = shellSecondaryInk,
        )
    }
}

@Composable
private fun ImportItemRow(row: PhoneImportRow, actions: PhoneImportActions, onAskRemove: (PhoneImportRow) -> Unit) {
    val subLine = when (row.kind) {
        ImportRowKind.WAITING, ImportRowKind.GOING_AGAIN -> phoneText("android.import.waiting")
        ImportRowKind.SENDING -> phoneText("android.import.sending")
        ImportRowKind.REFUSED -> row.error ?: IMPORT_REFUSED_FALLBACK
        ImportRowKind.MISSING_COPY -> phoneText("android.import.copy.device.missing")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ShellMetrics.rowMinHeight)
            .padding(horizontal = ShellMetrics.surfacePadding, vertical = 8.dp)
            .testTag("importItem-${row.id}"),
    ) {
        Text(
            row.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(subLine, style = MaterialTheme.typography.bodySmall, color = shellSecondaryInk)
        if (row.kind != ImportRowKind.SENDING) {
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (row.kind == ImportRowKind.REFUSED && row.retryable) {
                    TextButton(
                        onClick = { actions.onRetry(row.id) },
                        modifier = Modifier
                            .testTag("importItem-${row.id}-retry")
                            .semantics { contentDescription = phoneText("android.import.try.now.row.name" , "name" to (row.name)) },
                    ) { Text(phoneText("android.phone.try.now")) }
                }
                TextButton(
                    onClick = { onAskRemove(row) },
                    modifier = Modifier
                        .testTag("importItem-${row.id}-remove")
                        .semantics { contentDescription = phoneText("android.import.remove.row.name.n2" , "name" to (row.name)) },
                ) { Text(phoneText("android.import.remove")) }
            }
        }
    }
}

// ---- the share confirmation ----

/**
 * One shared item as the sheet shows it. [type] is an owner word (PDF, image, audio, text,
 * file), never a media type; [size] is left out when the source doesn't report one. [refusal] is
 * set when the item's type alone already rules it out, so the owner sees that before adding.
 */
data class ShareSheetItem(val name: String, val type: String, val size: String?, val refusal: IntakeRefusalReason? = null)

sealed interface ShareSheetState {
    /** What the share holds, before the owner adds anything. */
    data class Review(
        val items: List<ShareSheetItem>,
        val textPreview: String?,
        /** Text arrived alongside files; it isn't added. */
        val textDropped: Boolean = false,
    ) : ShareSheetState {
        /** Whether anything here can be added at all. */
        val addable: Boolean get() = textPreview != null || items.any { it.refusal == null }
    }

    data class Copying(val done: Int, val total: Int) : ShareSheetState

    data class Done(val accepted: Int, val refused: List<IntakeRefusalLine>) : ShareSheetState
}

/** How many shared items the review lists by name before it counts the rest. */
private const val SHARE_SHEET_LISTED = 20

/** The sheet a share opens: nothing is added to the journal until the owner taps to add it. */
@Composable
fun PhoneShareSheet(
    state: ShareSheetState,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .shellSurface(shellSurface, shellHairline, ShellMetrics.cardShape)
            .padding(20.dp)
            .testTag("shareSheet"),
    ) {
        when (state) {
            is ShareSheetState.Review -> {
                Text(phoneText("android.import.add.journal"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.items.take(SHARE_SHEET_LISTED).forEach { item ->
                        Text(
                            listOfNotNull(item.name, item.type, item.size).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("shareSheet-item"),
                        )
                        item.refusal?.let { reason ->
                            Text(
                                refusalText(reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = shellSecondaryInk,
                                modifier = Modifier.testTag("shareSheet-itemRefused"),
                            )
                        }
                    }
                    if (state.items.size > SHARE_SHEET_LISTED) {
                        Text(
                            phoneText("android.import.state.items.size.share.sheet", "count" to (state.items.size - SHARE_SHEET_LISTED)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = shellSecondaryInk,
                            modifier = Modifier.testTag("shareSheet-more"),
                        )
                    }
                    state.textPreview?.let { preview ->
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("shareSheet-text"),
                        )
                    }
                    if (state.textDropped) {
                        Spacer(Modifier.height(8.dp))
                        Text(phoneText("android.import.text.sent.along.with.these"), style = MaterialTheme.typography.bodySmall, color = shellSecondaryInk)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (state.addable) {
                        TextButton(onClick = onCancel, modifier = Modifier.testTag("shareSheet-cancel")) { Text(phoneText("android.phone.cancel")) }
                        TextButton(onClick = onAdd, modifier = Modifier.testTag("shareSheet-add")) { Text(phoneText("android.import.add.journal.n2")) }
                    } else {
                        TextButton(onClick = onCancel, modifier = Modifier.testTag("shareSheet-close")) { Text(phoneText("android.journal.close")) }
                    }
                }
            }
            is ShareSheetState.Copying -> {
                Text(
                    intakeText(PhoneIntakeLine.Copying(state.done, state.total)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("shareSheet-copying").semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(8.dp))
                Text(phoneText("android.import.keeps.saving.if.close"), style = MaterialTheme.typography.bodySmall, color = shellSecondaryInk)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDone, modifier = Modifier.testTag("shareSheet-close")) { Text(phoneText("android.journal.close")) }
                }
            }
            is ShareSheetState.Done -> {
                Text(
                    intakeText(PhoneIntakeLine.Finished(state.accepted, state.refused)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("shareSheet-done").semantics { liveRegion = LiveRegionMode.Polite },
                )
                Column(Modifier.weight(1f, fill = false).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    RefusalLines(state.refused)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDone, modifier = Modifier.testTag("shareSheet-close")) { Text(phoneText("android.import.done")) }
                }
            }
        }
    }
}
