// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
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
 * @param content Destination content slot. Defaults to the width-class readout.
 */
@Composable
fun PhoneShell(
    shellAttachment: @Composable () -> Unit = {},
    content: @Composable () -> Unit = { PhoneShellDefaultContent() },
) {
    PhoneTheme {
        Scaffold(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { paddingValues ->
            // paddingValues are identically zero because contentWindowInsets is
            // WindowInsets(0, 0, 0, 0). Applying them is the Scaffold contract,
            // not a second inset read. Do not suppress UnusedMaterial3ScaffoldPaddingParameter.
            Box(Modifier.padding(paddingValues)) {
                Row(Modifier.fillMaxSize()) {
                    shellAttachment()
                    Box(Modifier.weight(1f)) { content() }
                }
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
