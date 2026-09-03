// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/**
 * `settings › about solstone` (§ 4): version, licenses, open source, privacy policy,
 * terms.
 *
 * ⚠ It was a single unstyled word — `licenses` — on an otherwise blank screen, with no
 * version, no source link and no legal routes at all.
 *
 * ⛔ **`terms` is stated and is not a link.** `solpbc.org/terms` returns 404 (checked
 * 2026-09-01); a row labelled `terms` that opens nothing is precisely the control § 2.4
 * forbids. The footer word stays, the link does not exist yet, and the missing page is
 * flagged rather than linked-to on faith.
 */
@Composable
fun PhoneAboutPane(
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
    version: String = "",
) {
    val context = LocalContext.current
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.AboutSolstone) },
    ) {
        // ⛔ No leading heading — the app bar already says `about solstone`.
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneFactRow(label = "version", value = version.ifBlank { "—" })
            PaneRowDivider()
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
