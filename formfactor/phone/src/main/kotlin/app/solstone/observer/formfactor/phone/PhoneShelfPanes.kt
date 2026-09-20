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
    journalKeptItsRecord: Boolean = false,
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
        // ⛔ No `connection` section title here: the card below already carries a `connection`
        // row, and a heading repeating its own row's label reads as two different things.
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            if (paired) {
                PaneFactRow(label = "fingerprint", value = facts.fingerprint)
                PaneRowDivider()
                PaneFactRow(label = "how your phone connects", value = facts.location)
                PaneRowDivider()
                PaneFactRow(label = "connection", value = facts.connection)
                PaneRowDivider()
                PaneNavRow(
                    label = "check connection",
                    subLine = facts.check,
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
                    subLine = "scan the pairing code your journal shows",
                    onClick = onConnectJournal,
                    modifier = Modifier.testTag("yourJournalConnect"),
                )
            }
        }
        // ⚠ `mutationFailed` reaches this pane only when the owner pressed THIS pane's control.
        // Driving both panes off one flag named the other pane's action for something the owner
        // never pressed.
        if (mutationFailed) PaneNote("couldn't forget this journal. try again.")
        if (journalKeptItsRecord) PaneNote(JOURNAL_KEPT_ITS_RECORD)
        // The subject register: the solstone app takes in what you share with it, and
        // the verb carries its object. `what this phone takes in` made the hardware the
        // perceiving subject and dropped the object -- never-list rule 1.
        PaneNote(
            if (paired) {
                "your journal holds what you share with the solstone app on this device."
            } else {
                // ⛔ Not the paired sentence plus a caveat. Nothing from this phone is in a
                // journal yet, so a present-tense `your journal holds…` is false on the one
                // screen where the owner is deciding whether to pair.
                "until a journal is connected, everything stays on this device."
            },
        )
    }
    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text("forget this journal?") },
            text = { Text(FORGET_JOURNAL_BODY) },
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
    journalKeptItsRecord: Boolean = false,
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
        // The row, the dialog and the button in this pane all say `unpair`; the failure note
        // is the same action and says it too.
        if (mutationFailed) PaneNote("couldn't unpair this device. try again.")
        if (journalKeptItsRecord) PaneNote(JOURNAL_KEPT_ITS_RECORD)
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
            // The row says `unpair`, so the question and the button say `unpair` too. Titling it
            // `forget this journal?` over an `unpair` button gave one action three words.
            title = { Text("unpair this device?") },
            text = { Text(FORGET_JOURNAL_BODY) },
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
            // ⛔ No `reconnects` / `errors` rows. Nothing ever wrote them, so they read `—`
            // forever, which is a measurement claim with no measurement behind it. The event log
            // below carries the same ground with real events in it.
            PaneFactRow(label = "fingerprint", value = facts.fingerprint)
            PaneRowDivider()
            PaneNavRow(label = "check connection", subLine = facts.check, onClick = onCheckConnection)
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

// `check connection` ran a real probe and said nothing about it: no spinner, no result, no
// timestamp. These are what it says now.
const val CHECK_CONNECTION_RUNNING = "checking…"
const val CHECK_CONNECTION_REACHED = "reached your journal"
const val CHECK_CONNECTION_UNREACHED = "couldn't reach your journal"

// Unpairing asks the journal to drop this device too, and the journal is not always reachable
// when it is asked. Saying nothing would leave the owner believing both halves happened.
// ⚠ Names the PLACE, ⛔ not the control. The journal renders one removal control per row and
// which one depends on delivery history: a device that never delivered gets `forget this device`
// and a device that has gets `unpair`. Naming either is wrong for half the owners who see this.
internal const val JOURNAL_KEPT_ITS_RECORD =
    "your journal still lists this device. open your journal, find it under network, and " +
        "remove it there."

// ⚠ Three states, not two, and the third is not the one it first looks like. `forget()` clears
// the identity, credential and endpoint and nothing else — ⛔ **it does not clear the spool or the
// segment table**, and `segmentsForDrain` selects on `stream` + `state` alone (`home_instance_id`
// is written null by both writers and read nowhere). So anything already taken in and not yet
// delivered survives, and drains to whichever journal this phone pairs to next. A confirm that
// partitions the world into "more" and "already reached" leaves that population in neither.
internal const val FORGET_JOURNAL_BODY =
    "nothing more from this device goes into your journal. what already reached it stays " +
        "there, and anything still waiting won't unless you pair again."

// ⛔ Deliberately does NOT enumerate the events. Four review rounds each found a different
// writer missing from the list — the widget's own switch, a sync that threw before its emit
// point — because an enumeration is a completeness claim and this log's writers are not a
// closed set. What the owner needs here is that an empty log is normal and what it is for.
internal const val EVENT_LOG_EMPTY =
    "nothing yet. this fills as the app runs, and saving a problem report puts a copy of it in " +
        "that report."

@Composable
fun PhoneEventLogPane(eventLog: String, modifier: Modifier = Modifier) {
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.EventLog) }) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        SelectionContainer {
            Text(
                text = eventLog.ifBlank { EVENT_LOG_EMPTY },
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
