// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/**
 * `settings › about solstone › licenses`.
 *
 * It stated `AGPL-3.0-only` and nothing else — true of this app's own source and silent
 * about everything vendored into it. The brand face is the live case: Comfortaa ships
 * inside the APK under the SIL Open Font License, which requires the license to travel
 * with the font. A licenses pane that omits the one third-party asset in the build is
 * the pane not doing its job.
 */
@Composable
fun PhoneLicencesPane(
    modifier: Modifier = Modifier,
) {
    PhonePaneScaffold(
        modifier.semantics(mergeDescendants = true) {
            paneTitle = spokenPaneTitle(PhoneRoute.Licences)
        },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneFactRow(label = "solstone", value = "AGPL-3.0-only")
            PaneRowDivider()
            PaneFactRow(label = "Comfortaa", value = "SIL OFL 1.1")
        }
        // Third-party proper names keep their own casing; house lowercase is ours.
        PaneNote("Comfortaa is the typeface solstone is set in, by Johan Aakerlund.")
    }
}
