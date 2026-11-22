// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.ZoneId

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

    /** Staged in the journal awaiting owner preview & confirmation; local copy stays on device. */
    READY_TO_REVIEW,

    /** The waiting copy in the journal expired (`404`); local copy stays on device to send again. */
    REVIEW_EXPIRED,

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
    /** Valid strict-ASCII `YYYYMMDD_HHMMSS` journal import ID when staged for review. */
    val importId: String? = null,
    /** Confirmation kind (e.g. `body_confirmation_required` for the 1-hour Apple Health copy). */
    val reviewKind: String? = null,
)

/** One persisted 24-hour recent receipt scoped to the currently paired journal instance. */
data class PhoneImportRecentReceipt(
    val id: String,
    val filename: String,
    val importId: String?,
    val deliveredAtEpochMs: Long,
)

/** Why an item wasn't taken in, for the owner. */
enum class IntakeRefusalReason { CANNOT_CONVERT, NOT_SHAREABLE, UNREADABLE, TOO_LARGE, NO_SPACE }

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
    /** The journal can be opened here: paired, and its mark confirmed. */
    val journalOpenable: Boolean = false,
    /** Persisted 24-hour receipts (max 5 newest) for the currently paired & confirmed journal. */
    val recentReceipts: List<PhoneImportRecentReceipt> = emptyList(),
    /** True when the paired journal is currently offline/unreachable while items wait. */
    val offline: Boolean = false,
) {
    val readyToReviewCount: Int
        get() = rows.count { it.kind == ImportRowKind.READY_TO_REVIEW }

    val hasNeedsAttention: Boolean
        get() = rows.any {
            it.kind == ImportRowKind.REFUSED ||
                it.kind == ImportRowKind.MISSING_COPY ||
                it.kind == ImportRowKind.REVIEW_EXPIRED
        } || (rows.isNotEmpty() && recent in setOf(
            ImportRecentState.PAIRING_REFUSED,
            ImportRecentState.PAIRING_INCOMPLETE,
            ImportRecentState.JOURNAL_TOO_OLD,
        ))
}

class PhoneImportActions(
    val onPickPhotos: () -> Unit = {},
    val onPickFiles: () -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onDismissInterrupted: () -> Unit = {},
    /** The finished intake's result has been shown; it is cleared once the owner leaves it. */
    val onIntakeSeen: () -> Unit = {},
    val onOpenScreenshots: () -> Unit = {},
    /** Opens the journal's own import page, for the kinds it brings in from other services. */
    val onMoreImports: () -> Unit = {},
    /** Opens `/app/import/<importId>` in the in-app journal sheet for review & confirmation. */
    val onReviewInJournal: (String) -> Unit = {},
    /** Re-enqueues an expired review item from its local device bytes. */
    val onSendAgain: (String) -> Unit = {},
    /** Opens `/app/import/<importId>` from a persisted 24h recent receipt. */
    val onOpenRecentImport: (String) -> Unit = {},
)

/** The import pane's state and actions, provided once by the activity that hosts the shell. */
data class PhoneImportBinding(
    val state: PhoneImportPaneState = PhoneImportPaneState(),
    val actions: PhoneImportActions = PhoneImportActions(),
)

val LocalPhoneImport = compositionLocalOf { PhoneImportBinding() }

/** Whether the in-app journal sheet is currently open, so [PhoneImportPane] can restore focus on dismiss. */
val LocalPhoneJournalSheetOpen = compositionLocalOf { false }

/**
 * Distinct geometric shapes for every import state (separate from [TileDotMark], which stays locked
 * to the 6 [app.solstone.core.model.SourceState] entries). Zero infinite animations.
 */
enum class ImportMarkerKind {
    /** Brand-orange bullseye (outer ring + center dot). */
    READY_TO_REVIEW,

    /** Error-ink hollow triangle (expired waiting copy). */
    REVIEW_EXPIRED,

    /** Brand-orange 270-degree open arc (actively sending). */
    SENDING,

    /** Calm secondary-ink hollow circle ring (waiting on device). */
    WAITING,

    /** Error-ink filled diamond (needs attention / refused / missing copy). */
    NEEDS_ATTENTION,

    /** Status-on green filled disc (delivered in journal). */
    DELIVERED,
}

@Composable
fun ImportStateMarker(
    kind: ImportMarkerKind,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.outline
    val error = MaterialTheme.colorScheme.error
    val secondary = shellSecondaryInk
    val green = LocalStatusOnGreen.current
    Canvas(
        modifier
            .size(12.dp)
            .testTag("importMarker-${kind.name.lowercase()}")
            .clearAndSetSemantics { },
    ) {
        val strokeWidth = size.minDimension * 0.18f
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val inset = size.minDimension * 0.10f
        val diameter = size.minDimension - inset * 2
        val topLeft = Offset(inset, inset)
        val box = Size(diameter, diameter)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = diameter / 2f
        when (kind) {
            ImportMarkerKind.READY_TO_REVIEW -> {
                drawArc(
                    color = accent,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = box,
                    style = stroke,
                )
                drawCircle(
                    color = accent,
                    radius = r * 0.38f,
                    center = Offset(cx, cy),
                )
            }
            ImportMarkerKind.REVIEW_EXPIRED -> {
                val path = Path().apply {
                    moveTo(cx, inset)
                    lineTo(size.width - inset, size.height - inset)
                    lineTo(inset, size.height - inset)
                    close()
                }
                drawPath(path, color = error, style = stroke)
            }
            ImportMarkerKind.SENDING -> {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = box,
                    style = stroke,
                )
            }
            ImportMarkerKind.WAITING -> {
                drawArc(
                    color = secondary,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = box,
                    style = stroke,
                )
            }
            ImportMarkerKind.NEEDS_ATTENTION -> {
                val path = Path().apply {
                    moveTo(cx, cy - r)
                    lineTo(cx + r, cy)
                    lineTo(cx, cy + r)
                    lineTo(cx - r, cy)
                    close()
                }
                drawPath(path, color = error)
            }
            ImportMarkerKind.DELIVERED -> {
                drawCircle(
                    color = green,
                    radius = r,
                    center = Offset(cx, cy),
                )
            }
        }
    }
}

fun refusalText(reason: IntakeRefusalReason): String =
    when (reason) {
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

private fun deviceRowSortRank(kind: ImportRowKind): Int = when (kind) {
    ImportRowKind.REFUSED, ImportRowKind.MISSING_COPY -> 0
    ImportRowKind.SENDING -> 1
    ImportRowKind.WAITING, ImportRowKind.GOING_AGAIN -> 2
    ImportRowKind.READY_TO_REVIEW, ImportRowKind.REVIEW_EXPIRED -> 3
}

internal fun queueHoldLine(state: PhoneImportPaneState): String? = when (state.recent) {
    ImportRecentState.UNPAIRED,
    ImportRecentState.AWAITING_CONFIRMATION,
    ImportRecentState.PAIRING_REFUSED,
    ImportRecentState.PAIRING_INCOMPLETE,
    ImportRecentState.JOURNAL_TOO_OLD,
    -> recentText(state.recent)
    else -> if (state.offline) phoneText("android.import.queue.offline") else null
}

/** `import`: what it adds, file picker & journal handoff, review card, device queue, and 24h recent receipts. */
@Composable
fun PhoneImportPane(
    modifier: Modifier = Modifier,
    state: PhoneImportPaneState = PhoneImportPaneState(),
    actions: PhoneImportActions = PhoneImportActions(),
) {
    var confirmingRemove by remember { mutableStateOf<PhoneImportRow?>(null) }
    val journalSheetOpen = LocalPhoneJournalSheetOpen.current
    var previousSheetOpen by remember { mutableStateOf(journalSheetOpen) }
    var lastJournalTrigger by remember { mutableStateOf<String?>(null) }
    val moreFocusRequester = remember { FocusRequester() }
    val allHistoryFocusRequester = remember { FocusRequester() }
    val triggerFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    LaunchedEffect(journalSheetOpen) {
        if (previousSheetOpen && !journalSheetOpen) {
            val target = lastJournalTrigger
            if (target != null) {
                val requester = when (target) {
                    "more" -> moreFocusRequester
                    "history" -> allHistoryFocusRequester
                    else -> triggerFocusRequesters[target]
                }
                if (requester != null) {
                    runCatching { requester.requestFocus() }
                }
                lastJournalTrigger = null
            }
        }
        previousSheetOpen = journalSheetOpen
    }

    val reviewRows = state.rows.filter {
        it.kind == ImportRowKind.READY_TO_REVIEW || it.kind == ImportRowKind.REVIEW_EXPIRED
    }
    val deviceRows = state.rows
        .filter { it.kind != ImportRowKind.READY_TO_REVIEW && it.kind != ImportRowKind.REVIEW_EXPIRED }
        .sortedBy { deviceRowSortRank(it.kind) }
    val nowEpochMs = LocalPhoneStatusNow.current.nowEpochMs()
    val zone = ZoneId.systemDefault()

    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Import) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneNote(phoneText("android.import.adds.pdfs.audio.images.text"))
        PhoneIntakeFactRows("import")
        PaneCard {
            PaneNavRow(
                label = phoneText("android.import.files"),
                onClick = actions.onPickFiles,
                modifier = Modifier.testTag("importRow-files"),
            )
            if (state.journalOpenable) {
                PaneRowDivider()
                PaneNavRow(
                    label = phoneText("android.import.moreimports"),
                    subLine = phoneText("android.import.moreimports.subline"),
                    onClick = {
                        lastJournalTrigger = "more"
                        actions.onMoreImports()
                    },
                    modifier = Modifier
                        .testTag("importRow-more")
                        .focusRequester(moreFocusRequester),
                )
            }
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
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ShellMetrics.surfacePadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        phoneText("android.import.something.were.adding.didn.t"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = actions.onDismissInterrupted,
                        modifier = Modifier.testTag("importInterrupted-dismiss"),
                    ) { Text(phoneText("android.import.ok")) }
                }
            }
        }
        if (reviewRows.isNotEmpty()) {
            PaneSectionTitle(phoneText("android.import.waiting.for.review"))
            ImportReviewCard(Modifier.testTag("importReviewCard")) {
                reviewRows.forEachIndexed { index, row ->
                    if (index > 0) PaneRowDivider()
                    val triggerKey = "review:${row.id}"
                    val requester = triggerFocusRequesters.getOrPut(triggerKey) { FocusRequester() }
                    ImportReviewRow(
                        row = row,
                        journalOpenable = state.journalOpenable,
                        focusRequester = requester,
                        onReview = { importId ->
                            lastJournalTrigger = triggerKey
                            actions.onReviewInJournal(importId)
                        },
                        onSendAgain = { actions.onSendAgain(row.id) },
                        onAskRemove = { confirmingRemove = row },
                    )
                }
            }
        }
        if (deviceRows.isNotEmpty()) {
            PaneSectionTitle(phoneText("android.import.device"))
            PaneCard(Modifier.testTag("importDeviceCard")) {
                val holdLine = queueHoldLine(state)
                if (holdLine != null) {
                    Text(
                        text = holdLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = shellSecondaryInk,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ShellMetrics.surfacePadding, vertical = 10.dp)
                            .testTag("importQueueHold"),
                    )
                    PaneRowDivider()
                }
                deviceRows.forEachIndexed { index, row ->
                    if (index > 0) PaneRowDivider()
                    ImportItemRow(row, actions, onAskRemove = { confirmingRemove = it })
                }
            }
        }
        PaneSectionTitle(phoneText("android.import.recent"))
        PaneCard(Modifier.testTag("importRecentCard")) {
            if (state.recentReceipts.isEmpty()) {
                Text(
                    text = phoneText("android.import.nothing.yet"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = shellSecondaryInk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ShellMetrics.rowMinHeight)
                        .padding(horizontal = ShellMetrics.surfacePadding, vertical = 12.dp)
                        .testTag("importRow-recent")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                state.recentReceipts.forEachIndexed { index, receipt ->
                    if (index > 0) PaneRowDivider()
                    val triggerKey = "recent:${receipt.id}"
                    val requester = triggerFocusRequesters.getOrPut(triggerKey) { FocusRequester() }
                    ImportRecentReceiptRow(
                        receipt = receipt,
                        nowEpochMs = nowEpochMs,
                        zone = zone,
                        journalOpenable = state.journalOpenable,
                        focusRequester = requester,
                        onOpen = { importId ->
                            lastJournalTrigger = triggerKey
                            actions.onOpenRecentImport(importId)
                        },
                    )
                }
            }
            if (state.journalOpenable) {
                PaneRowDivider()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ShellMetrics.rowMinHeight)
                        .focusRequester(allHistoryFocusRequester)
                        .clickable(role = Role.Button) {
                            lastJournalTrigger = "history"
                            actions.onMoreImports()
                        }
                        .padding(horizontal = ShellMetrics.surfacePadding, vertical = 12.dp)
                        .testTag("importRow-allHistory"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = phoneText("android.import.recent.all.history"),
                        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
    confirmingRemove?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmingRemove = null },
            title = { Text(phoneText("android.import.remove.row.name", "name" to (row.name))) },
            text = {
                Text(
                    when {
                        row.kind == ImportRowKind.MISSING_COPY -> phoneText("android.import.copy.already.gone.so.only")
                        row.kind == ImportRowKind.READY_TO_REVIEW || row.savedAtJournal -> phoneText("android.import.removes.copy.device.journal.may")
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
                TextButton(
                    onClick = { confirmingRemove = null },
                    modifier = Modifier.testTag("importRemove-cancel"),
                ) { Text(phoneText("android.phone.cancel")) }
            },
        )
    }
}

@Composable
private fun ImportReviewCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fill = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f).compositeOver(shellSurface)
    val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    Column(
        modifier
            .fillMaxWidth()
            .background(color = fill, shape = ShellMetrics.cardShape)
            .border(width = 1.dp, color = border, shape = ShellMetrics.cardShape)
            .padding(vertical = 4.dp),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportReviewRow(
    row: PhoneImportRow,
    journalOpenable: Boolean,
    focusRequester: FocusRequester,
    onReview: (String) -> Unit,
    onSendAgain: () -> Unit,
    onAskRemove: () -> Unit,
) {
    val expired = row.kind == ImportRowKind.REVIEW_EXPIRED
    val markerKind = if (expired) ImportMarkerKind.REVIEW_EXPIRED else ImportMarkerKind.READY_TO_REVIEW
    val badgeText = if (expired) {
        phoneText("android.import.review.expired.label")
    } else {
        phoneText("android.import.tile.ready.to.review")
    }
    val badgeColor = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    val detailText = when {
        expired -> phoneText("android.import.review.expired.detail")
        row.reviewKind == "body_confirmation_required" -> phoneText("android.import.review.detail.hour")
        else -> phoneText("android.import.review.detail.general")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ShellMetrics.rowMinHeight)
            .padding(horizontal = ShellMetrics.surfacePadding, vertical = 12.dp)
            .testTag("importItem-${row.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ImportStateMarker(kind = markerKind)
            Spacer(Modifier.width(8.dp))
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelLarge,
                color = badgeColor,
            )
        }
        Text(
            text = detailText,
            style = MaterialTheme.typography.bodySmall,
            color = shellSecondaryInk,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (expired) {
                Button(
                    onClick = onSendAgain,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .testTag("importItem-${row.id}-sendAgain")
                        .semantics {
                            contentDescription = phoneText("android.import.send.again.review.a11y", "name" to row.name)
                        },
                ) {
                    Text(phoneText("android.import.send.again.review"))
                }
            } else {
                val importId = row.importId
                Button(
                    onClick = { if (importId != null) onReview(importId) },
                    enabled = journalOpenable && importId != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .testTag("importItem-${row.id}-review")
                        .semantics {
                            contentDescription = phoneText("android.import.review.in.journal.a11y", "name" to row.name)
                        },
                ) {
                    Text(phoneText("android.import.review.in.journal"))
                }
            }
            TextButton(
                onClick = onAskRemove,
                modifier = Modifier
                    .testTag("importItem-${row.id}-remove")
                    .semantics {
                        contentDescription = phoneText("android.import.remove.row.name.n2", "name" to row.name)
                    },
            ) {
                Text(phoneText("android.import.remove"))
            }
        }
    }
}

@Composable
private fun ImportRecentReceiptRow(
    receipt: PhoneImportRecentReceipt,
    nowEpochMs: Long,
    zone: ZoneId,
    journalOpenable: Boolean,
    focusRequester: FocusRequester,
    onOpen: (String) -> Unit,
) {
    val relative = formatRelative(receipt.deliveredAtEpochMs, nowEpochMs, zone)
    val subLine = phoneText("android.import.recent.in.journal", "relative" to relative)
    val importId = receipt.importId
    val openable = journalOpenable && importId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ShellMetrics.rowMinHeight)
            .then(
                if (openable) {
                    Modifier
                        .focusRequester(focusRequester)
                        .clickable(role = Role.Button) { onOpen(importId!!) }
                        .semantics {
                            contentDescription = phoneText(
                                "android.import.recent.open.a11y",
                                "name" to receipt.filename,
                                "relative" to relative,
                            )
                        }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = ShellMetrics.surfacePadding, vertical = 10.dp)
            .testTag("importRecent-${receipt.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ImportStateMarker(kind = ImportMarkerKind.DELIVERED)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = receipt.filename,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subLine,
                style = MaterialTheme.typography.bodySmall,
                color = shellSecondaryInk,
            )
        }
        if (openable) {
            Icon(
                painter = painterResource(R.drawable.phone_chevron_right),
                contentDescription = null,
                tint = shellSecondaryInk,
                modifier = Modifier.size(16.dp),
            )
        }
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
            phoneText("android.import.line.label.refusaltext.line.reason", "label" to (line.label), "reason" to (refusalText(line.reason))),
            style = MaterialTheme.typography.bodySmall,
            color = shellSecondaryInk,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportItemRow(row: PhoneImportRow, actions: PhoneImportActions, onAskRemove: (PhoneImportRow) -> Unit) {
    val markerKind = when (row.kind) {
        ImportRowKind.WAITING, ImportRowKind.GOING_AGAIN -> ImportMarkerKind.WAITING
        ImportRowKind.SENDING -> ImportMarkerKind.SENDING
        ImportRowKind.REFUSED, ImportRowKind.MISSING_COPY -> ImportMarkerKind.NEEDS_ATTENTION
        ImportRowKind.READY_TO_REVIEW -> ImportMarkerKind.READY_TO_REVIEW
        ImportRowKind.REVIEW_EXPIRED -> ImportMarkerKind.REVIEW_EXPIRED
    }
    val isAttention = row.kind == ImportRowKind.REFUSED || row.kind == ImportRowKind.MISSING_COPY
    val subLine = when (row.kind) {
        ImportRowKind.WAITING, ImportRowKind.GOING_AGAIN -> phoneText("android.import.row.waiting.detail")
        ImportRowKind.SENDING -> phoneText("android.import.row.sending.detail")
        ImportRowKind.REFUSED -> row.error ?: IMPORT_REFUSED_FALLBACK
        ImportRowKind.MISSING_COPY -> phoneText("android.import.copy.device.missing")
        ImportRowKind.READY_TO_REVIEW -> phoneText("android.import.review.detail.general")
        ImportRowKind.REVIEW_EXPIRED -> phoneText("android.import.review.expired.detail")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ShellMetrics.rowMinHeight)
            .padding(horizontal = ShellMetrics.surfacePadding, vertical = 10.dp)
            .testTag("importItem-${row.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            row.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ImportStateMarker(kind = markerKind)
            Spacer(Modifier.width(8.dp))
            Text(
                text = subLine,
                style = MaterialTheme.typography.bodySmall,
                color = if (isAttention) MaterialTheme.colorScheme.error else shellSecondaryInk,
            )
        }
        if (row.kind != ImportRowKind.SENDING) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (row.kind == ImportRowKind.REFUSED && row.retryable) {
                    TextButton(
                        onClick = { actions.onRetry(row.id) },
                        modifier = Modifier
                            .testTag("importItem-${row.id}-retry")
                            .semantics { contentDescription = phoneText("android.import.try.now.row.name", "name" to (row.name)) },
                    ) { Text(phoneText("android.phone.try.now")) }
                }
                TextButton(
                    onClick = { onAskRemove(row) },
                    modifier = Modifier
                        .testTag("importItem-${row.id}-remove")
                        .semantics { contentDescription = phoneText("android.import.remove.row.name.n2", "name" to (row.name)) },
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
                        if (state.items.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("shareSheet-text"),
                        )
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
