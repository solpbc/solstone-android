// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Switch
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish

private val PHONE_SOURCE_TILE_INLINE_MIN_WIDTH = 224.dp

@Composable
fun PhoneSourceTile(
    status: SourceStatus,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onToggle: (SourceWish) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = sourceLabel(status.sourceId)
    val stateCopy = sourceStateCopy(status.state)
    val earnsSwitch = sourceEarnsSwitch(status.sourceId)
    val wishOn = status.wish == SourceWish.On
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .testTag("sourceTile-${status.sourceId}")
            .clearAndSetSemantics {
                stateDescription = "$label $stateCopy"
                collectionItemInfo = CollectionItemInfo(index, count, 0, 1)
                customActions = buildList {
                    add(CustomAccessibilityAction(label) { onOpen(); true })
                    if (earnsSwitch) {
                        add(
                            CustomAccessibilityAction(if (wishOn) "off" else "on") {
                                onToggle(if (wishOn) SourceWish.Off else SourceWish.On)
                                true
                            },
                        )
                    }
                }
            },
    ) {
        if (maxWidth < PHONE_SOURCE_TILE_INLINE_MIN_WIDTH) {
            Column {
                PhoneSourceBody(
                    label = label,
                    state = status.state,
                    stateCopy = stateCopy,
                    onOpen = onOpen,
                    sourceId = status.sourceId,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (earnsSwitch) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        PhoneSourceSwitch(
                            sourceId = status.sourceId,
                            wishOn = wishOn,
                            onToggle = onToggle,
                        )
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhoneSourceBody(
                    label = label,
                    state = status.state,
                    stateCopy = stateCopy,
                    onOpen = onOpen,
                    sourceId = status.sourceId,
                    modifier = Modifier.weight(1f),
                )
                if (earnsSwitch) {
                    PhoneSourceSwitch(
                        sourceId = status.sourceId,
                        wishOn = wishOn,
                        onToggle = onToggle,
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneSourceBody(
    label: String,
    state: SourceState,
    stateCopy: String,
    onOpen: () -> Unit,
    sourceId: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clickable { onOpen() }
            .padding(12.dp)
            .testTag("sourceBody-$sourceId"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.testTag("sourceLabel-$sourceId"),
        ) {
            PhoneTileDot(state)
            Text(
                text = label,
                modifier = Modifier.padding(start = 8.dp),
                softWrap = false,
            )
        }
        Text(text = stateCopy)
        if (state == SourceState.OFF) {
            Text(text = "turn it on any time.")
        }
    }
}

@Composable
private fun PhoneSourceSwitch(
    sourceId: String,
    wishOn: Boolean,
    onToggle: (SourceWish) -> Unit,
) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("sourceSwitch-$sourceId"),
        contentAlignment = Alignment.Center,
    ) {
        Switch(
            checked = wishOn,
            onCheckedChange = { checked ->
                onToggle(if (checked) SourceWish.On else SourceWish.Off)
            },
        )
    }
}
