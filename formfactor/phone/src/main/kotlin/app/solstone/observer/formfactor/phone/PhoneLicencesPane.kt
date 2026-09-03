// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily

/**
 * `settings › about solstone › licenses` — and this pane carries a legal obligation, not a
 * courtesy.
 *
 * 🔴 **AGPL § 0 defines "Appropriate Legal Notices" for an interactive UI**, and § 5(d) attaches
 * the requirement to conveying a modified work. It is specific about the content: a copyright
 * notice, a statement that there is **no warranty**, a statement that licensees **may convey the
 * work under this License**, and **how to view a copy of the License**. This pane shipped two
 * identifier rows and a designer credit — none of the four — and **no copy of either license text
 * was in the APK at all** (verified against the built release artifact, not the source: the only
 * `LICENSE` files in it were androidx's own, in `META-INF`).
 *
 * ⚠ **The SIL OFL has the same shape and the same gap.** OFL 1.1 § 2 requires the copyright notice
 * **and the license** to travel with each copy of the font. `docs/third-party-assets.md` asserted
 * that the attribution is "surfaced to owners in the app's own about › licenses pane", and what was
 * surfaced was the string `SIL OFL 1.1`. An identifier is not a license.
 *
 * ✅ Both texts are vendored into `res/raw/` and rendered below, so the notices are complete
 * offline and do not depend on a network round trip to a licence host.
 *
 * ⛔ Do not replace either text with a link. "How to view a copy of this License" is satisfied most
 * robustly by containing it, and OFL § 2 requires the license to *travel with* the font rather than
 * to be findable.
 */
@Composable
fun PhoneLicencesPane(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val agpl = remember(context) { context.readRawText(R.raw.license_agpl) }
    val ofl = remember(context) { context.readRawText(R.raw.license_ofl) }
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Licences) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))

        // AGPL § 0's four required notices. Owner-register wording, and each one is
        // making the statement the licence asks for rather than describing it.
        PaneNote("copyright © 2026 sol pbc")
        PaneNote(
            "solstone comes with no warranty, to the extent the law allows.",
        )
        PaneNote(
            "you may share and change it under the terms of the GNU Affero General Public " +
                "License, version 3. the full text is below.",
        )

        PaneSectionTitle("what is in this app")
        PaneCard {
            PaneFactRow(label = "solstone", value = "AGPL-3.0-only")
            PaneRowDivider()
            PaneFactRow(label = "Comfortaa", value = "SIL OFL 1.1")
        }
        // Third-party proper names keep their own casing; house lowercase is ours.
        PaneNote("Comfortaa is the typeface solstone is set in, by Johan Aakerlund.")

        PaneSectionTitle("GNU Affero General Public License v3")
        LicenceText(agpl, "licenceTextAgpl")

        PaneSectionTitle("SIL Open Font License 1.1")
        LicenceText(ofl, "licenceTextOfl")
    }
}

@Composable
private fun LicenceText(text: String, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = shellSecondaryInk,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.testTag(tag),
    )
}

/**
 * ⚠ Read once and remembered. A licence text is tens of kilobytes and never changes for a given
 * build, so re-reading it on every recomposition would be work with no possible new answer.
 */
private fun android.content.Context.readRawText(id: Int): String =
    runCatching { resources.openRawResource(id).bufferedReader().use { it.readText() } }
        .getOrDefault("")
