// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.reasonDiagnosis
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel

enum class SourceDetailActionKind {
    RETRY,
    GRANT_PERMISSIONS,
    CONNECT_JOURNAL,
    MANAGE_LOCAL_STORAGE,
}

data class SourceDetailAction(
    val label: String,
    val kind: SourceDetailActionKind = SourceDetailActionKind.RETRY,
)

data class SourceDetailRule(
    val diagnosis: String?,
    val action: SourceDetailAction?,
    val retryHonest: Boolean,
)

/**
 * The recovery half of § 5.6 — ⛔ **the diagnosis half is NOT here.**
 *
 * Every string this used to declare was byte-identical to [reasonDiagnosis]'s, which is what made
 * the duplication invisible: two copies that agree read as one table. They stopped agreeing once,
 * silently, and the stale copy kept a deleted product name alive on a reachable surface. This
 * function now owns only what is genuinely per-renderer — which action to offer, and whether a
 * bare retry can honestly help.
 */
fun sourceDetailRule(reason: ReasonCode): SourceDetailRule = SourceDetailRule(
    diagnosis = reasonDiagnosis(reason),
    action = reasonAction(reason),
    retryHonest = reasonRetryIsHonest(reason),
)

private fun reasonAction(reason: ReasonCode): SourceDetailAction? = when (reason) {
    ReasonCode.PERMISSION_REVOKED ->
        SourceDetailAction("grant permissions", SourceDetailActionKind.GRANT_PERMISSIONS)
    ReasonCode.AUTH_REVOKED ->
        SourceDetailAction("pair again", SourceDetailActionKind.CONNECT_JOURNAL)
    ReasonCode.UNPAIRED ->
        SourceDetailAction("connect a journal", SourceDetailActionKind.CONNECT_JOURNAL)
    ReasonCode.STORAGE_FULL ->
        SourceDetailAction("manage local storage", SourceDetailActionKind.MANAGE_LOCAL_STORAGE)
    ReasonCode.SERVICE_KILLED,
    ReasonCode.REBOOTED,
    ReasonCode.FOREGROUND_TYPE_NOT_HELD -> SourceDetailAction("start intake again")
    ReasonCode.FOREGROUND_START_NOT_ALLOWED -> SourceDetailAction("start intake")
    ReasonCode.PERSISTENCE_FAILED -> SourceDetailAction("try again")
    // ⛔ No generic retry: a lack of recent input does not establish a restartable stall, and an
    // unreachable journal does not by itself make intake fail.
    ReasonCode.PROVIDER_SILENT,
    ReasonCode.TRANSPORT_UNAVAILABLE,
    ReasonCode.DESIRED_OFF,
    ReasonCode.NONE -> null
}

private fun reasonRetryIsHonest(reason: ReasonCode): Boolean = when (reason) {
    ReasonCode.SERVICE_KILLED,
    ReasonCode.REBOOTED,
    ReasonCode.FOREGROUND_START_NOT_ALLOWED,
    ReasonCode.FOREGROUND_TYPE_NOT_HELD,
    ReasonCode.PERSISTENCE_FAILED -> true
    ReasonCode.PERMISSION_REVOKED,
    ReasonCode.AUTH_REVOKED,
    ReasonCode.UNPAIRED,
    ReasonCode.STORAGE_FULL,
    ReasonCode.PROVIDER_SILENT,
    ReasonCode.TRANSPORT_UNAVAILABLE,
    ReasonCode.DESIRED_OFF,
    ReasonCode.NONE -> false
}

private val DEVICE_LEVEL_REASONS = setOf(
    ReasonCode.UNPAIRED,
    ReasonCode.AUTH_REVOKED,
    ReasonCode.SERVICE_KILLED,
    ReasonCode.PERSISTENCE_FAILED,
)

fun resolveSourceDetailReason(
    status: SourceStatus,
    observer: ObserverStatus,
): ReasonCode = when {
    status.reason != ReasonCode.NONE -> status.reason
    status.wish != SourceWish.On -> ReasonCode.NONE
    observer.reason in DEVICE_LEVEL_REASONS -> observer.reason
    else -> ReasonCode.NONE
}

/**
 * `source detail` (§ 3): **verdict → plain-language reason → one action → the facts**,
 * then `give this a tile on home`.
 *
 * ⚠ **The facts block restated the two lines above it.** It rendered the source's own
 * label and its wish word — so a screen already titled `audio`, already leading with
 * `on`, ended with `audio` / `on` again. That is the same defect class as iOS's tile
 * rendering "off / off": a line that repeats the line above it is noise, and here it
 * was the whole facts section. The facts now say what the verdict cannot — what the
 * owner asked for, which is a different thing from what the source is doing, and § 5.1
 * is explicit that collapsing intent and state tells an owner they made a choice they
 * did not make.
 *
 * ⚠ **The source's own switch was missing here entirely.** A source hidden from home
 * had no reachable control anywhere in the app; this is the view § 3 calls "a way in".
 */
@Composable
internal fun PhoneSourceDetail(
    loadState: LoadState<SourcesReadModel>,
    sourceId: String,
    homeTileStore: PhoneHomeTileStore,
    onStartObserving: () -> Unit,
    onGrantPermissions: (String) -> Unit,
    onConnectJournal: () -> Unit,
    onManageLocalStorage: () -> Unit,
    modifier: Modifier = Modifier,
    onToggle: (SourceWish) -> Unit = {},
) {
    val readModel = (loadState as? LoadState.Loaded)?.value
    val paired = readModel?.observer?.paired == true
    PhonePaneScaffold(modifier) {
        readModel?.let { model ->
            model.sources.firstOrNull { it.sourceId == sourceId }?.let { status ->
                SourceDetailTemplate(
                    status = status,
                    reason = resolveSourceDetailReason(status, model.observer),
                    paired = paired,
                    onStartObserving = onStartObserving,
                    onGrantPermissions = onGrantPermissions,
                    onConnectJournal = onConnectJournal,
                    onManageLocalStorage = onManageLocalStorage,
                    onToggle = onToggle,
                )
            }
        }
        PaneSectionTitle("on home")
        PaneCard {
            HomeTileControl(sourceId = sourceId, homeTileStore = homeTileStore)
        }
        PaneNote(
            "taking a tile off home does not turn the source off. " +
                // ⚠ Was "it just keeps home to what you actually look at." — `keeps home to` is
                // not idiomatic, and three independent reads of the same frame stumbled on it.
                "it just keeps home to the ones you actually look at.",
        )
    }
}

@Composable
private fun SourceDetailTemplate(
    status: SourceStatus,
    reason: ReasonCode,
    paired: Boolean,
    onStartObserving: () -> Unit,
    onGrantPermissions: (String) -> Unit,
    onConnectJournal: () -> Unit,
    onManageLocalStorage: () -> Unit,
    onToggle: (SourceWish) -> Unit,
) {
    val rule = sourceDetailRule(reason)
    val subLine = sourceSubLine(status, paired)
    Spacer(Modifier.height(ShellMetrics.sectionGap))
    // The verdict: the state, said once, in the words the deck tile used.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(VERDICT_TEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhoneTileDot(status.state)
        Text(
            text = sourceStateCopy(status.state),
            style = MaterialTheme.typography.headlineSmall,
            color = if (status.state == SourceState.NEEDS_ATTENTION) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            modifier = Modifier.padding(start = 10.dp),
        )
    }
    if (subLine != null && subLine != rule.diagnosis) {
        Text(
            text = subLine,
            style = MaterialTheme.typography.bodyMedium,
            color = shellSecondaryInk,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    rule.diagnosis?.let { diagnosis ->
        Text(
            text = diagnosis,
            style = MaterialTheme.typography.bodyMedium,
            color = shellSecondaryInk,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .testTag(REASON_TEST_TAG),
        )
    }
    rule.action?.let { action ->
        Spacer(Modifier.height(ShellMetrics.sectionSpacing))
        SourceDetailActionControl(
            action = action,
            sourceId = status.sourceId,
            retryHonest = rule.retryHonest,
            onStartObserving = onStartObserving,
            onGrantPermissions = onGrantPermissions,
            onConnectJournal = onConnectJournal,
            onManageLocalStorage = onManageLocalStorage,
        )
    }
    if (sourceEarnsSwitch(status.sourceId)) {
        // ⛔ No section heading here: the app bar already names the source, and a
        // heading repeating it is the same restating-the-line-above defect this pane's
        // facts block carried.
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ShellMetrics.rowMinHeight)
                    .padding(horizontal = ShellMetrics.surfacePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "taking it in",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Switch(
                        checked = status.wish == SourceWish.On,
                        onCheckedChange = { checked ->
                            onToggle(if (checked) SourceWish.On else SourceWish.Off)
                        },
                        modifier = Modifier.testTag(SOURCE_SWITCH_TEST_TAG),
                        colors = solstoneSwitchColors(),
                    )
                }
            }
        }
    }
    // The facts. ⛔ Not the label and not the state word — both are already on screen. The only
    // thing this block adds is § 5.1's intent-vs-state split, so ⛔ do not collapse the two rows to
    // make the labels read better: that reintroduces the defect the split exists to prevent.
    //
    // `you asked for` was retired 2026-09-10: it named internal wish state, and it was literally
    // false on a fresh install, where the wish defaults to On without the owner choosing it.
    // `your setting` is true whether the value was chosen or defaulted, and it still reads as the
    // owner's rather than the system's. ⚠ It survives only while every value in this row is one
    // the owner can select — `SourceWish` is `{ Off, On }` and both are selectable. A computed
    // value entering this set would re-break the label. Contract: `mobile-shell.md` § 5.
    //
    // ⛔ When absence becomes representable — a source with no expressed wish, per § 5.1 — this row
    // is OMITTED rather than filled with a dash, a blank or `not set`. Not merely because there is
    // no value: once `right now` reads `ready to set up`, a wish row restates it, which is the
    // defect that shaped this block. The row below stays.
    PaneSectionTitle("details")
    PaneCard(modifier = Modifier.testTag(FACTS_TEST_TAG)) {
        // ⛔ Omitted when the owner has expressed nothing: there is no setting to state, and the
        // resolved `Off` behind it is the app's resolution rather than their choice. ⚠ It would also
        // restate `right now`, which already reads `ready to set up` — the exact duplication this
        // block was rebuilt to remove.
        if (status.wishExpressed) {
            PaneFactRow(label = "your setting", value = sourceWishCopy(status.wish))
            PaneRowDivider()
        }
        PaneFactRow(label = "right now", value = sourceStateCopy(status.state))
    }
}

@Composable
private fun SourceDetailActionControl(
    action: SourceDetailAction,
    sourceId: String,
    retryHonest: Boolean,
    onStartObserving: () -> Unit,
    onGrantPermissions: (String) -> Unit,
    onConnectJournal: () -> Unit,
    onManageLocalStorage: () -> Unit,
) {
    val enabled = action.kind != SourceDetailActionKind.RETRY || retryHonest
    val onClick = when (action.kind) {
        SourceDetailActionKind.RETRY -> onStartObserving
        // ⛔ The source id is not decoration here: it is what makes the grant attributable, so the
        // owner is asked for this source's permission and not for every declared type.
        SourceDetailActionKind.GRANT_PERMISSIONS -> { { onGrantPermissions(sourceId) } }
        SourceDetailActionKind.CONNECT_JOURNAL -> onConnectJournal
        SourceDetailActionKind.MANAGE_LOCAL_STORAGE -> onManageLocalStorage
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ACTION_TEST_TAG),
    ) {
        Text(action.label)
    }
}

@Composable
private fun HomeTileControl(sourceId: String, homeTileStore: PhoneHomeTileStore) {
    var hasTile by remember(sourceId, homeTileStore) {
        mutableStateOf(homeTileStore.hasTile(sourceId))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ShellMetrics.rowMinHeight)
            .padding(horizontal = ShellMetrics.surfacePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "give this a tile on home",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Switch(
                checked = hasTile,
                onCheckedChange = { checked ->
                    hasTile = checked
                    homeTileStore.setHasTile(sourceId, checked)
                },
                modifier = Modifier.testTag(HOME_TILE_CONTROL_TEST_TAG),
                colors = solstoneSwitchColors(),
            )
        }
    }
}

internal const val VERDICT_TEST_TAG = "sourceDetailVerdict"
internal const val REASON_TEST_TAG = "sourceDetailReason"
internal const val ACTION_TEST_TAG = "sourceDetailAction"
internal const val FACTS_TEST_TAG = "sourceDetailFacts"
internal const val HOME_TILE_CONTROL_TEST_TAG = "sourceDetailHomeTile"
internal const val SOURCE_SWITCH_TEST_TAG = "sourceDetailSwitch"
