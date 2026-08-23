// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish

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
    Row(
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable { onOpen() }
                .padding(12.dp)
                .testTag("sourceBody-${status.sourceId}"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhoneTileDot(status.state)
                Text(text = label, modifier = Modifier.padding(start = 8.dp))
            }
            Text(text = stateCopy)
            if (status.state == SourceState.OFF) {
                Text(text = "turn it on any time.")
            }
        }
        if (earnsSwitch) {
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .testTag("sourceSwitch-${status.sourceId}"),
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
    }
}
