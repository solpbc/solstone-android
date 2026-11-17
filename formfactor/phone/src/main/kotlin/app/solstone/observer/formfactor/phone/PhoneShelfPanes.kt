// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

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
import androidx.compose.material3.MaterialTheme
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
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.journalRefusesThisDevice
import app.solstone.core.identity.JournalMarkPresentation

@Composable
fun PhoneYourJournalPane(
    paired: Boolean = false,
    presentation: JournalMarkPresentation = JournalMarkPresentation.Generic,
    onConnectJournal: () -> Unit,
    onForgetJournal: () -> Unit = {},
    mutationFailed: Boolean = false,
    onSetOneUp: () -> Unit = {},
    onSendLink: () -> Unit = {},
    onAlreadyHaveOne: () -> Unit = onConnectJournal,
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
            if (!paired) {
                Spacer(Modifier.height(ShellMetrics.sectionGap))
                PhoneJournalSetup(
                    onSetOneUp = onSetOneUp,
                    onSendLink = onSendLink,
                    onAlreadyHaveOne = onAlreadyHaveOne,
                )
            }
        }
        if (paired) {
            Spacer(Modifier.height(ShellMetrics.sectionGap))
            PaneCard {
                // ⛔ No `fingerprint` row here. `mobile-shell-android.md` § 3.3.1 already ruled it
                // out of the glance — *"minus fingerprint (a verification detail — see technical
                // details below)"* — and `PhoneTechnicalDetailsPane` carries it. Rendering it in
                // both places made the same 64-hex digest a glance fact on one surface and a
                // diagnostic on the other. iOS reaches the same answer with its own
                // `DisclosureGroup("technical details")`.
                // ⛔ No connection / check-connection rows here. Status already shows
                // `how your phone connects`; technical details already show `connection`
                // and `check connection`.
                PaneNavRow(
                    label = phoneText("android.shelf.pair.new.journal"),
                    onClick = onConnectJournal,
                    modifier = Modifier.testTag("yourJournalPairNew"),
                )
                PaneRowDivider()
                PaneNavRow(
                    label = phoneText("android.shelf.forget.journal"),
                    onClick = { confirmingForget = true },
                    modifier = Modifier.testTag("yourJournalForget"),
                )
            }
        }
        // ⚠ `mutationFailed` reaches this pane only when the owner pressed THIS pane's control.
        // Driving both panes off one flag named the other pane's action for something the owner
        // never pressed.
        if (mutationFailed) PaneNote(phoneText("android.shelf.couldn.t.forget.journal.try"))
        // The subject register: the solstone app takes in what you share with it, and
        // the verb carries its object. `what this phone takes in` made the hardware the
        // perceiving subject and dropped the object -- never-list rule 1.
        if (paired) {
            PaneNote(phoneText("android.shelf.journal.holds.what.share.with"))
        }
    }
    if (confirmingForget) {
        AlertDialog(
            onDismissRequest = { confirmingForget = false },
            title = { Text(phoneText("android.shelf.forget.journal.n2")) },
            text = { Text(FORGET_JOURNAL_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingForget = false
                    onForgetJournal()
                }) { Text(phoneText("android.shelf.forget")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingForget = false }) { Text(phoneText("android.phone.cancel")) }
            },
        )
    }
}

/** `settings › this device` (§ 4), backed by device and app state. */
@Composable
fun PhoneThisDevicePane(
    version: String,
    hapticsEnabled: Boolean,
    onHapticsChanged: (Boolean) -> Unit,
    onOpenTechnicalDetails: () -> Unit,
    onOpenEventLog: () -> Unit,
    onOpenProblemReports: () -> Unit,
    paired: Boolean,
    onUnpair: () -> Unit,
    deviceChoicePending: Boolean = false,
    onDeviceChoice: () -> Unit = {},
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
            PaneFactRow(label = phoneText("android.shelf.name"), value = deviceName())
            PaneRowDivider()
            PaneFactRow(label = phoneText("android.about.android"), value = Build.VERSION.RELEASE.orEmpty())
            PaneRowDivider()
            PaneFactRow(label = phoneText("android.push.title"), value = version.ifBlank { phoneText("android.ownerfacts.line") })
        }
        // The row, the dialog and the button in this pane all say `unpair`; the failure note
        // is the same action and says it too.
        if (mutationFailed) PaneNote(phoneText("android.shelf.couldn.t.unpair.device.try"))
        PaneSectionTitle(phoneText("android.shelf.settings"))
        PaneCard {
            PaneSwitchRow(
                label = phoneText("android.shelf.haptics"),
                checked = hapticsEnabled,
                onCheckedChange = onHapticsChanged,
                modifier = Modifier.testTag("thisDeviceHaptics"),
            )
            PaneRowDivider()
            PaneNavRow(
                label = phoneText("android.shelf.app.permissions"),
                subLine = phoneText("android.shelf.microphone.location.camera.android.settings"),
                onClick = { context.openAppSettings() },
                modifier = Modifier.testTag("thisDevicePermissions"),
            )
            PaneRowDivider()
            PaneNavRow(label = phoneText("android.migration.technical.details"), onClick = onOpenTechnicalDetails)
            PaneRowDivider()
            PaneNavRow(label = phoneText("android.shelf.event.log"), onClick = onOpenEventLog)
            PaneRowDivider()
            PaneNavRow(label = phoneText("android.shelf.problem.reports"), onClick = onOpenProblemReports)
            if (paired && deviceChoicePending) {
                PaneRowDivider()
                PaneNavRow(label = phoneText("android.shelf.device.choice"), value = phoneText("android.shelf.not.answered"), onClick = onDeviceChoice)
            }
            if (paired) {
                PaneRowDivider()
                PaneNavRow(label = phoneText("android.shelf.unpair"), onClick = { confirmingUnpair = true })
            }
        }
    }
    if (confirmingUnpair) {
        AlertDialog(
            onDismissRequest = { confirmingUnpair = false },
            // The row says `unpair`, so the question and the button say `unpair` too. Titling it
            // `forget this journal?` over an `unpair` button gave one action three words.
            title = { Text(phoneText("android.shelf.unpair.device")) },
            text = { Text(FORGET_JOURNAL_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingUnpair = false
                    onUnpair()
                }) { Text(phoneText("android.shelf.unpair")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnpair = false }) { Text(phoneText("android.phone.cancel")) }
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
    gapDetails: String = phoneText("android.gap.not.run"),
) {
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.TechnicalDetails) }) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneFactRow(label = phoneText("android.shelf.connection"), value = facts.connection)
            PaneRowDivider()
            PaneFactRow(label = phoneText("android.push.intake"), value = facts.intake)
            PaneRowDivider()
            // ⛔ No `reconnects` / `errors` rows. Nothing ever wrote them, so they read `—`
            // forever, which is a measurement claim with no measurement behind it. The event log
            // below carries the same ground with real events in it.
            PaneFactRow(label = phoneText("android.shelf.fingerprint"), value = facts.fingerprint)
            PaneRowDivider()
            // The saved addresses this phone tries before the relay.
            PaneFactRow(label = phoneText("android.shelf.address"), value = facts.address)
            PaneRowDivider()
            PaneNavRow(label = phoneText("android.shelf.check.connection"), subLine = facts.check, onClick = onCheckConnection)
            PaneRowDivider()
            PaneNavRow(label = phoneText("android.shelf.event.log"), onClick = onOpenEventLog)
        }
        PaneSectionTitle(phoneText("android.shelf.gap.record"))
        PaneCard {
            Text(
                text = gapDetails,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("gapTechnicalDetails"),
            )
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
                label = phoneText("android.shelf.save.problem.report"),
                onClick = onSaveReport,
                modifier = Modifier.testTag("problemReportsSave"),
            )
            PaneRowDivider()
            PaneNavRow(
                label = phoneText("android.shelf.report.problem"),
                subLine = phoneText("android.shelf.review.send.support.site"),
                onClick = onReportProblem,
                modifier = Modifier.testTag("problemReportsSend"),
            )
        }
        if (reports.isNotEmpty()) {
            PaneSectionTitle(phoneText("android.shelf.saved.device"))
            PaneCard {
                reports.forEachIndexed { index, savedAt ->
                    if (index > 0) PaneRowDivider()
                    PaneFactRow(label = phoneText("android.shelf.saved"), value = savedAt)
                }
            }
        }
    }
}

// `check connection` ran a real probe and said nothing about it: no spinner, no result, no
// timestamp. These are what it says now.
val CHECK_CONNECTION_RUNNING get() = phoneText("android.shelf.checking")
val CHECK_CONNECTION_REACHED get() = phoneText("android.shelf.reached.journal")
val CHECK_CONNECTION_UNREACHED get() = phoneText("android.shelf.couldn.t.reach.journal")

/**
 * The failed check, naming the address only when one was actually dialed. A check that stopped
 * before dialing (a missing credential or identity) has no address to name, and keeps the plain
 * words.
 */
fun checkConnectionUnreached(address: String?): String =
    address?.takeIf(String::isNotBlank)?.let { phoneText("android.shelf.couldn.t.reach.journal.at" , "it" to (it)) } ?: CHECK_CONNECTION_UNREACHED

/**
 * What a finished `check connection` says. A peer that is not the paired journal answering is not
 * "couldn't reach": the check names what the status pane names.
 */
fun checkConnectionResult(probed: HarnessPlStatus?): String = when {
    probed?.journalRefusesThisDevice() == true -> STATUS_REFUSED_LEAD
    else -> checkConnectionAnswer(probed)
}

private fun checkConnectionAnswer(probed: HarnessPlStatus?): String = when (probed) {
    is HarnessPlStatus.Reachable -> CHECK_CONNECTION_REACHED
    is HarnessPlStatus.PairedButUnreachable -> probed.unknownJournal
        ?.let { unknownJournalRowText(it.address) }
        ?: checkConnectionUnreached(probed.address)
    else -> CHECK_CONNECTION_UNREACHED
}

// ⚠ Three states, not two, and the third is not the one it first looks like. `forget()` clears
// the identity, credential and endpoint and nothing else — ⛔ **it does not clear the spool or the
// segment table**, and `segmentsForDrain` selects on `stream` + `state` alone (`home_instance_id`
// records only which journal a send attempt went to, so a back-off earned at one journal never
// delays the next). So anything already taken in and not yet delivered survives, and drains to
// whichever journal this phone pairs to next. The confirm
// names that third population — anything still waiting — and says it goes to the journal you
// pair next.
internal val FORGET_JOURNAL_BODY get() = phoneText("android.shelf.nothing.more.from.device.goes")

// ⛔ Deliberately does NOT enumerate the events. Four review rounds each found a different
// writer missing from the list — the widget's own switch, a sync that threw before its emit
// point — because an enumeration is a completeness claim and this log's writers are not a
// closed set. What the owner needs here is that an empty log is normal and what it is for.
internal val EVENT_LOG_EMPTY get() = phoneText("android.shelf.nothing.yet.fills.as.app")

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

sealed interface PhoneJournalNotificationRow {
    data object On : PhoneJournalNotificationRow
    data object NeedsDeliveryApp : PhoneJournalNotificationRow
    data object ChooseDeliveryApp : PhoneJournalNotificationRow
    data object InsecureAddress : PhoneJournalNotificationRow
    data class DeliveryAppStopped(val appName: String) : PhoneJournalNotificationRow
}

internal val JOURNAL_FROM_YOUR_JOURNAL get() = phoneText("android.phone.from.journal")
internal val JOURNAL_NOTIFICATIONS_ON get() = phoneText("android.push.on")
internal val JOURNAL_SWITCH_SUB get() = phoneText("android.shelf.off.until.turn.journal.encrypts")
internal val JOURNAL_DELIVERED_BY get() = phoneText("android.shelf.delivered.by")
internal val JOURNAL_NOT_REACHING get() = phoneText("android.shelf.not.reaching.phone")
internal val JOURNAL_NO_APP_NOTE get() = phoneText("android.shelf.notifications.from.journal.need.separate")
internal val JOURNAL_GET_NTFY get() = phoneText("android.shelf.get.ntfy")
internal val JOURNAL_GET_NTFY_SUB get() = phoneText("android.shelf.free.open.source.from.f")
internal const val JOURNAL_NTFY_URL = "https://f-droid.org/packages/io.heckel.ntfy/"
internal val JOURNAL_CHOOSE_VALUE get() = phoneText("android.shelf.choose.app.deliver.notifications")
internal val JOURNAL_CHOOSE_SUB get() = phoneText("android.shelf.more.than.one.app.phone")
// Row D's wording is loose for endpoints distributors do not produce.
internal val JOURNAL_INSECURE_NOTE get() = phoneText("android.shelf.app.that.delivers.notifications.uses")

internal fun journalDeliveryAppStoppedNote(app: String): String =
    phoneText("android.shelf.app.installed.but.needs.be" , "app" to (app))

/** `settings › notifications` (§ 4), including live permission/channel state. */
@Composable
fun PhoneNotificationsPane(
    enabled: Boolean,
    testFailed: Boolean = false,
    onOpenSettings: () -> Unit,
    onSendTest: () -> Unit,
    journalPushEnabled: Boolean = false,
    journalNotificationRow: PhoneJournalNotificationRow? = null,
    onChooseJournalDeliveryApp: () -> Unit = {},
    journalPushOn: Boolean = false,
    onJournalPushChange: (Boolean) -> Unit = {},
    journalDeliveredBy: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Notifications) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneFactRow(label = phoneText("android.phone.notifications"), value = if (enabled) phoneText("android.push.on") else phoneText("android.push.off"))
            PaneRowDivider()
            PaneNavRow(
                label = phoneText("android.shelf.open.notification.settings"),
                subLine = phoneText("android.shelf.android.decides.whether.solstone.app"),
                onClick = onOpenSettings,
                modifier = Modifier.testTag("notificationsOpenSettings"),
            )
            if (enabled) {
                PaneRowDivider()
                PaneNavRow(
                    label = phoneText("android.shelf.send.test.notification"),
                    onClick = onSendTest,
                    modifier = Modifier.testTag("notificationsSendTest"),
                )
            }
            if (journalPushEnabled) {
                PaneRowDivider()
                PaneSwitchRow(
                    label = JOURNAL_FROM_YOUR_JOURNAL,
                    subLine = JOURNAL_SWITCH_SUB,
                    checked = journalPushOn,
                    onCheckedChange = onJournalPushChange,
                    switchModifier = Modifier.testTag("notificationsJournalSwitch"),
                )
                if (journalPushOn && journalDeliveredBy != null) {
                    PaneRowDivider()
                    PaneNavRow(
                        label = JOURNAL_DELIVERED_BY,
                        value = journalDeliveredBy,
                        onClick = onChooseJournalDeliveryApp,
                        modifier = Modifier.testTag("notificationsDeliveredBy"),
                    )
                }
            }
            if (journalPushEnabled && journalPushOn && journalNotificationRow != null && journalNotificationRow != PhoneJournalNotificationRow.On) {
                PaneRowDivider()
                when (journalNotificationRow) {
                    // The switch already says on.
                    PhoneJournalNotificationRow.On -> Unit
                    PhoneJournalNotificationRow.NeedsDeliveryApp -> {
                        PaneFactRow(label = JOURNAL_FROM_YOUR_JOURNAL, value = JOURNAL_NOT_REACHING)
                        PaneRowDivider()
                        PaneExternalRow(
                            label = JOURNAL_GET_NTFY,
                            subLine = JOURNAL_GET_NTFY_SUB,
                            onClick = { context.openUrl(JOURNAL_NTFY_URL) },
                            modifier = Modifier.testTag("notificationsGetNtfy"),
                        )
                    }
                    PhoneJournalNotificationRow.ChooseDeliveryApp -> {
                        PaneNavRow(
                            label = JOURNAL_FROM_YOUR_JOURNAL,
                            value = JOURNAL_CHOOSE_VALUE,
                            subLine = JOURNAL_CHOOSE_SUB,
                            onClick = onChooseJournalDeliveryApp,
                            modifier = Modifier.testTag("notificationsChooseApp"),
                        )
                    }
                    PhoneJournalNotificationRow.InsecureAddress -> {
                        PaneFactRow(label = JOURNAL_FROM_YOUR_JOURNAL, value = JOURNAL_NOT_REACHING)
                    }
                    is PhoneJournalNotificationRow.DeliveryAppStopped -> {
                        PaneFactRow(label = JOURNAL_FROM_YOUR_JOURNAL, value = JOURNAL_NOT_REACHING)
                    }
                }
            }
        }
        if (journalPushEnabled && journalPushOn && journalNotificationRow != null) {
            when (journalNotificationRow) {
                PhoneJournalNotificationRow.NeedsDeliveryApp -> {
                    PaneNote(JOURNAL_NO_APP_NOTE)
                }
                PhoneJournalNotificationRow.InsecureAddress -> {
                    PaneNote(JOURNAL_INSECURE_NOTE)
                }
                is PhoneJournalNotificationRow.DeliveryAppStopped -> {
                    PaneNote(journalDeliveryAppStoppedNote(journalNotificationRow.appName))
                }
                PhoneJournalNotificationRow.On,
                PhoneJournalNotificationRow.ChooseDeliveryApp -> Unit
            }
        }
        // Two locked strings from section 5, verbatim and kept SEPARATE. They are listed
        // there as two lines; joining them with a dash would be authoring a third.
        PaneNote(phoneText("android.shelf.when.there.s.something.worth"))
        PaneNote(phoneText("android.push.short.heads.up.never.content"))
        if (testFailed) {
            PaneNote(phoneText("android.shelf.couldn.t.send.test.notification"))
        }
    }
}

/**
 * `settings › help` (§ 4): get help, report a problem, and the support address.
 */
@Composable
fun PhoneHelpPane(
    reading: app.solstone.core.pl.JournalVersionReading?,
    status: PhoneDefaultDetailStatus,
    modifier: Modifier = Modifier,
    aboutBlock: String? = null,
) {
    val context = LocalContext.current
    val reportFacts = remember(context) { readAndroidAboutFacts(context) }
    val reportAbout = aboutBlock ?: rememberPhoneAboutBlock(reportFacts, reading)
    PhonePaneScaffold(
        modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.Help) },
    ) {
        Spacer(Modifier.height(ShellMetrics.sectionGap))
        PaneCard {
            PaneExternalRow(
                label = phoneText("android.shelf.get.help"),
                subLine = phoneText("android.shelf.support.solstone.app"),
                onClick = { context.openUrl(SUPPORT_SITE_URL) },
                modifier = Modifier.testTag("helpSupportSite"),
            )
            PaneRowDivider()
            PaneExternalRow(
                label = phoneText("android.shelf.report.problem"),
                subLine = phoneText("android.shelf.review.send.support.site"),
                onClick = {
                    val facts = reportFacts
                    context.openUrl(
                        supportReportUrl(
                            version = facts.versionName,
                            build = facts.build,
                            osVersion = facts.osVersion,
                            state = supportState(status),
                            about = reportAbout,
                        ),
                    )
                },
                modifier = Modifier.testTag("helpReportProblem"),
            )
            PaneRowDivider()
            PaneExternalRow(
                label = phoneText("android.shelf.email.support"),
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
        else -> phoneText("android.shelf.manufacturer.model" , "manufacturer" to (manufacturer), "model" to (model))
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
