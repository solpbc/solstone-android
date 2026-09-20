// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview

/**
 * Themed phone shell host.
 *
 * @param shellAttachment Slot for persistent navigation chrome shown alongside
 *        the destination content. Defaults to nothing. This module must not
 *        reference navigation types; those sit outside this module's boundary.
 * @param drawerState State for the shelf drawer. Defaults to a closed drawer.
 * @param shelfOpen Whether the shelf is open. It only suppresses the shell
 *        controls that remain visually behind the drawer.
 * @param drawerContent Content shown inside the shelf drawer. Defaults to
 *        nothing so existing shell hosts keep their current presentation.
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
    navigationIcon: @Composable () -> Unit = {},
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    shelfOpen: Boolean = false,
    drawerContent: @Composable ColumnScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit = { PhoneShellDefaultContent() },
) {
    PhoneTheme {
        val gestureInsets = WindowInsets.safeGestures
        ModalNavigationDrawer(
            drawerContent = {
                PhoneShelfSheet(
                    drawerState = drawerState,
                    semanticsVisible = shelfOpen,
                    content = drawerContent,
                )
            },
            drawerState = drawerState,
            gesturesEnabled = true,
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing
                    .union(gestureInsets)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                topBar = {
                    TopAppBar(
                        title = title,
                        navigationIcon = navigationIcon,
                        actions = { statusAction() },
                        modifier = if (shelfOpen) {
                            Modifier.clearAndSetSemantics { }
                        } else {
                            Modifier.testTag("phoneAppBar")
                        },
                    )
                }
            ) { paddingValues ->
                // The pill floats over the content slot, so the Scaffold's own padding does not
                // account for it. Reserve its band here, once, rather than in each surface.
                val contentPadding = remember(paddingValues) {
                    JournalPillBandPadding(paddingValues, ShellMetrics.journalPillSlot)
                }
                Box(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        shellAttachment()
                        Box(Modifier.weight(1f)) { content(contentPadding) }
                    }
                    PhoneJournalPill(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(gestureInsets),
                        hideSemantics = shelfOpen,
                        mark = journalMark,
                    )
                }
            }
        }
    }
}

/**
 * The Scaffold's own padding with the floating journal pill's band added to the bottom.
 *
 * 🔴 A wrapper, ⛔ never a rebuilt [PaddingValues]. The Scaffold's value is computed from the
 * MEASURED top bar, so reading `calculateTopPadding()` during composition and freezing the result
 * captures it before the bar has been measured: the deck then started 64dp from the top instead
 * of 113dp and the first tile sat under the app bar. Only the instrumented gate could see it —
 * a JVM test has no layout pass to be early for. Delegating keeps every read at its original
 * call time.
 */
private class JournalPillBandPadding(
    private val base: PaddingValues,
    private val band: Dp,
) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateLeftPadding(layoutDirection)

    override fun calculateTopPadding(): Dp = base.calculateTopPadding()

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        base.calculateRightPadding(layoutDirection)

    override fun calculateBottomPadding(): Dp = base.calculateBottomPadding() + band
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
