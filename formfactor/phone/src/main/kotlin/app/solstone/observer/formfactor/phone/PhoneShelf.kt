// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val shelfWidth = 360.dp
private val shelfRowHorizontalPadding = 28.dp

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
            .semantics { paneTitle = PhonePane.SHELF.paneTitle },
    ) {
        Text(
            text = headingText(PhonePane.SHELF).orEmpty(),
            modifier = Modifier
                .padding(horizontal = shelfRowHorizontalPadding)
                .testTag("shelfHeading"),
        )
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
        )
        Text(text = "privacy", modifier = Modifier.testTag("shelfPrivacy"))
        Text(text = "terms", modifier = Modifier.testTag("shelfTerms"))
        Text(text = version, modifier = Modifier.testTag("shelfVersion"))
    }
}

@Composable
private fun PhoneShelfRow(
    text: String,
    rowTag: String,
    textTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusable()
            .semantics(mergeDescendants = true) { }
            .clickable(onClick = onClick)
            .testTag(rowTag)
            .padding(horizontal = shelfRowHorizontalPadding),
    ) {
        Text(text = text, modifier = Modifier.testTag(textTag))
    }
}
