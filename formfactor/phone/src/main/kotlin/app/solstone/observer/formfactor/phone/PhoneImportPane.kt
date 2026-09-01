// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/**
 * `import`.
 *
 * 🔴 **Android has no import intake behind this pane**, and the contract's own § "Android
 * import is unavailable, not a dead menu" says exactly what to render: three quiet,
 * non-interactive availability rows. That was already right and is unchanged in
 * substance — only the treatment moved onto the shell's card and type.
 *
 * ⛔ The one removal: a stray `in your journal` line sat at the bottom of the pane with
 * nothing attached to it. Read alone under three `not available` rows it asserts a
 * receipt for material that was never taken in.
 *
 * ⛔ No share-sheet line: Android declares no `ACTION_SEND` target, and the contract is
 * explicit that a target must not be added merely to make iOS's sentence travel.
 */
@Composable
fun PhoneImportPane(
    modifier: Modifier = Modifier,
) {
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Import) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneUnavailableRow(
                label = "photos",
                subLine = "not available",
                modifier = Modifier.testTag("importRow-photos"),
            )
            PaneRowDivider()
            PaneUnavailableRow(
                label = "files",
                subLine = "not available",
                modifier = Modifier.testTag("importRow-files"),
            )
            PaneRowDivider()
            PaneUnavailableRow(
                label = "recently imported",
                subLine = "nothing to show",
                modifier = Modifier.testTag("importRow-recentlyImported"),
            )
        }
        PaneNote("importing from this device isn't available.")
    }
}
