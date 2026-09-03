// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The parts every pane is built from.
 *
 * ⚠ **Four of the five shelf panes rendered an empty `Box`** — `your journal`, `this
 * device`, `notifications` and `help` each opened a completely blank screen with only
 * the app bar on it, and `about solstone` was one unstyled word. § 4's table says what
 * each holds. This is the same class of defect as iOS's unbuilt § 7.5 requirement, at
 * four times the scale: a spec saying "the pane shows X" is not evidence any client
 * shows X.
 *
 * 🔴 **Restraint is deliberate.** Each pane below states only what this app can honestly
 * answer today and offers only actions that genuinely work. § 2.4 forbids a control
 * that cannot perform what it names, and a row that looks tappable and is not is that
 * control. Rows the contract lists but the app cannot yet supply are **absent**, not
 * faked — and they are named in the outcome so the gap is visible rather than papered.
 */
@Composable
internal fun PhonePaneScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 96.dp),
        content = content,
    )
}

/** A section heading inside a pane. Comfortaa names things. */
@Composable
internal fun PaneSectionTitle(text: String, modifier: Modifier = Modifier) {
    Spacer(Modifier.height(ShellMetrics.sectionGap))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.semantics { heading() },
    )
    Spacer(Modifier.height(ShellMetrics.sectionSpacing))
}

/** A card that groups rows or facts, on the shell's surface with its hairline. */
@Composable
internal fun PaneCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .shellSurface(shellSurface, shellHairline, ShellMetrics.cardShape)
            .padding(vertical = 4.dp),
        content = content,
    )
}

/**
 * A fact: a label and its value. **Not tappable and carrying no affordance** — it
 * states something true and offers nothing, which is exactly what it should look like.
 */
@Composable
internal fun PaneFactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = ShellMetrics.surfacePadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = shellSecondaryInk,
        )
    }
}

/** A row that opens something inside the app. */
@Composable
internal fun PaneNavRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subLine: String? = null,
) {
    PaneActionRow(
        label = label,
        subLine = subLine,
        glyph = R.drawable.phone_chevron_right,
        onClick = onClick,
        modifier = modifier,
    )
}

/** A row that leaves the app — a web page, a mail composer. */
@Composable
internal fun PaneExternalRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subLine: String? = null,
) {
    PaneActionRow(
        label = label,
        subLine = subLine,
        glyph = R.drawable.phone_open_external,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun PaneActionRow(
    label: String,
    subLine: String?,
    glyph: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ShellMetrics.rowMinHeight)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = ShellMetrics.surfacePadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subLine != null) {
                Text(
                    text = subLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = shellSecondaryInk,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = shellSecondaryInk,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The hairline between two rows inside a card. */
@Composable
internal fun PaneRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = ShellMetrics.surfacePadding),
        thickness = ShellMetrics.hairline,
        color = shellHairline,
    )
}

/** Explanatory copy under a section — never a control, so it carries no affordance. */
@Composable
internal fun PaneNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = shellSecondaryInk,
        modifier = modifier.padding(top = ShellMetrics.sectionSpacing),
    )
}

/**
 * A row for something the product does not offer here yet.
 *
 * ⛔ Deliberately **non-interactive** — no click semantics, no chevron, no list-item
 * affordance. The import pane established this treatment (`photos` / `not available`)
 * for exactly this reason: directing an owner to tap a row that cannot do anything is
 * worse than saying plainly that it is not there.
 */
@Composable
internal fun PaneUnavailableRow(
    label: String,
    subLine: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = ShellMetrics.rowMinHeight)
            .padding(horizontal = ShellMetrics.surfacePadding, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = shellSecondaryInk,
        )
        Text(
            text = subLine,
            style = MaterialTheme.typography.bodySmall,
            color = shellSecondaryInk,
        )
    }
}

internal const val SUPPORT_SITE_URL = "https://support.solstone.app"
internal const val SUPPORT_EMAIL = "support@solstone.app"
internal const val PRIVACY_URL = "https://solpbc.org/privacy"
internal const val SOURCE_URL = "https://github.com/solpbc/solstone-android"

/**
 * Opens the published privacy policy.
 *
 * ⚠ `solpbc.org`, not `solstone.app`, and that is settled rather than convenient:
 * `solpbc.org/privacy` is the policy the org publishes and the URL registered as the
 * store-submission privacy URL (`clo/matters.md` matter 16, discharged 2026-08-28).
 * `solstone.app/privacy` 404s by design — the founder ruled 2026-07-04 that a scoped notice
 * gets its own URL only when the flagship policy cannot carry the disclosure, and here it can.
 * ⛔ Do not mint a second privacy page for this app.
 *
 * One function, so the shelf footer and the about pane's row cannot drift to two URLs.
 */
internal fun openPrivacyPolicy(context: android.content.Context) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse(PRIVACY_URL),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        // No browser on this device. Declining beats crashing.
    }
}
