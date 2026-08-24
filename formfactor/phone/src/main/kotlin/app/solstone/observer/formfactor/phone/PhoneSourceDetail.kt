// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
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
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel

internal data class SourceDetailAction(
    val label: String,
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
        action = SourceDetailAction("connect a journal"),
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
        diagnosis = "open sol to resume observing",
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

@Composable
internal fun PhoneSourceDetail(
    loadState: LoadState<SourcesReadModel>,
    sourceId: String,
    homeTileStore: PhoneHomeTileStore,
    onStartObserving: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val readModel = (loadState as? LoadState.Loaded)?.value
    Column(modifier = modifier.fillMaxSize()) {
        readModel?.let { model ->
            model.sources.firstOrNull { it.sourceId == sourceId }?.let { status ->
                SourceDetailTemplate(
                    status = status,
                    reason = resolveSourceDetailReason(status, model.observer),
                    onStartObserving = onStartObserving,
                )
            }
        }
        HomeTileControl(sourceId = sourceId, homeTileStore = homeTileStore)
    }
}

@Composable
private fun SourceDetailTemplate(
    status: SourceStatus,
    reason: ReasonCode,
    onStartObserving: () -> Unit,
) {
    val rule = sourceDetailRule(reason)
    Text(
        text = sourceStateCopy(status.state),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag(VERDICT_TEST_TAG),
    )
    rule.diagnosis?.let { diagnosis ->
        Text(
            text = diagnosis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .testTag(REASON_TEST_TAG),
        )
    }
    rule.action?.let { action ->
        SourceDetailActionControl(
            action = action,
            retryHonest = rule.retryHonest,
            onStartObserving = onStartObserving,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag(FACTS_TEST_TAG),
    ) {
        Text(text = sourceLabel(status.sourceId))
        Text(text = sourceWishCopy(status.wish))
    }
}

@Composable
private fun SourceDetailActionControl(
    action: SourceDetailAction,
    retryHonest: Boolean,
    onStartObserving: () -> Unit,
) {
    Button(
        onClick = onStartObserving,
        enabled = retryHonest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
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
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "give this a tile on home",
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            // This state affordance will be replaced when the ruled glyph pair arrives.
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
