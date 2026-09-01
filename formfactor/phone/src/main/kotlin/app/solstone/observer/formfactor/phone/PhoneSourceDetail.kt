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
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel

internal enum class SourceDetailActionKind { RETRY, CONNECT_JOURNAL }

internal data class SourceDetailAction(
    val label: String,
    val kind: SourceDetailActionKind = SourceDetailActionKind.RETRY,
)

internal data class SourceDetailRule(
    val diagnosis: String?,
    val action: SourceDetailAction?,
    val retryHonest: Boolean,
)

internal fun sourceDetailRule(reason: ReasonCode): SourceDetailRule = when (reason) {
    ReasonCode.PERMISSION_REVOKED -> SourceDetailRule(
        diagnosis = "permissions needed",
        action = SourceDetailAction("grant permissions"),
        retryHonest = false,
    )
    ReasonCode.AUTH_REVOKED -> SourceDetailRule(
        diagnosis = "access to your journal was revoked",
        action = SourceDetailAction("pair again"),
        retryHonest = false,
    )
    ReasonCode.SERVICE_KILLED -> SourceDetailRule(
        diagnosis = "observing was stopped by the system",
        action = SourceDetailAction("start observing again"),
        retryHonest = true,
    )
    ReasonCode.STORAGE_FULL -> SourceDetailRule(
        diagnosis = "storage is full",
        action = SourceDetailAction("manage local storage"),
        retryHonest = false,
    )
    ReasonCode.UNPAIRED -> SourceDetailRule(
        diagnosis = "not paired with your journal",
        action = SourceDetailAction("connect a journal", kind = SourceDetailActionKind.CONNECT_JOURNAL),
        retryHonest = false,
    )
    ReasonCode.PROVIDER_SILENT -> SourceDetailRule(
        diagnosis = "nothing has come in recently",
        action = null,
        retryHonest = false,
    )
    ReasonCode.REBOOTED -> SourceDetailRule(
        diagnosis = "this device restarted and observing didn't resume on its own",
        action = SourceDetailAction("start observing again"),
        retryHonest = true,
    )
    ReasonCode.TRANSPORT_UNAVAILABLE -> SourceDetailRule(
        diagnosis = "can't reach your journal",
        action = null,
        retryHonest = false,
    )
    ReasonCode.FOREGROUND_START_NOT_ALLOWED -> SourceDetailRule(
        // `sol` retired as a product name 2026-08-19. mobile-shell.md section 5.6
        // scoped this exact substitution and left it pending; landing it here.
        diagnosis = "open solstone to resume observing",
        action = null,
        retryHonest = false,
    )
    ReasonCode.DESIRED_OFF,
    ReasonCode.NONE -> SourceDetailRule(
        diagnosis = null,
        action = null,
        retryHonest = false,
    )
}

private val DEVICE_LEVEL_REASONS = setOf(
    ReasonCode.UNPAIRED,
    ReasonCode.AUTH_REVOKED,
    ReasonCode.SERVICE_KILLED,
)

internal fun resolveSourceDetailReason(
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
    onConnectJournal: () -> Unit,
    modifier: Modifier = Modifier,
    onToggle: (SourceWish) -> Unit = {},
) {
    val readModel = (loadState as? LoadState.Loaded)?.value
    val paired = readModel?.observer?.reason != ReasonCode.UNPAIRED
    PhonePaneScaffold(modifier) {
        readModel?.let { model ->
            model.sources.firstOrNull { it.sourceId == sourceId }?.let { status ->
                SourceDetailTemplate(
                    status = status,
                    reason = resolveSourceDetailReason(status, model.observer),
                    paired = paired,
                    onStartObserving = onStartObserving,
                    onConnectJournal = onConnectJournal,
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
                "it just keeps home to what you actually look at.",
        )
    }
}

@Composable
private fun SourceDetailTemplate(
    status: SourceStatus,
    reason: ReasonCode,
    paired: Boolean,
    onStartObserving: () -> Unit,
    onConnectJournal: () -> Unit,
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
            retryHonest = rule.retryHonest,
            onStartObserving = onStartObserving,
            onConnectJournal = onConnectJournal,
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
                    )
                }
            }
        }
    }
    // The facts. ⛔ Not the label and not the state word — both are already on screen.
    PaneSectionTitle("details")
    PaneCard(modifier = Modifier.testTag(FACTS_TEST_TAG)) {
        PaneFactRow(label = "you asked for", value = sourceWishCopy(status.wish))
        PaneRowDivider()
        PaneFactRow(label = "right now", value = sourceStateCopy(status.state))
    }
}

@Composable
private fun SourceDetailActionControl(
    action: SourceDetailAction,
    retryHonest: Boolean,
    onStartObserving: () -> Unit,
    onConnectJournal: () -> Unit,
) {
    val enabled = action.kind == SourceDetailActionKind.CONNECT_JOURNAL || retryHonest
    val onClick = if (action.kind == SourceDetailActionKind.CONNECT_JOURNAL) onConnectJournal else onStartObserving
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
