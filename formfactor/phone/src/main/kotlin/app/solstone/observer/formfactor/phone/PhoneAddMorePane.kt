// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/**
 * `add more` (§ 3): *"sources not on home first, the ones already on home below. Every
 * row opens that source's own view."*
 *
 * ⚠ It rendered one flat list of every source with no grouping, no glyph and no
 * sub-line, so the pane could not answer the only question it exists to answer — what
 * is *not* on home. The split is the pane's whole structure, and the section words are
 * the ones iOS already ships rather than a second set for the same idea.
 */
@Composable
fun PhoneAddMorePane(
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    isOnHome: (String) -> Boolean = { true },
) {
    val ids = phoneSourceLabels.keys.toList()
    val notOnHome = ids.filterNot(isOnHome)
    val onHome = ids.filter(isOnHome)
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.AddMore) },
    ) {
        if (notOnHome.isNotEmpty()) {
            PaneSectionTitle("not on home")
            PaneCard {
                notOnHome.forEachIndexed { index, id ->
                    if (index > 0) PaneRowDivider()
                    PaneNavRow(
                        label = sourceLabel(id),
                        onClick = { onOpenSource(id) },
                        modifier = Modifier.testTag("addMoreRow-$id"),
                    )
                }
            }
        }
        if (onHome.isNotEmpty()) {
            PaneSectionTitle("already on home")
            PaneCard {
                onHome.forEachIndexed { index, id ->
                    if (index > 0) PaneRowDivider()
                    PaneNavRow(
                        label = sourceLabel(id),
                        onClick = { onOpenSource(id) },
                        modifier = Modifier.testTag("addMoreRow-$id"),
                    )
                }
            }
        }
        PaneNote("open any source to change what it does, and to add or remove its tile.")
    }
}
