// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview

/**
 * Themed phone shell host.
 *
 * @param shellAttachment Slot for persistent navigation chrome shown alongside
 *        the destination content. Defaults to nothing. This module must not
 *        reference navigation- or state-holder types; those sit outside this
 *        module's boundary.
 * @param content Destination content slot. Receives Scaffold paddingValues to
 *        use as lazy-grid contentPadding so tiles can scroll under the app bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneShell(
    shellAttachment: @Composable () -> Unit = {},
    title: @Composable () -> Unit = {},
    statusAction: @Composable RowScope.() -> Unit = {},
    journalMark: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit = { PhoneShellDefaultContent() },
) {
    PhoneTheme {
        val gestureInsets = WindowInsets.safeGestures
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing
                .union(gestureInsets)
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            topBar = {
                TopAppBar(
                    title = title,
                    actions = { statusAction() },
                    modifier = Modifier.testTag("phoneAppBar"),
                )
            },
        ) { paddingValues ->
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    shellAttachment()
                    Box(Modifier.weight(1f)) { content(paddingValues) }
                }
                PhoneJournalPill(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(gestureInsets),
                    mark = journalMark,
                )
            }
        }
    }
}

@Composable
internal fun PhoneShellDefaultContent() {
    val minWidthDp = currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)
        .windowSizeClass
        .minWidthDp
    val widthClass = classifyWindowWidth(minWidthDp)
    Text(
        text = minWidthDp.toString(),
        modifier = Modifier
            .testTag("minWidthDp")
            .semantics { contentDescription = widthClass.name },
    )
}

@Preview(showBackground = true)
@Composable
private fun PhoneShellPreview() {
    PhoneShell()
}
