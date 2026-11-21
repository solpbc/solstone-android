// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The whole app while the store's age answer holds it: one plain line, and at most one action.
 * A null [line] is the moment the answer is being read, and shows only the ground.
 */
@Composable
fun PhoneAgeSignalScreen(
    line: String?,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    PhoneTheme {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("ageSignalScreen"),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (line == null) return@Surface
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = ShellMetrics.screenMargin),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("ageSignalLine"),
                )
                if (action != null) {
                    Spacer(Modifier.height(ShellMetrics.sectionGap))
                    Button(
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ageSignalAction"),
                    ) {
                        Text(action)
                    }
                }
            }
        }
    }
}
