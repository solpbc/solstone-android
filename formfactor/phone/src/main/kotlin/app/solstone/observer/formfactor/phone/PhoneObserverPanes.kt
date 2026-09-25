// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.SourcesReadModel
import app.solstone.core.identity.JournalMarkPresentation

// Guards the selected preferred deck width: 2 x 48dp tiles + 8dp grid gap + 2 x 16dp compact
// margins = 136dp. The split collapses entirely rather than shrinking the leading pane below it.
private const val MINIMUM_SPLIT_DECK_WIDTH_DP = 136

internal fun deckPaneWidth(widthClass: WidthClass): Dp = when (widthClass) {
    WidthClass.LARGE,
    WidthClass.EXTRA_LARGE -> 412.dp
    else -> 360.dp
}

internal fun shouldRenderSplit(
    maxHorizontalPartitions: Int,
    deckPaneWidthDp: Int,
): Boolean = maxHorizontalPartitions >= 2 && deckPaneWidthDp >= MINIMUM_SPLIT_DECK_WIDTH_DP

@Composable
internal fun PhoneDetailPane(
    top: PhoneRoute?,
    loadState: LoadState<SourcesReadModel>,
    homeTileStore: PhoneHomeTileStore,
    onStartObserving: () -> Unit,
    onGrantPermissions: (String) -> Unit,
    onConnectJournal: () -> Unit,
    onManageLocalStorage: () -> Unit,
    journalPaired: Boolean,
    journalFacts: PhoneJournalFacts,
    journalMarkPresentation: JournalMarkPresentation,
    hapticsEnabled: Boolean,
    notificationsEnabled: Boolean,
    journalPushEnabled: Boolean = false,
    journalNotificationRow: PhoneJournalNotificationRow? = null,
    onChooseJournalDeliveryApp: () -> Unit = {},
    eventLog: String,
    problemReports: List<String>,
    journalMutationFailed: Boolean,
    journalMutationFromThisDevice: Boolean = false,
    journalKeptItsRecord: Boolean = false,
    notificationTestFailed: Boolean,
    onHapticsChanged: (Boolean) -> Unit,
    onCheckConnection: () -> Unit,
    onForgetJournal: () -> Unit,
    onUnpairThisDevice: () -> Unit = onForgetJournal,
    onOpenNotificationSettings: () -> Unit,
    onSendTestNotification: () -> Unit,
    onReportProblem: () -> Unit,
    onSaveProblemReport: () -> Unit,
    onOpenTechnicalDetails: () -> Unit,
    onOpenEventLog: () -> Unit,
    onOpenProblemReports: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenSource: (String) -> Unit,
    defaultDetailStatus: PhoneDefaultDetailStatus,
    onRefreshStatus: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leadingSlot: (@Composable () -> Unit)? = null,
    version: String = "",
    journalVersion: String = "",
    isOnHome: (String) -> Boolean = { true },
    onToggle: (String, app.solstone.observer.harness.SourceWish) -> Unit = { _, _ -> },
) {
    Column(modifier.fillMaxSize()) {
        leadingSlot?.let { slot ->
            Box(Modifier.fillMaxWidth()) {
                slot()
            }
        }
        PhoneDetailContent(
            top = top,
            loadState = loadState,
            homeTileStore = homeTileStore,
            onStartObserving = onStartObserving,
            onGrantPermissions = onGrantPermissions,
            onConnectJournal = onConnectJournal,
            onManageLocalStorage = onManageLocalStorage,
            journalPaired = journalPaired,
            journalFacts = journalFacts,
            journalMarkPresentation = journalMarkPresentation,
            hapticsEnabled = hapticsEnabled,
            notificationsEnabled = notificationsEnabled,
            journalPushEnabled = journalPushEnabled,
            journalNotificationRow = journalNotificationRow,
            onChooseJournalDeliveryApp = onChooseJournalDeliveryApp,
            eventLog = eventLog,
            problemReports = problemReports,
            journalMutationFailed = journalMutationFailed,
            journalMutationFromThisDevice = journalMutationFromThisDevice,
            journalKeptItsRecord = journalKeptItsRecord,
            notificationTestFailed = notificationTestFailed,
            onHapticsChanged = onHapticsChanged,
            onCheckConnection = onCheckConnection,
            onForgetJournal = onForgetJournal,
            onUnpairThisDevice = onUnpairThisDevice,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onSendTestNotification = onSendTestNotification,
            onReportProblem = onReportProblem,
            onSaveProblemReport = onSaveProblemReport,
            onOpenTechnicalDetails = onOpenTechnicalDetails,
            onOpenEventLog = onOpenEventLog,
            onOpenProblemReports = onOpenProblemReports,
            onOpenLicences = onOpenLicences,
            onOpenSource = onOpenSource,
            defaultDetailStatus = defaultDetailStatus,
            onRefreshStatus = onRefreshStatus,
            version = version,
            journalVersion = journalVersion,
            isOnHome = isOnHome,
            onToggle = onToggle,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = PHONE_CONTENT_MARGIN_DP.dp),
        )
    }
}

@Composable
private fun PhoneDetailContent(
    top: PhoneRoute?,
    loadState: LoadState<SourcesReadModel>,
    homeTileStore: PhoneHomeTileStore,
    onStartObserving: () -> Unit,
    onGrantPermissions: (String) -> Unit,
    onConnectJournal: () -> Unit,
    onManageLocalStorage: () -> Unit,
    journalPaired: Boolean,
    journalFacts: PhoneJournalFacts,
    journalMarkPresentation: JournalMarkPresentation,
    hapticsEnabled: Boolean,
    notificationsEnabled: Boolean,
    journalPushEnabled: Boolean = false,
    journalNotificationRow: PhoneJournalNotificationRow? = null,
    onChooseJournalDeliveryApp: () -> Unit = {},
    eventLog: String,
    problemReports: List<String>,
    journalMutationFailed: Boolean,
    journalMutationFromThisDevice: Boolean = false,
    journalKeptItsRecord: Boolean = false,
    notificationTestFailed: Boolean,
    onHapticsChanged: (Boolean) -> Unit,
    onCheckConnection: () -> Unit,
    onForgetJournal: () -> Unit,
    onUnpairThisDevice: () -> Unit = onForgetJournal,
    onOpenNotificationSettings: () -> Unit,
    onSendTestNotification: () -> Unit,
    onReportProblem: () -> Unit,
    onSaveProblemReport: () -> Unit,
    onOpenTechnicalDetails: () -> Unit,
    onOpenEventLog: () -> Unit,
    onOpenProblemReports: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenSource: (String) -> Unit,
    defaultDetailStatus: PhoneDefaultDetailStatus,
    onRefreshStatus: (() -> Unit)?,
    modifier: Modifier,
    version: String = "",
    journalVersion: String = "",
    isOnHome: (String) -> Boolean = { true },
    onToggle: (String, app.solstone.observer.harness.SourceWish) -> Unit = { _, _ -> },
) {
    when (top) {
        null -> PhoneDefaultDetailPane(
            status = defaultDetailStatus,
            onConnectJournal = onConnectJournal,
            onOpenSource = onOpenSource,
            onRefreshStatus = onRefreshStatus,
            modifier = modifier,
        )
        PhoneRoute.AboutSolstone -> PhoneAboutPane(
            onOpenLicences = onOpenLicences,
            version = version,
            journalVersion = journalVersion,
            modifier = modifier,
        )
        PhoneRoute.Licences -> PhoneLicencesPane(modifier = modifier)
        PhoneRoute.Import -> PhoneImportPane(modifier = modifier)
        PhoneRoute.AddMore -> PhoneAddMorePane(
            onOpenSource = onOpenSource,
            isOnHome = isOnHome,
            modifier = modifier,
        )
        PhoneRoute.YourJournal -> PhoneYourJournalPane(
            paired = journalPaired,
            facts = journalFacts,
            presentation = journalMarkPresentation,
            onConnectJournal = onConnectJournal,
            onForgetJournal = onForgetJournal,
            mutationFailed = journalMutationFailed && !journalMutationFromThisDevice,
            journalKeptItsRecord = journalKeptItsRecord,
            modifier = modifier,
        )
        PhoneRoute.ThisDevice -> PhoneThisDevicePane(
            version = version,
            hapticsEnabled = hapticsEnabled,
            onHapticsChanged = onHapticsChanged,
            onOpenTechnicalDetails = onOpenTechnicalDetails,
            onOpenEventLog = onOpenEventLog,
            onOpenProblemReports = onOpenProblemReports,
            paired = journalPaired,
            onUnpair = onUnpairThisDevice,
            mutationFailed = journalMutationFailed && journalMutationFromThisDevice,
            journalKeptItsRecord = journalKeptItsRecord,
            modifier = modifier,
        )
        PhoneRoute.TechnicalDetails -> PhoneTechnicalDetailsPane(
            facts = journalFacts,
            onCheckConnection = onCheckConnection,
            onOpenEventLog = onOpenEventLog,
            modifier = modifier,
        )
        PhoneRoute.EventLog -> PhoneEventLogPane(eventLog = eventLog, modifier = modifier)
        PhoneRoute.ProblemReports -> PhoneProblemReportsPane(
            reports = problemReports,
            onSaveReport = onSaveProblemReport,
            onReportProblem = onReportProblem,
            modifier = modifier,
        )
        PhoneRoute.Notifications -> PhoneNotificationsPane(
            enabled = notificationsEnabled,
            testFailed = notificationTestFailed,
            onOpenSettings = onOpenNotificationSettings,
            onSendTest = onSendTestNotification,
            journalPushEnabled = journalPushEnabled,
            journalNotificationRow = journalNotificationRow,
            onChooseJournalDeliveryApp = onChooseJournalDeliveryApp,
            modifier = modifier,
        )
        PhoneRoute.Help -> PhoneHelpPane(
            version = version,
            status = defaultDetailStatus,
            modifier = modifier,
        )
        // Deliberately unimplemented placeholder routes, kept for the navigation
        // tests. They are the only surfaces allowed to expose an identifier.
        PhoneRoute.RouteA,
        PhoneRoute.RouteB,
        PhoneRoute.RouteC,
        PhoneRoute.RouteCChild -> {
            Box(
                modifier
                    .fillMaxSize()
                    .semantics { paneTitle = spokenPaneTitle(top) },
            )
        }
        is PhoneRoute.SourceDetail -> Box(
            modifier
                .fillMaxSize()
                .semantics { paneTitle = spokenPaneTitle(top) },
        ) {
            PhoneSourceDetail(
                loadState = loadState,
                sourceId = top.sourceId,
                homeTileStore = homeTileStore,
                onStartObserving = onStartObserving,
                onGrantPermissions = onGrantPermissions,
                onConnectJournal = onConnectJournal,
                onManageLocalStorage = onManageLocalStorage,
                onToggle = { wish -> onToggle(top.sourceId, wish) },
            )
        }
    }
}
