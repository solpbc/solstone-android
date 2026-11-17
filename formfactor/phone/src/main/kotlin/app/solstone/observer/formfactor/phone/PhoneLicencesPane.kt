// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

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
    val agpl = phoneText("android.legal.license.agpl.exact")
    val ofl = phoneText("android.legal.license.ofl")
    val lucide = phoneText("android.legal.license.lucide")
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Licences) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))

        // AGPL § 0's four required notices. Owner-register wording, and each one is
        // making the statement the licence asks for rather than describing it.
        PaneNote(phoneText("android.about.copyright.n2026.sol.pbc.fixed"))
        PaneNote(
            phoneText("android.about.solstone.comes.with.no.warranty"),
        )
        PaneNote(
            phoneText("android.about.may.share.change.under.terms"),
        )

        PaneSectionTitle(phoneText("android.about.what.app"))
        PaneCard {
            PaneFactRow(label = phoneText("android.push.title"), value = phoneText("android.licences.agpl30only"))
            PaneRowDivider()
            PaneFactRow(label = phoneText("android.licences.comfortaa"), value = phoneText("android.about.sil.ofl.n1.n1"))
            PaneRowDivider()
            PaneFactRow(label = phoneText("android.licences.lucide"), value = phoneText("android.licences.iscmit"))
            PaneRowDivider()
            PaneFactRow(label = phoneText("android.licences.efflongwordlist"), value = phoneText("android.licences.cc30us"))
        }
        // Third-party proper names keep their own casing; house lowercase is ours.
        PaneNote(phoneText("android.about.comfortaa.typeface.solstone.set.by"))
        PaneNote(phoneText("android.licences.markuses"))
        PaneNote(
            phoneText("android.licences.effwordlist"),
        )

        PaneSectionTitle(phoneText("android.about.gnu.affero.general.public.license"))
        LicenceText(agpl, "licenceTextAgpl")

        PaneSectionTitle(phoneText("android.about.sil.open.font.license.n1"))
        LicenceText(ofl, "licenceTextOfl")

        PaneSectionTitle(phoneText("android.licences.lucide"))
        LicenceText(lucide, "licenceTextLucide")
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
