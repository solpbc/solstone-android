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

/**
 * The journal pill's slot at the bottom of home.
 *
 * The shell owns the placement; what it holds is [PhoneJournalMarkPill], passed in by
 * the screen. ⚠ The slot defaulted to nothing *and the screen passed nothing*, which is
 * how home ended up with a 48dp invisible box where § 3 puts "a journal pill floating
 * at the bottom".
 */
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
