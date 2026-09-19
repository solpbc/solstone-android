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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.solstone.core.identity.JournalMarkPresentation

@Composable
fun PhoneYourJournalPane(
    paired: Boolean = false,
    facts: PhoneJournalFacts = PhoneJournalFacts(),
    presentation: JournalMarkPresentation = JournalMarkPresentation.Generic,
    onConnectJournal: () -> Unit,
    onCheckConnection: () -> Unit = {},
    onForgetJournal: () -> Unit = {},
    mutationFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var confirmingForget by remember { mutableStateOf(false) }
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.YourJournal) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            JournalMarkCard(presentation)
        }
        PaneSectionTitle("connection")
        PaneCard {
            if (paired) {
                PaneFactRow(label = "fingerprint", value = facts.fingerprint)
                PaneRowDivider()
                PaneFactRow(label = "where it lives", value = facts.location)
                PaneRowDivider()
                PaneFactRow(label = "connection", value = facts.connection)
                PaneRowDivider()
                PaneNavRow(
                    label = "check connection",
                    onClick = onCheckConnection,
                    modifier = Modifier.testTag("yourJournalCheckConnection"),
                )
                PaneRowDivider()
                PaneNavRow(
                    label = "pair a new journal",
                    onClick = onConnectJournal,
                    modifier = Modifier.testTag("yourJournalPairNew"),
                )
                PaneRowDivider()
                PaneNavRow(
                    label = "forget this journal",
                    onClick = { confirmingForget = true },
                    modifier = Modifier.testTag("yourJournalForget"),
                )
            } else {
                PaneFactRow(label = "status", value = "not paired")
                PaneRowDivider()
                PaneNavRow(
                    label = "connect a journal",
                    subLine = "scan the pair code your journal shows",
                    onClick = onConnectJournal,
                    modifier = Modifier.testTag("yourJournalConnect"),
                )
            }
        }
        if (mutationFailed) PaneNote("couldn't forget this journal. try again.")
        // The subject register: the solstone app takes in what you share with it, and
        // the verb carries its object. `what this phone takes in` made the hardware the
        // perceiving subject and dropped the object -- never-list rule 1.
        PaneNote(
            "your journal holds what you share with the solstone app on this device. " +
                "until one is connected, everything stays on this device.",
        )
    }
    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text("forget this journal?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingForget = false
                    onForgetJournal()
                }) { Text("forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingForget = false }) { Text("cancel") }
            },
        )
    }
}

/** `settings › this device` (§ 4), backed by device and app state. */
@Composable
fun PhoneThisDevicePane(
    version: String,
    storageUsed: String,
    hapticsEnabled: Boolean,
    onHapticsChanged: (Boolean) -> Unit,
    onManageLocalStorage: () -> Unit,
    onOpenTechnicalDetails: () -> Unit,
    onOpenEventLog: () -> Unit,
    onOpenProblemReports: () -> Unit,
    paired: Boolean,
    onUnpair: () -> Unit,
    mutationFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var confirmingUnpair by remember { mutableStateOf(false) }
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
        if (mutationFailed) PaneNote("couldn't forget this journal. try again.")
        PaneSectionTitle("storage")
        PaneCard {
            PaneFactRow(label = "in use", value = storageUsed)
            PaneRowDivider()
            PaneNavRow(
                label = "manage local storage",
                onClick = onManageLocalStorage,
                modifier = Modifier.testTag("thisDeviceStorage"),
            )
        }
        PaneSectionTitle("settings")
        PaneCard {
            PaneSwitchRow(
                label = "haptics",
                checked = hapticsEnabled,
                onCheckedChange = onHapticsChanged,
                modifier = Modifier.testTag("thisDeviceHaptics"),
            )
            PaneRowDivider()
            PaneNavRow(
                label = "app permissions",
                subLine = "microphone, location and camera, in android settings",
                onClick = { context.openAppSettings() },
                modifier = Modifier.testTag("thisDevicePermissions"),
            )
            PaneRowDivider()
            PaneNavRow(label = "technical details", onClick = onOpenTechnicalDetails)
            PaneRowDivider()
            PaneNavRow(label = "event log", onClick = onOpenEventLog)
            PaneRowDivider()
            PaneNavRow(label = "problem reports", onClick = onOpenProblemReports)
            if (paired) {
                PaneRowDivider()
                PaneNavRow(label = "unpair", onClick = { confirmingUnpair = true })
            }
        }
    }
    if (confirmingUnpair) {
        AlertDialog(
            onDismissRequest = { confirmingUnpair = false },
            title = { Text("forget this journal?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingUnpair = false
                    onUnpair()
                }) { Text("unpair") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnpair = false }) { Text("cancel") }
            },
        )
    }
}

@Composable
fun PhoneTechnicalDetailsPane(
    facts: PhoneJournalFacts,
    onCheckConnection: () -> Unit,
    onOpenEventLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.TechnicalDetails) }) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneFactRow(label = "connection", value = facts.connection)
            PaneRowDivider()
            PaneFactRow(label = "intake", value = facts.intake)
            PaneRowDivider()
            PaneFactRow(label = "reconnects", value = facts.reconnects)
            PaneRowDivider()
            PaneFactRow(label = "errors", value = facts.errors)
            PaneRowDivider()
            PaneFactRow(label = "fingerprint", value = facts.fingerprint)
            PaneRowDivider()
            PaneNavRow(label = "check connection", onClick = onCheckConnection)
            PaneRowDivider()
            PaneNavRow(label = "event log", onClick = onOpenEventLog)
        }
    }
}

@Composable
fun PhoneProblemReportsPane(
    reports: List<String>,
    onSaveReport: () -> Unit,
    onReportProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.ProblemReports) }) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneNavRow(
                label = "save a problem report",
                onClick = onSaveReport,
                modifier = Modifier.testTag("problemReportsSave"),
            )
            PaneRowDivider()
            PaneNavRow(
                label = "report a problem",
                subLine = "review and send on the support site",
                onClick = onReportProblem,
                modifier = Modifier.testTag("problemReportsSend"),
            )
        }
        if (reports.isNotEmpty()) {
            PaneSectionTitle("saved on this device")
            PaneCard {
                reports.forEachIndexed { index, savedAt ->
                    if (index > 0) PaneRowDivider()
                    PaneFactRow(label = "saved", value = savedAt)
                }
            }
        }
    }
}

@Composable
fun PhoneEventLogPane(eventLog: String, modifier: Modifier = Modifier) {
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.EventLog) }) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        SelectionContainer {
            Text(
                text = eventLog.ifBlank { "—" },
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = shellSecondaryInk,
            )
        }
    }
}

/** `settings › notifications` (§ 4), including live permission/channel state. */
@Composable
fun PhoneNotificationsPane(
    enabled: Boolean,
    testFailed: Boolean = false,
    onOpenSettings: () -> Unit,
    onSendTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Notifications) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneFactRow(label = "notifications", value = if (enabled) "on" else "off")
            PaneRowDivider()
            PaneNavRow(
                label = "open notification settings",
                subLine = "android decides whether the solstone app can notify you",
                onClick = onOpenSettings,
                modifier = Modifier.testTag("notificationsOpenSettings"),
            )
            if (enabled) {
                PaneRowDivider()
                PaneNavRow(
                    label = "send test notification",
                    onClick = onSendTest,
                    modifier = Modifier.testTag("notificationsSendTest"),
                )
            }
        }
        // Two locked strings from section 5, verbatim and kept SEPARATE. They are listed
        // there as two lines; joining them with a dash would be authoring a third.
        PaneNote("when there's something worth a look")
        PaneNote("a short heads-up, never the content")
        if (testFailed) {
            PaneNote("couldn't send a test notification. check notification settings, then try again.")
        }
    }
}

/**
 * `settings › help` (§ 4): get help, report a problem, and the support address.
 */
@Composable
fun PhoneHelpPane(
    version: String,
    status: PhoneDefaultDetailStatus,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val build = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
    }.getOrDefault("unknown")
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Help) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneExternalRow(
                label = "get help",
                subLine = "support.solstone.app",
                onClick = { context.openUrl(SUPPORT_SITE_URL) },
                modifier = Modifier.testTag("helpSupportSite"),
            )
            PaneRowDivider()
            PaneExternalRow(
                label = "report a problem",
                subLine = "review and send on the support site",
                onClick = {
                    context.openUrl(
                        supportReportUrl(
                            version = version,
                            build = build,
                            osVersion = Build.VERSION.RELEASE,
                            state = supportState(status),
                        ),
                    )
                },
                modifier = Modifier.testTag("helpReportProblem"),
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
