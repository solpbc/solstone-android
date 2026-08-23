// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.solstone.observer.harness.SourceStatus

@Composable
fun PhoneStatusPane(
    model: PhoneStatusModel,
    waiting: List<SourceStatus>,
    onDismiss: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = statusPillKind(model)
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = modifier
                .testTag("statusPane")
                .semantics { paneTitle = PhonePane.STATUS.paneTitle },
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                headingText(PhonePane.STATUS.headingKey)?.let { heading ->
                    Text(
                        text = heading,
                        modifier = Modifier.testTag("statusPaneHeading"),
                    )
                }
                when (kind) {
                    StatusPillKind.CONNECTED -> {
                        Text("all caught up")
                        Text("everything is in your journal")
                    }
                    StatusPillKind.SYNCING -> {
                        Text(
                            text = model.pendingCount.toString(),
                            style = TextStyle(fontFeatureSettings = "tnum"),
                        )
                        Text("syncing to your journal…")
                    }
                    StatusPillKind.OFFLINE -> {
                        Text(
                            text = model.pendingCount.toString(),
                            style = TextStyle(fontFeatureSettings = "tnum"),
                        )
                        Text("on this device")
                    }
                    StatusPillKind.NOT_PAIRED -> {
                        Text("your journal")
                    }
                }
                waiting.forEach { status ->
                    Text(
                        text = sourceLabel(status.sourceId),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSource(status.sourceId) }
                            .padding(vertical = 8.dp)
                            .testTag("waitingRow-${status.sourceId}"),
                    )
                }
            }
        }
    }
}
