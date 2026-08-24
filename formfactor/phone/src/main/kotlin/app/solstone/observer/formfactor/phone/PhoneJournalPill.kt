// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@Composable
fun PhoneJournalPill(
    modifier: Modifier = Modifier,
    hideSemantics: Boolean = false,
    mark: @Composable () -> Unit = {},
) {
    Box(
        modifier.then(
            if (hideSemantics) Modifier.clearAndSetSemantics { } else Modifier.testTag("journalPill"),
        )
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        mark()
    }
}
