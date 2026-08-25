// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun PhoneAddMorePane(
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .semantics { paneTitle = spokenPaneTitle(PhoneRoute.AddMore) },
    ) {
        phoneSourceLabels.keys.forEach { id ->
            Text(
                text = sourceLabel(id),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onOpenSource(id) }
                    .padding(12.dp)
                    .testTag("addMoreRow-$id"),
            )
        }
    }
}
