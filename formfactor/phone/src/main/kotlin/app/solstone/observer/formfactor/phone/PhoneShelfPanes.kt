// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * `settings › your journal` (§ 4).
 *
 * The contract lists the mark, the fingerprint, where it lives, connection, check
 * connection, pair a new journal and forget this journal. Unpaired — the only state
 * this app can reach today, since no Android surface fetches the journal's render-spec
 * — there is no identity, no address and nothing to forget. ✅ So the pane shows the
 * one thing that is true and the one action that works: the generic mark in its card
 * (`journal-mark.md` § 3 + § 4.3), and `connect a journal`.
 *
 * ⚠ § 7 item 5 says the pane behind the shelf's journal row "shows the full card". iOS
 * shipped that in build 70; this is Android's. The identified states below it are owed
 * once the render-spec is on the wire.
 */
@Composable
fun PhoneYourJournalPane(
    onConnectJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.YourJournal) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            JournalMarkCard()
        }
        PaneSectionTitle("connection")
        PaneCard {
            PaneFactRow(label = "status", value = "not paired")
            PaneRowDivider()
            PaneNavRow(
                label = "connect a journal",
                subLine = "scan the pair code your journal shows",
                onClick = onConnectJournal,
                modifier = Modifier.testTag("yourJournalConnect"),
            )
        }
        // The subject register: the solstone app takes in what you share with it, and
        // the verb carries its object. `what this phone takes in` made the hardware the
        // perceiving subject and dropped the object -- never-list rule 1.
        PaneNote(
            "your journal holds what you share with the solstone app on this device. " +
                "until one is connected, everything stays on this device.",
        )
    }
}

/**
 * `settings › this device` (§ 4).
 *
 * The contract lists device name, storage used, haptics, show technical details, event
 * log, problem reports and unpair. Of those, the device's own identity and the
 * installed version are the facts this shell can answer without inventing a reading;
 * the rest are behaviour this app has not built a surface for, and a row that looks
 * like a control but does nothing is what § 2.4 forbids. They are listed as owed in
 * this pass's outcome rather than mocked here.
 */
@Composable
fun PhoneThisDevicePane(
    version: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.ThisDevice) },
    ) {
        // ⛔ No leading section heading: the app bar already names the pane, and a
        // heading that repeats it is the restating defect § 3's source-detail template
        // carried. A pane's FIRST card needs no title; later sections do.
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneFactRow(label = "name", value = deviceName())
            PaneRowDivider()
            PaneFactRow(label = "android", value = Build.VERSION.RELEASE.orEmpty())
            PaneRowDivider()
            PaneFactRow(label = "solstone", value = version.ifBlank { "—" })
        }
        PaneSectionTitle("permissions")
        PaneCard {
            PaneNavRow(
                label = "app permissions",
                subLine = "microphone, location and camera, in android settings",
                onClick = { context.openAppSettings() },
                modifier = Modifier.testTag("thisDevicePermissions"),
            )
        }
        PaneNote(
            "each source asks for what it needs the first time you turn it on. " +
                "you can change any of them in android settings, any time.",
        )
    }
}

/**
 * `settings › notifications` (§ 4).
 *
 * The locked copy is § 5's, verbatim from `cmo/brand/services.md`. The permission is a
 * real system surface, so `open notification settings` performs exactly what it names.
 * ⛔ No `send test notification` row: this app has no test-notification path, and a row
 * that names one would be the § 2.4 violation.
 */
@Composable
fun PhoneNotificationsPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Notifications) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneNavRow(
                label = "open notification settings",
                subLine = "android decides whether the solstone app can notify you",
                onClick = { context.openNotificationSettings() },
                modifier = Modifier.testTag("notificationsOpenSettings"),
            )
        }
        // Two locked strings from section 5, verbatim and kept SEPARATE. They are listed
        // there as two lines; joining them with a dash would be authoring a third.
        PaneNote("when there's something worth a look")
        PaneNote("a short heads-up, never the content")
    }
}

/**
 * `settings › help` (§ 4): the support site, the support address, report a problem.
 *
 * The site and the address are the ones iOS already ships, verbatim — a second address
 * for the same purpose is the cross-platform defect the shared contract exists to
 * prevent. ⛔ No `report a problem` row: this app has no problem-report store, and the
 * support address is the honest route to the same outcome.
 */
@Composable
fun PhoneHelpPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Help) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneExternalRow(
                label = "support site",
                subLine = "support.solstone.app",
                onClick = { context.openUrl(SUPPORT_SITE_URL) },
                modifier = Modifier.testTag("helpSupportSite"),
            )
            PaneRowDivider()
            PaneExternalRow(
                label = "email support",
                subLine = SUPPORT_EMAIL,
                onClick = { context.openMail(SUPPORT_EMAIL) },
                modifier = Modifier.testTag("helpSupportEmail"),
            )
        }
    }
}

private fun deviceName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    return when {
        model.isEmpty() -> manufacturer
        manufacturer.isEmpty() -> model
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
}

private fun Context.openUrl(url: String) {
    startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun Context.openMail(address: String) {
    startActivitySafely(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")))
}

private fun Context.openAppSettings() {
    startActivitySafely(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    startActivitySafely(intent)
}

/**
 * ⚠ A missing handler is not a crash. Every route out of this app goes through an
 * implicit intent, and a device with no browser or no mail client resolves none of
 * them; the app declines quietly rather than taking the owner down with it.
 */
private fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing on this device can open it. Staying put is the honest outcome.
    }
}
