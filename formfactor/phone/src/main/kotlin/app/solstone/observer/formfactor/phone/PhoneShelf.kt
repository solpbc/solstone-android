// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val shelfWidth = 360.dp
private val shelfRowHorizontalPadding = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneShelfSheet(
    drawerState: DrawerState,
    modifier: Modifier = Modifier,
    semanticsVisible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalDrawerSheet(
        drawerState = drawerState,
        // The shelf is the app's own drawer and it opens onto the app's own ground.
        // It was taking Material's default sheet colour, which is the grey the whole
        // shell is not — the same "opening anything from home steps out of the
        // product" defect the iOS pass found across five panes.
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerContentColor = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .width(shelfWidth)
            .then(
                if (semanticsVisible) {
                    Modifier.testTag("phoneShelfSheet")
                } else {
                    Modifier.clearAndSetSemantics { }.testTag("phoneShelfSheet")
                },
            ),
        content = content,
    )
}

@Composable
internal fun PhoneShelfContent(
    shelfOpen: Boolean,
    onNavigate: (PhoneRoute) -> Unit,
    version: String,
    firstRowFocusRequester: FocusRequester,
) {
    var previousShelfOpen by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(shelfOpen) {
        val previous = previousShelfOpen
        previousShelfOpen = shelfOpen
        if (previous == false && shelfOpen) {
            firstRowFocusRequester.requestFocus()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { paneTitle = spokenPaneTitle(PhonePane.SHELF) },
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = headingText(PhonePane.SHELF).orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(horizontal = shelfRowHorizontalPadding)
                .semantics { heading() }
                .testTag("shelfHeading"),
        )
        Spacer(Modifier.height(12.dp))
        PhoneShelfRow(
            text = "your journal",
            rowTag = "shelfRow-yourJournal",
            textTag = "shelfRowText-yourJournal",
            onClick = { onNavigate(PhoneRoute.YourJournal) },
            modifier = Modifier.focusRequester(firstRowFocusRequester),
        )
        PhoneShelfRow(
            text = "this device",
            rowTag = "shelfRow-thisDevice",
            textTag = "shelfRowText-thisDevice",
            onClick = { onNavigate(PhoneRoute.ThisDevice) },
        )
        PhoneShelfRow(
            text = "notifications",
            rowTag = "shelfRow-notifications",
            textTag = "shelfRowText-notifications",
            onClick = { onNavigate(PhoneRoute.Notifications) },
        )
        PhoneShelfRow(
            text = "help",
            rowTag = "shelfRow-help",
            textTag = "shelfRowText-help",
            onClick = { onNavigate(PhoneRoute.Help) },
        )
        PhoneShelfRow(
            text = "about solstone",
            rowTag = "shelfRow-aboutSolstone",
            textTag = "shelfRowText-aboutSolstone",
            onClick = { onNavigate(PhoneRoute.AboutSolstone) },
            showDivider = false,
        )
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        // The footer (§ 4: privacy · terms · version). It was three left-flush `Text`s
        // at x=0, outside the rows' own inset, so it read as unrelated debris below the
        // list rather than as the shelf's own footer.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = shelfRowHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "privacy",
                style = MaterialTheme.typography.bodySmall,
                color = shellSecondaryInk,
                modifier = Modifier.testTag("shelfPrivacy"),
            )
            Text("·", style = MaterialTheme.typography.bodySmall, color = shellSecondaryInk)
            Text(
                text = "terms",
                style = MaterialTheme.typography.bodySmall,
                color = shellSecondaryInk,
                modifier = Modifier.testTag("shelfTerms"),
            )
            Text("·", style = MaterialTheme.typography.bodySmall, color = shellSecondaryInk)
            Text(
                text = version,
                style = MaterialTheme.typography.bodySmall,
                color = shellSecondaryInk,
                modifier = Modifier.testTag("shelfVersion"),
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * A shelf row.
 *
 * It had no height, no divider and no affordance — five `Text`s stacked with no gap,
 * which is why the open drawer read as a paragraph rather than as a list of doors. The
 * chevron is what says "this opens something"; § 4's own table is a list of panes.
 */
@Composable
private fun PhoneShelfRow(
    text: String,
    rowTag: String,
    textTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = ShellMetrics.rowMinHeight)
                .focusable()
                .semantics(mergeDescendants = true) { role = Role.Button }
                .clickable(onClick = onClick)
                .testTag(rowTag)
                .padding(horizontal = shelfRowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.testTag(textTag),
            )
            Icon(
                painter = painterResource(R.drawable.phone_chevron_right),
                contentDescription = null,
                tint = shellSecondaryInk,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = shelfRowHorizontalPadding),
                thickness = ShellMetrics.hairline,
                color = shellHairline,
            )
        }
    }
}
