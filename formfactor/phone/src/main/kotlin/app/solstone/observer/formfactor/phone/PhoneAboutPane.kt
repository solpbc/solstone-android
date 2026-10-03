// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import app.solstone.core.pl.JournalVersionReading

/**
 * `settings › about solstone`: a copyable two-line app and journal version block, licenses,
 * open source, and privacy policy.
 */
@Composable
fun PhoneAboutPane(
    onOpenLicences: () -> Unit,
    reading: JournalVersionReading?,
    copy: (String) -> Boolean = { false },
    modifier: Modifier = Modifier,
    facts: AndroidAboutFacts? = null,
) {
    val context = LocalContext.current
    val aboutFacts = facts ?: remember(context) { readAndroidAboutFacts(context) }
    val block = remember(aboutFacts, reading) {
        phoneAboutBlock(aboutFacts, reading, System.currentTimeMillis())
    }
    var copied by remember(block) { mutableStateOf(false) }
    var copyFailed by remember(block) { mutableStateOf(false) }
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.AboutSolstone) },
    ) {
        // ⛔ No leading heading — the app bar already says `about solstone`.
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneFactRow(
                label = "about",
                value = block,
                valueContent = {
                    SelectionContainer {
                        Text(
                            text = block,
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                            color = shellSecondaryInk,
                        )
                    }
                },
            )
            PaneRowDivider()
            TextButton(onClick = {
                copied = copy(block)
                copyFailed = !copied
            }) {
                Text(if (copied) "copied" else "copy")
            }
            PaneNavRow(
                label = "licenses",
                onClick = onOpenLicences,
                modifier = Modifier.testTag("licencesRow"),
            )
            PaneRowDivider()
            PaneExternalRow(
                label = "open source",
                subLine = "github.com/solpbc/solstone-android",
                onClick = { context.open(SOURCE_URL) },
                modifier = Modifier.testTag("aboutSourceRow"),
            )
            PaneRowDivider()
            PaneExternalRow(
                label = "privacy policy",
                subLine = "solpbc.org/privacy",
                onClick = { openPrivacyPolicy(context) },
                modifier = Modifier.testTag("aboutPrivacyRow"),
            )
        }
        if (copyFailed) PaneNote("couldn't copy. select the text and copy it.")
        PaneNote("solstone is open source, under the AGPL.")
    }
}

private fun Context.open(url: String) {
    try {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        // No browser on this device. Declining beats crashing.
    }
}
