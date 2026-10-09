// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import app.solstone.observer.formfactor.phone.EXTRA_PHONE_ROUTE
import app.solstone.observer.formfactor.phone.PhoneObserverScreen
import app.solstone.observer.formfactor.phone.PhoneRouteStack
import app.solstone.observer.formfactor.phone.PhoneStatusSnapshot
import app.solstone.observer.formfactor.phone.PhoneStatusViewModel
import app.solstone.observer.formfactor.phone.SourcesViewModel
import app.solstone.observer.formfactor.phone.decodePhoneRoute
import app.solstone.observer.formfactor.phone.phoneDefaultDetailStatusOf
import app.solstone.observer.formfactor.phone.resolvePhoneCaptureWidthDp
import app.solstone.observer.formfactor.phone.resolvePhoneStatusCapture
import app.solstone.observer.formfactor.phone.supportReportUrl
import app.solstone.observer.formfactor.phone.supportReportFields
import app.solstone.observer.formfactor.phone.supportState
import app.solstone.observer.formfactor.phone.readAndroidAboutFacts
import app.solstone.observer.formfactor.phone.rememberPhoneAboutBlock
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.MigrationSurface
import app.solstone.observer.harness.migrationCopy
import app.solstone.observer.harness.ObserverStartMode
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.FileSourceWishStore
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.awaitingMarkConfirmation
import app.solstone.core.identity.confirmedFor
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.StoreInspectResult
import app.solstone.observer.formfactor.phone.MarkConfirmationRoute
import app.solstone.observer.formfactor.phone.manualMarkConfirmationRoute
import app.solstone.core.diagnostics.DiagnosticLogRead
import app.solstone.platform.work.forgetPushAfterCleared
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingProvenance
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.observer.formfactor.phone.CHECK_CONNECTION_REACHED
import app.solstone.observer.formfactor.phone.PhoneTheme
import app.solstone.observer.formfactor.phone.CHECK_CONNECTION_RUNNING
import app.solstone.observer.formfactor.phone.checkConnectionUnreached
import app.solstone.platform.work.revokeThisDeviceOnJournal
import app.solstone.observer.harness.HarnessPlStatus
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.platform.fgs.shouldAskForNotifications
import app.solstone.platform.fgs.ObserverNotification
import app.solstone.observer.harness.SourcesReader
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverApplication
import app.solstone.observer.scaffold.ObserverHarnessRuntime
import app.solstone.platform.work.JournalBrowserUpstreamAdapter
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.SyncStores
import app.solstone.platform.work.syncStores
import app.solstone.core.pl.PairingMigrationListing
import app.solstone.core.pl.PairingMigrationOwner
import app.solstone.core.pl.PairingMigrationPendingReason
import app.solstone.core.pl.InitialClientDecision
import app.solstone.core.pl.PairingMigrationRecord
import app.solstone.core.pl.PairingMigrationResult
import app.solstone.core.pl.PairingMigrationStage
import app.solstone.core.pl.canOpenDeviceChoice
import app.solstone.core.pl.MigrationClient
import app.solstone.core.pl.TARGET_UNAVAILABLE_REASON
import app.solstone.core.push.JournalNotificationRow
import app.solstone.core.push.journalNotificationRow
import app.solstone.core.push.journalOpenPath
import app.solstone.core.push.journalPushRepair
import app.solstone.core.push.journalPushPickerResult
import app.solstone.core.push.JournalPushPickerAction
import app.solstone.core.push.JournalPushRepairAction
import app.solstone.core.push.JournalPushRepairMemory
import app.solstone.core.push.PushDeliveryState
import app.solstone.observer.harness.PhoneFreshPairPlan
import app.solstone.observer.harness.freshPairResultIsCurrent
import app.solstone.observer.harness.phoneDeviceChoicePending
import app.solstone.observer.harness.planPhoneFreshPair
import app.solstone.observer.formfactor.phone.PhoneJournalNotificationRow
import org.unifiedpush.android.connector.UnifiedPush
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal enum class PhoneDeviceChoiceRecoveryAction {
    SHOW_CURRENT,
    REFRESH_CLIENTS,
    RESUME_DECISION,
    HIDE,
}

internal fun phoneDeviceChoiceRecoveryAction(stage: PairingMigrationStage?): PhoneDeviceChoiceRecoveryAction = when (stage) {
    PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT -> PhoneDeviceChoiceRecoveryAction.HIDE
    PairingMigrationStage.AWAITING_SELECTION -> PhoneDeviceChoiceRecoveryAction.REFRESH_CLIENTS
    PairingMigrationStage.READY_TO_SUBMIT,
    PairingMigrationStage.SUBMITTED_UNKNOWN -> PhoneDeviceChoiceRecoveryAction.RESUME_DECISION
    else -> PhoneDeviceChoiceRecoveryAction.SHOW_CURRENT
}

class PhoneShellActivity : ComponentActivity() {
    private lateinit var container: ObserverAppContainer
    private lateinit var sourcesViewModel: SourcesViewModel
    private lateinit var statusViewModel: PhoneStatusViewModel
    private lateinit var stores: SyncStores
    private var captureOwnerToken: Long = -1L
    private val notificationPrompt by lazy { NotificationPromptStore(this) }
    private val capturePermissionRequests by lazy { CapturePermissionRequestStore(this) }
    private val ownerStopped by lazy { OwnerStoppedStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusListener: (HarnessBacklogStatus) -> Unit = { backlog ->
        mainHandler.post { if (::statusViewModel.isInitialized) statusViewModel.publish(backlog) }
    }
    private var notificationsEnabled by mutableStateOf(false)
    private var journalNotificationRow by mutableStateOf<PhoneJournalNotificationRow?>(null)
    private var journalPushOn by mutableStateOf(false)
    private var journalDeliveredBy by mutableStateOf<String?>(null)
    private var pushFactsExecutor: ExecutorService? = null
    private var pushDeliveryUnsubscribe: (() -> Unit)? = null
    @Volatile private var pushFactsStopped = false
    private var repairMemory = JournalPushRepairMemory()
    /**
     * What the last `check connection` found, cleared when the shell leaves the foreground.
     *
     * ⚠ The result answers "what happened when I just tapped this," so it does not outlive the
     * session that tapped it. Left standing it can sit under a `connection` row that has since
     * gone the other way, which is worse than showing nothing.
     */
    private var connectionCheck by mutableStateOf<String?>(null)
    private var migrationRefreshEpoch by mutableStateOf(0L)
    /**
     * Re-establish intake on resume — ⛔ **without deciding, on the owner's behalf, that they want
     * it.**
     *
     * 🔴 This called `ensureObserving()`, which sets `desiredOn = true`. So an owner who pressed
     * **`stop intake`** in the notification had that choice thrown away the next time they opened
     * the app: the shade said `off`, the screen said `setting up`, and nothing had asked them.
     * Caught on video — the Play demonstration showed the stop control working and then the app
     * undoing it eight seconds later, which is also the beat a reviewer is watching hardest.
     *
     * ✅ `reconcile` is the honest verb: it returns immediately when `desiredOn` is false, and
     * otherwise brings the service back up for whatever the owner has already asked for. Turning a
     * source on is what asks — see [onSourceWish].
     */
    private val startWhenReady = object : Runnable {
        override fun run() {
            if (container.recoveryCompleted) {
                // ⚠ `ensureObserving` unless the owner stopped it themselves. `reconcile` alone is
                // not enough: on a fresh install whose permissions were already granted, the
                // migration backfill expresses the sources and NOTHING would ever bring the service
                // up — sources the owner sees as on, taking nothing in.
                if (ownerStopped.ownerStopped()) {
                    container.controller.reconcile(ObserverStartMode.VisibleStart)
                } else {
                    container.controller.ensureObserving()
                }
                requestNotificationsOnce()
            } else {
                mainHandler.postDelayed(this, RECOVERY_POLL_INTERVAL_MS)
            }
        }
    }

    private fun displayedSnapshot(): PairingGraphSnapshot =
        PhoneJournalTestHooks.pairingSnapshotOverride ?: stores.publisher.currentSnapshot()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as ObserverApplication
        val runtime = ObserverHarnessRuntime.runtime ?: app.runtime.also {
            ObserverHarnessRuntime.runtime = it
        }
        container = runtime.container()
        stores = syncStores(applicationContext)

        if (!promptedConfirmationThisProcess && savedInstanceState == null && awaitingMarkConfirmation(JournalConfirmationPolicy.consults, displayedSnapshot(), stores.journalConfirmationStore)) {
            promptedConfirmationThisProcess = true
            startActivity(
                Intent(this, ObserverActivity::class.java)
                    .putExtra(ObserverActivity.EXTRA_CONFIRM_JOURNAL, true),
            )
        }

        val openedJournalPath = journalOpenPath(
            pushRegistration = BuildConfig.PUSH_REGISTRATION,
            freshCreate = savedInstanceState == null,
            launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0,
            pairingCommitted = displayedSnapshot() is PairingGraphSnapshot.Committed,
            rawPath = intent.getStringExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN),
        )
        if (openedJournalPath != null) {
            PhoneDiagLog.appendRaw("kind=journal_open")
        }

        if (BuildConfig.PUSH_REGISTRATION) {
            pushFactsExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "phone-push-facts").apply { isDaemon = true }
            }
            pushDeliveryUnsubscribe = stores.addPushDeliveryStateListener { nextState ->
                pushFactsExecutor?.execute {
                    refreshJournalPush(nextState, repair = false)
                }
            }
        }

        val capture = captureSurfaceFromIntent()
        val noticeStore = PhoneAudioOffNoticeStore(this)
        val decoratedSources = AudioOffNoticeDecoratedSourcesReader(
            delegate = container.sources,
            readNotice = { noticeStore.read() },
        )
        val factory = PhoneShellViewModelFactory(
            sources = decoratedSources,
            readStatus = PhoneStatusSupplier.forContainer(container),
            asyncLoad = container.asyncLoad,
            awaitingMarkConfirmation = { phoneAwaitingMarkConfirmation(this@PhoneShellActivity) },
            recoveryCompleted = { container.recoveryCompleted },
            audioAwaitingCustody = { phoneAudioAwaitingCustody(this@PhoneShellActivity, container) },
            unresolvedAudioInterruption = { phoneUnresolvedAudioInterruption(this@PhoneShellActivity) },
            unresolvedOtherInterruption = { phoneUnresolvedOtherInterruption(this@PhoneShellActivity) },
            unresolvedUnknownRecovery = { phoneUnresolvedUnknownRecovery(this@PhoneShellActivity) },
            capturedStatusState = capture.capturedStatusState,
        )
        sourcesViewModel = ViewModelProvider(
            this,
            factory,
        ).get(SourcesViewModel::class.java)
        statusViewModel = ViewModelProvider(this, factory).get(PhoneStatusViewModel::class.java)
        setContent {
            val statusState = statusViewModel.statusState
            val snapshot = (statusState as? LoadState.Loaded)?.value
            var pairingSnapshot by remember { mutableStateOf(displayedSnapshot()) }
            var journalConfirmed by remember {
                val snapshot = displayedSnapshot()
                mutableStateOf(
                    snapshot is PairingGraphSnapshot.Committed && confirmedFor(
                        JournalConfirmationPolicy.consults,
                        stores.journalConfirmationStore,
                        snapshot.home.clientCertFingerprint,
                    )
                )
            }
            var markPresentation by remember {
                mutableStateOf(stores.journalIdentityCoordinator.currentPresentation())
            }
            var markGeneration by remember {
                mutableStateOf(stores.journalIdentityCoordinator.currentPresentationGeneration())
            }
            var journalPath by remember { mutableStateOf(openedJournalPath) }
            var journalOpen by remember { mutableStateOf(openedJournalPath != null) }
            val wishStore = remember { FileSourceWishStore(filesDir.resolve("source-wishes")) }
            var wishStoreState by remember { mutableStateOf(wishStore.read()) }
            val hapticsStore = remember { getSharedPreferences("phone-shell", MODE_PRIVATE) }
            val problemReportStore = remember {
                PhoneProblemReportStore(filesDir.resolve("problem-reports"))
            }
            var problemReports by remember { mutableStateOf(problemReportStore.list()) }
            val shellScope = rememberCoroutineScope()
            var journalMutationFailed by remember { mutableStateOf(false) }
            var journalMutationFromThisDevice by remember { mutableStateOf(false) }
            var migrationRecord by remember { mutableStateOf<PairingMigrationRecord?>(null) }
            var migrationListing by remember { mutableStateOf<PairingMigrationListing?>(null) }
            var migrationSelected by remember { mutableStateOf<MigrationClient?>(null) }
            var migrationDialogOpen by remember { mutableStateOf(false) }
            var migrationBusy by remember { mutableStateOf(false) }
            var migrationSurface by remember { mutableStateOf(MigrationSurface.OFFER) }
            var migrationStoreUnavailable by remember { mutableStateOf(false) }
            var migrationInventorySettled by remember { mutableStateOf(false) }
            var technicalDetailsRequest by remember { mutableStateOf(0L) }
            val migrationOwner = stores.pairingMigrationOwner

            fun publishMigration(result: PairingMigrationResult) {
                migrationStoreUnavailable = false
                when (result) {
                    PairingMigrationResult.NoOffer -> {
                        migrationRecord = null
                        migrationListing = null
                        migrationSelected = null
                    }
                    is PairingMigrationResult.Offer -> {
                        migrationRecord = result.record
                        migrationSurface = when {
                            result.record.refusalReason == TARGET_UNAVAILABLE_REASON -> MigrationSurface.TARGET_UNAVAILABLE
                            result.record.stage == PairingMigrationStage.AWAITING_SELECTION -> MigrationSurface.CONFIRM
                            result.record.stage == PairingMigrationStage.TERMINAL_REFUSED &&
                                result.record.refusalReason == "migration_protocol_unsupported" -> MigrationSurface.UNSUPPORTED
                            result.record.stage == PairingMigrationStage.TERMINAL_REFUSED -> MigrationSurface.REFUSED
                            else -> MigrationSurface.OFFER
                        }
                        if (result.record.stage in setOf(
                                PairingMigrationStage.TERMINAL_KEEP_BOTH,
                                PairingMigrationStage.TERMINAL_REPLACED,
                                PairingMigrationStage.SKIPPED_NO_OTHER_CLIENT,
                            )
                        ) migrationDialogOpen = false
                    }
                    is PairingMigrationResult.Listing -> {
                        migrationListing = result.value
                        migrationSelected = null
                        migrationRecord = migrationRecord?.copy(
                            stage = PairingMigrationStage.LISTING,
                            requestSequence = result.value.requestSequence,
                            selectedCid = null,
                            refusalReason = null,
                        )
                        migrationSurface = if (result.value.clients.isEmpty()) {
                            MigrationSurface.PICKER_EMPTY
                        } else {
                            MigrationSurface.PICKER
                        }
                    }
                    is PairingMigrationResult.Pending -> {
                        migrationRecord = result.record
                        migrationSurface = when (result.reason) {
                            PairingMigrationPendingReason.DECISION_UNKNOWN -> MigrationSurface.CHECKING
                            PairingMigrationPendingReason.LIST_OFFLINE -> MigrationSurface.OFFLINE
                            PairingMigrationPendingReason.LIST_UNAVAILABLE -> MigrationSurface.LIST_UNAVAILABLE
                            PairingMigrationPendingReason.SAVED_CONNECTION_REFUSED -> MigrationSurface.KEY_REFUSED
                        }
                    }
                    is PairingMigrationResult.Terminal -> {
                        migrationRecord = result.record
                        if (result.record.stage == PairingMigrationStage.TERMINAL_REFUSED) {
                            migrationSurface = if (result.record.refusalReason == "migration_protocol_unsupported") {
                                MigrationSurface.UNSUPPORTED
                            } else {
                                MigrationSurface.REFUSED
                            }
                        } else {
                            migrationDialogOpen = false
                        }
                    }
                    PairingMigrationResult.StaleGeneration -> {
                        migrationRecord = null
                        migrationListing = null
                        migrationSelected = null
                        migrationDialogOpen = false
                    }
                    PairingMigrationResult.Unavailable -> {
                        migrationStoreUnavailable = true
                        migrationSurface = MigrationSurface.STORAGE
                    }
                    PairingMigrationResult.InvalidSelection -> {
                        migrationListing = null
                        migrationSelected = null
                        migrationSurface = MigrationSurface.OFFER
                    }
                    PairingMigrationResult.ConfirmationRequired -> {
                        migrationSelected = null
                        migrationSurface = MigrationSurface.OFFER
                    }
                }
            }

            fun performMigration(
                progress: MigrationSurface,
                action: (PairingMigrationOwner) -> PairingMigrationResult,
            ) {
                val owner = migrationOwner ?: run {
                    migrationStoreUnavailable = true
                    migrationSurface = MigrationSurface.STORAGE
                    return
                }
                migrationSurface = progress
                migrationBusy = true
                shellScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { action(owner) }.getOrElse { PairingMigrationResult.Unavailable } }
                    migrationBusy = false
                    publishMigration(result)
                }
            }

            fun deferMigration() {
                val expected = migrationRecord?.generation ?: (pairingSnapshot as? PairingGraphSnapshot.Committed)?.pairing
                migrationDialogOpen = false
                migrationSelected = null
                if (expected != null) performMigration(MigrationSurface.OFFER) { it.defer(expected) }
            }

            fun openDeviceChoice() {
                migrationSelected = null
                val owner = migrationOwner
                if (owner == null) {
                    migrationDialogOpen = true
                    publishMigration(PairingMigrationResult.Unavailable)
                    return
                }
                migrationBusy = true
                val started = owner.currentPairing()
                shellScope.launch {
                    var current = withContext(Dispatchers.IO) {
                        runCatching { owner.currentOffer() }.getOrElse { PairingMigrationResult.Unavailable }
                    }
                    if (!freshPairResultIsCurrent(started, owner.currentPairing())) {
                        migrationBusy = false
                        return@launch
                    }
                    val unseen = (current as? PairingMigrationResult.Offer)?.record
                    if (unseen?.stage == PairingMigrationStage.OFFER_NOT_SHOWN) {
                        if (journalConfirmed) {
                            val decision = withContext(Dispatchers.IO) {
                                runCatching { owner.decideInitialClients(unseen.generation) }
                                    .getOrElse { InitialClientDecision.PreflightFailed }
                            }
                            if (!freshPairResultIsCurrent(started, owner.currentPairing())) {
                                migrationBusy = false
                                return@launch
                            }
                            when (val plan = planPhoneFreshPair(journalConfirmed = true, decision)) {
                                PhoneFreshPairPlan.DoNothing,
                                PhoneFreshPairPlan.Drop -> {
                                    migrationBusy = false
                                    return@launch
                                }
                                is PhoneFreshPairPlan.ApplySkip -> {
                                    migrationBusy = false
                                    migrationDialogOpen = false
                                    migrationInventorySettled = true
                                    publishMigration(PairingMigrationResult.Offer(plan.record))
                                    return@launch
                                }
                                PhoneFreshPairPlan.ClaimAndShow -> {
                                    val claimed = withContext(Dispatchers.IO) {
                                        runCatching { owner.claimInitialPresentation(unseen.generation) }.getOrElse { false }
                                    }
                                    if (!freshPairResultIsCurrent(started, owner.currentPairing())) {
                                        migrationBusy = false
                                        return@launch
                                    }
                                    current = withContext(Dispatchers.IO) {
                                        runCatching { owner.currentOffer() }.getOrElse { PairingMigrationResult.Unavailable }
                                    }
                                    if (!freshPairResultIsCurrent(started, owner.currentPairing())) {
                                        migrationBusy = false
                                        return@launch
                                    }
                                    migrationBusy = false
                                    migrationInventorySettled = true
                                    publishMigration(current)
                                    if (claimed) migrationDialogOpen = true
                                    return@launch
                                }
                                is PhoneFreshPairPlan.FollowExisting -> {
                                    current = plan.result
                                }
                            }
                        } else {
                            withContext(Dispatchers.IO) { owner.claimInitialPresentation(unseen.generation) }
                            if (!freshPairResultIsCurrent(started, owner.currentPairing())) {
                                migrationBusy = false
                                return@launch
                            }
                            current = withContext(Dispatchers.IO) {
                                runCatching { owner.currentOffer() }.getOrElse { PairingMigrationResult.Unavailable }
                            }
                            if (!freshPairResultIsCurrent(started, owner.currentPairing())) {
                                migrationBusy = false
                                return@launch
                            }
                            val stillUnseen = (current as? PairingMigrationResult.Offer)?.record?.stage ==
                                PairingMigrationStage.OFFER_NOT_SHOWN
                            if (stillUnseen) current = PairingMigrationResult.Unavailable
                        }
                    }
                    val record = (current as? PairingMigrationResult.Offer)?.record
                    when (phoneDeviceChoiceRecoveryAction(record?.stage)) {
                        PhoneDeviceChoiceRecoveryAction.HIDE -> {
                            migrationBusy = false
                            migrationDialogOpen = false
                            migrationInventorySettled = true
                            publishMigration(current)
                        }
                        PhoneDeviceChoiceRecoveryAction.REFRESH_CLIENTS -> {
                            val awaitingSelection = checkNotNull(record)
                            migrationDialogOpen = true
                            migrationListing = null
                            migrationSelected = null
                            migrationSurface = MigrationSurface.PREPARING
                            val refreshed = withContext(Dispatchers.IO) {
                                runCatching { owner.listClients(awaitingSelection.generation) }
                                    .getOrElse { PairingMigrationResult.Unavailable }
                            }
                            if (!freshPairResultIsCurrent(started, owner.currentPairing())) {
                                migrationBusy = false
                                return@launch
                            }
                            migrationBusy = false
                            publishMigration(refreshed)
                        }
                        PhoneDeviceChoiceRecoveryAction.RESUME_DECISION -> {
                            val decision = checkNotNull(record)
                            migrationDialogOpen = true
                            migrationSurface = MigrationSurface.CHECKING
                            val resumed = withContext(Dispatchers.IO) {
                                runCatching { owner.resume(decision.generation) }.getOrElse { PairingMigrationResult.Unavailable }
                            }
                            if (!freshPairResultIsCurrent(started, owner.currentPairing())) {
                                migrationBusy = false
                                return@launch
                            }
                            migrationBusy = false
                            publishMigration(resumed)
                        }
                        PhoneDeviceChoiceRecoveryAction.SHOW_CURRENT -> {
                            migrationBusy = false
                            migrationDialogOpen = current !is PairingMigrationResult.NoOffer
                            publishMigration(current)
                        }
                    }
                }
            }
            // One routine, two entry points. The pane that was pressed is what decides which
            // pane's failure note the owner is shown.
            val unpairFrom: (Boolean) -> Unit = { fromThisDevice ->
                journalMutationFailed = false
                journalMutationFromThisDevice = fromThisDevice
                PhoneDiagLog.appendRaw("kind=unpair")
                shellScope.launch {
                    // 🔴 The journal half runs FIRST and on its own thread: it authenticates with
                    // the very credential `forget()` is about to delete. It never blocks the local
                    // half. The owner is not told when the journal cannot be reached.
                    val cleared = leaveJournal(
                        dispatcher = Dispatchers.IO,
                        revoke = { revokeThisDeviceOnJournal(stores.publisher) },
                        forget = stores.publisher::forget,
                        log = PhoneDiagLog::appendRaw,
                    ) is GraphMutationResult.Cleared
                    if (cleared) {
                        forgetPushAfterCleared(stores)
                        statusViewModel.refresh()
                    } else {
                        journalMutationFailed = true
                    }
                }
            }
            var notificationTestFailed by remember { mutableStateOf(false) }
            var hapticsEnabled by remember {
                mutableStateOf(hapticsStore.getBoolean("haptics-enabled", true))
            }
            DisposableEffect(stores) {
                val pairingSubscription = stores.publisher.subscribe { next ->
                    mainHandler.post {
                        statusViewModel.refresh()
                        val effective = displayedSnapshot()
                        val before = pairingSnapshot
                        pairingSnapshot = effective
                        if (pairingDiagKey(before) != pairingDiagKey(effective)) {
                            PhoneDiagLog.appendRaw("kind=pairing state=${pairingDiagState(effective)}")
                        }
                    }
                }
                val removeMarkListener = stores.journalIdentityCoordinator.addGenerationListener { generation, next ->
                    mainHandler.post {
                        val current = (stores.publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)
                            ?.pairing
                        if (generation == null || generation == current) {
                            markGeneration = generation
                            markPresentation = next
                        }
                    }
                }
                val removeConfirmationListener = stores.journalConfirmationStore.addListener {
                    mainHandler.post {
                        statusViewModel.refresh()
                        val snapshot = displayedSnapshot()
                        journalConfirmed = snapshot is PairingGraphSnapshot.Committed && confirmedFor(
                            JournalConfirmationPolicy.consults,
                            stores.journalConfirmationStore,
                            snapshot.home.clientCertFingerprint,
                        )
                    }
                }
                onDispose {
                    pairingSubscription.cancel()
                    removeMarkListener()
                    removeConfirmationListener()
                }
            }
            LaunchedEffect(pairingSnapshot.sequenceNumber, journalConfirmed, migrationRefreshEpoch) {
                val snapshot = pairingSnapshot
                journalConfirmed = snapshot is PairingGraphSnapshot.Committed && confirmedFor(
                    JournalConfirmationPolicy.consults,
                    stores.journalConfirmationStore,
                    snapshot.home.clientCertFingerprint,
                )
                if (pairingSnapshot !is PairingGraphSnapshot.Committed) journalOpen = false
                val fresh = snapshot as? PairingGraphSnapshot.Committed
                val owner = migrationOwner
                if (fresh == null || !journalConfirmed || owner == null) {
                    migrationRecord = null
                    migrationListing = null
                    migrationSelected = null
                    migrationStoreUnavailable = false
                    migrationInventorySettled = false
                } else {
                    val started = owner.currentPairing()
                    val offered = withContext(Dispatchers.IO) {
                        runCatching { owner.currentOffer() }.getOrElse { PairingMigrationResult.Unavailable }
                    }
                    if (!freshPairResultIsCurrent(started, owner.currentPairing())) return@LaunchedEffect
                    val record = (offered as? PairingMigrationResult.Offer)?.record
                    if (record?.stage == PairingMigrationStage.OFFER_NOT_SHOWN) {
                        migrationInventorySettled = false
                        migrationDialogOpen = false
                        val decision = withContext(Dispatchers.IO) {
                            runCatching { owner.decideInitialClients(record.generation) }
                                .getOrElse { InitialClientDecision.PreflightFailed }
                        }
                        if (!freshPairResultIsCurrent(started, owner.currentPairing())) return@LaunchedEffect
                        when (val plan = planPhoneFreshPair(journalConfirmed = true, decision)) {
                            PhoneFreshPairPlan.DoNothing,
                            PhoneFreshPairPlan.Drop -> return@LaunchedEffect
                            is PhoneFreshPairPlan.ApplySkip -> {
                                publishMigration(PairingMigrationResult.Offer(plan.record))
                                migrationDialogOpen = false
                                migrationInventorySettled = true
                            }
                            PhoneFreshPairPlan.ClaimAndShow -> {
                                val claimed = withContext(Dispatchers.IO) {
                                    runCatching { owner.claimInitialPresentation(record.generation) }.getOrElse { false }
                                }
                                if (!freshPairResultIsCurrent(started, owner.currentPairing())) return@LaunchedEffect
                                val shown = withContext(Dispatchers.IO) {
                                    runCatching { owner.currentOffer() }.getOrElse { PairingMigrationResult.Unavailable }
                                }
                                if (!freshPairResultIsCurrent(started, owner.currentPairing())) return@LaunchedEffect
                                publishMigration(shown)
                                if (claimed) {
                                    migrationDialogOpen = true
                                }
                                migrationInventorySettled = true
                            }
                            is PhoneFreshPairPlan.FollowExisting -> {
                                publishMigration(plan.result)
                                val currentRec = (plan.result as? PairingMigrationResult.Offer)?.record
                                if (currentRec?.stage == PairingMigrationStage.READY_TO_SUBMIT ||
                                    currentRec?.stage == PairingMigrationStage.SUBMITTED_UNKNOWN
                                ) {
                                    val resumed = withContext(Dispatchers.IO) {
                                        runCatching { owner.resume(currentRec.generation) }.getOrElse { PairingMigrationResult.Unavailable }
                                    }
                                    if (!freshPairResultIsCurrent(started, owner.currentPairing())) return@LaunchedEffect
                                    publishMigration(resumed)
                                }
                                migrationInventorySettled = true
                            }
                        }
                    } else {
                        publishMigration(offered)
                        if (record?.stage == PairingMigrationStage.READY_TO_SUBMIT ||
                            record?.stage == PairingMigrationStage.SUBMITTED_UNKNOWN
                        ) {
                            val resumed = withContext(Dispatchers.IO) {
                                runCatching { owner.resume(record.generation) }.getOrElse { PairingMigrationResult.Unavailable }
                            }
                            if (!freshPairResultIsCurrent(started, owner.currentPairing())) return@LaunchedEffect
                            publishMigration(resumed)
                        }
                        migrationInventorySettled = true
                    }
                }
            }
            val currentPairing = (pairingSnapshot as? PairingGraphSnapshot.Committed)?.pairing
            val currentMarkPresentation = shellMarkPresentation(
                currentPairing = currentPairing,
                markGeneration = markGeneration,
                journalConfirmed = journalConfirmed,
                markPresentation = markPresentation,
            )
            val journalFacts = phoneJournalFacts(
                pairing = pairingSnapshot,
                status = snapshot?.status,
                intakeRunning = ObserverForegroundService.heldCaptureForegroundTypes != null,
                check = connectionCheck,
            )
            val eventLog = when (val log = PhoneDiagLog.installedSink()?.readResult()) {
                is DiagnosticLogRead.Complete -> log.content
                is DiagnosticLogRead.Partial -> "some events couldn't be read.\n${log.content}"
                DiagnosticLogRead.Unreadable -> "the event log couldn't be read."
                null -> "the event log couldn't be read."
            }
            val journalSheetOpenState = journalOpen && pairingSnapshot is PairingGraphSnapshot.Committed && journalConfirmed
            val aboutFacts = remember { readAndroidAboutFacts(this) }
            var aboutSnapshotEpoch by remember { mutableStateOf(0L) }
            val aboutBlock = rememberPhoneAboutBlock(
                aboutFacts, snapshot?.status?.journalVersion, aboutSnapshotEpoch,
            )
            PhoneObserverScreen(
                loadState = sourcesViewModel.sourcesState,
                status = snapshot?.status,
                aboutBlock = aboutBlock,
                onAboutOpened = { aboutSnapshotEpoch++ },
                waiting = snapshot?.waiting.orEmpty(),
                defaultDetailStatus = phoneDefaultDetailStatusOf(statusState),
                onRefreshStatus = statusViewModel::refresh,
                onToggle = { id, wish ->
                    onSourceWish(id, wish)
                    wishStoreState = wishStore.read()
                },
                onStartObserving = {
                    // ⚠ Asking again is asking: this is `resume intake` and `start intake again`,
                    // and both have to clear the owner's stop or the service will not come back.
                    ownerStopped.clear()
                    container.controller.ensureObserving()
                },
                onGrantPermissions = { sourceId -> routePermissionRequest(sourceId, priorWishFor(sourceId)) },
                onConnectJournal = {
                    openPairingScanner()
                },
                onConfirmMark = {
                    val route = manualMarkConfirmationRoute(
                        awaitingMarkConfirmation = awaitingMarkConfirmation(
                            JournalConfirmationPolicy.consults,
                            displayedSnapshot(),
                            stores.journalConfirmationStore,
                        ),
                        promptedConfirmationThisProcess = promptedConfirmationThisProcess,
                    )
                    if (route == MarkConfirmationRoute.CONFIRM) {
                        startActivity(
                            Intent(this@PhoneShellActivity, ObserverActivity::class.java)
                                .putExtra(ObserverActivity.EXTRA_CONFIRM_JOURNAL, true),
                        )
                    }
                },
                onOpenJournal = {
                    if (pairingSnapshot is PairingGraphSnapshot.Committed && journalConfirmed) {
                        journalPath = null
                        journalOpen = true
                    } else {
                        startActivity(
                            Intent(this@PhoneShellActivity, ObserverActivity::class.java)
                                .putExtra(ObserverActivity.EXTRA_CONFIRM_JOURNAL, true),
                        )
                    }
                },
                journalPaired = pairingSnapshot is PairingGraphSnapshot.Committed,
                journalMarkPresentation = currentMarkPresentation,
                journalSheetOpen = journalSheetOpenState,
                journalFacts = journalFacts,
                journalPushEnabled = BuildConfig.PUSH_REGISTRATION,
                journalNotificationRow = journalNotificationRow,
                onChooseJournalDeliveryApp = { pickJournalDistributor() },
                journalPushOn = journalPushOn,
                onJournalPushChange = { on -> setJournalPush(on) },
                journalDeliveredBy = journalDeliveredBy,
                hapticsEnabled = hapticsEnabled,
                notificationsEnabled = notificationsEnabled,
                eventLog = eventLog,
                problemReports = problemReports.map { it.savedAt },
                journalMutationFailed = journalMutationFailed,
                journalMutationFromThisDevice = journalMutationFromThisDevice,
                notificationTestFailed = notificationTestFailed,
                showWelcome = shouldShowWelcome(
                    wishes = wishStoreState,
                    pairing = pairingSnapshot,
                ),
                onHapticsChanged = { enabled ->
                    hapticsEnabled = enabled
                    hapticsStore.edit().putBoolean("haptics-enabled", enabled).apply()
                },
                onCheckConnection = {
                    // ⚠ The probe was already real; what was missing was any sign it had run.
                    // Tapping produced no spinner, no result and no timestamp, so the control
                    // read as dead.
                    PhoneDiagLog.appendRaw("kind=check-connection")
                    connectionCheck = CHECK_CONNECTION_RUNNING
                    shellScope.launch {
                        val probed = withContext(Dispatchers.IO) {
                            runCatching { container.controller.probePlStatus() }.getOrNull()
                        }
                        val reachable = probed is HarnessPlStatus.Reachable
                        PhoneDiagLog.appendRaw(
                            "kind=check-connection result=${if (reachable) "reached" else "unreached"}",
                        )
                        // ⚠ The address is named only when a dial was actually attempted and
                        // failed; a missing credential or identity keeps the plain words.
                        connectionCheck = if (reachable) {
                            CHECK_CONNECTION_REACHED
                        } else {
                            checkConnectionUnreached((probed as? HarnessPlStatus.PairedButUnreachable)?.address)
                        }
                        statusViewModel.refresh()
                    }
                },
                onForgetJournal = { unpairFrom(false) },
                onUnpairThisDevice = { unpairFrom(true) },
                deviceChoicePending = phoneDeviceChoicePending(
                    journalConfirmed = journalConfirmed,
                    provenance = (pairingSnapshot as? PairingGraphSnapshot.Committed)?.provenance,
                    migrationInventorySettled = migrationInventorySettled,
                    migrationStoreUnavailable = migrationStoreUnavailable,
                    migrationRecord = migrationRecord,
                ),
                onDeviceChoice = ::openDeviceChoice,
                onOpenNotificationSettings = {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                    )
                },
                onSendTestNotification = {
                    notificationTestFailed = !ObserverNotification.postTest(this)
                    notificationsEnabled = ObserverNotification.notificationsEnabled(this)
                },
                onReportProblem = {
                    val facts = aboutFacts
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                supportReportUrl(
                                    version = facts.versionName,
                                    build = facts.build,
                                    osVersion = facts.osVersion,
                                    state = supportState(phoneDefaultDetailStatusOf(statusState)),
                                    about = aboutBlock,
                                 ),
                            ),
                        ),
                    )
                },
                onSaveProblemReport = {
                    val facts = aboutFacts
                    val body = supportReportFields(
                        version = facts.versionName,
                        build = facts.build,
                        osVersion = facts.osVersion,
                        state = supportState(phoneDefaultDetailStatusOf(statusState)),
                        about = aboutBlock,
                        addresses = journalFacts.address.takeUnless { it == "—" },
                    )
                    runCatching { problemReportStore.save(body) }
                        .onSuccess { problemReports = it }
                },
                onCopyAbout = { text ->
                    runCatching {
                        getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                            clipboard.setPrimaryClip(ClipData.newPlainText("about", text))
                            true
                        } ?: false
                    }.getOrDefault(false)
                },
                onManageLocalStorage = {
                    try {
                        startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                },
                initial = capture.stack,
                initialShelfOpen = capture.shelfOpen,
                initialStatusOpen = capture.statusOpen,
                version = appVersion,
                captureWidthDp = capture.windowWidthDp,
                technicalDetailsRequest = technicalDetailsRequest,
            )
            if (journalSheetOpenState) {
                // 🔴 The theme is applied INSIDE `PhoneShell`, and this sheet is a sibling of it
                // rather than a child — so it had been drawing on Material's stock scheme: a
                // lavender header, a purple `close`, a purple `try again`. The journal is the
                // app's content half and cannot be the one surface in a different palette.
                PhoneTheme {
                JournalSheet(
                    presentation = currentMarkPresentation,
                    sessionFactory = {
                        PhoneJournalTestHooks.sessionOverride?.invoke()
                            ?: LiveJournalSheetSession(
                                JournalBrowserSession(
                                    publisher = stores.publisher,
                                    upstreamFactory = JournalBrowserUpstreamAdapter(stores.publisher),
                                    diag = { PhoneDiagLog.emit(it) },
                                ),
                            )
                    },
                    onClose = { journalOpen = false },
                    onPairingRepair = {
                        journalOpen = false
                        openPairingScanner()
                    },
                    initialPath = journalPath,
                )
                }
            }
            val migrationNeedsAnswer = migrationRecord?.stage in setOf(
                PairingMigrationStage.OFFER_NOT_SHOWN,
                PairingMigrationStage.SHOWN_DEFERRED,
                PairingMigrationStage.LISTING,
                PairingMigrationStage.AWAITING_SELECTION,
            )
            if (migrationDialogOpen) {
                val copy = migrationCopy(migrationSurface, migrationSelected?.displayLabel)
                val expected = migrationRecord?.generation
                    ?: (pairingSnapshot as? PairingGraphSnapshot.Committed)?.pairing
                AlertDialog(
                    onDismissRequest = ::deferMigration,
                    title = { Text(copy.title) },
                    text = {
                        Column {
                            copy.body?.let { Text(it) }
                            if (!migrationBusy && !migrationStoreUnavailable &&
                                migrationSurface == MigrationSurface.OFFER && migrationNeedsAnswer
                            ) {
                                TextButton(onClick = {
                                    val generation = expected ?: return@TextButton
                                    migrationListing = null
                                    performMigration(MigrationSurface.PREPARING) { it.listClients(generation) }
                                }) { Text("choose a device") }
                            }
                            if (!migrationBusy && !migrationStoreUnavailable &&
                                migrationSurface in setOf(MigrationSurface.PICKER, MigrationSurface.PICKER_EMPTY)
                            ) {
                                migrationListing?.clients.orEmpty().forEach { client ->
                                    TextButton(onClick = {
                                        val listing = migrationListing ?: return@TextButton
                                        migrationSelected = client
                                        performMigration(MigrationSurface.PREPARING) { it.selectTarget(listing, client.cid) }
                                    }) { Text(client.displayLabel) }
                                }
                            }
                            if (migrationSurface == MigrationSurface.TARGET_UNAVAILABLE &&
                                !migrationBusy && !migrationStoreUnavailable
                            ) {
                                TextButton(onClick = {
                                    val generation = expected ?: return@TextButton
                                    performMigration(MigrationSurface.PREPARING) { it.listClients(generation) }
                                }) { Text("choose a device") }
                            }
                        }
                    },
                    confirmButton = {
                        when {
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface == MigrationSurface.OFFER && migrationNeedsAnswer ->
                                TextButton(onClick = {
                                    expected?.let { generation ->
                                        performMigration(MigrationSurface.DECIDING) { it.keepBoth(generation) }
                                    }
                                }) { Text("keep both") }
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface == MigrationSurface.PICKER ->
                                TextButton(onClick = ::deferMigration) { Text("cancel") }
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface == MigrationSurface.PICKER_EMPTY ->
                                TextButton(onClick = {
                                    expected?.let { generation ->
                                        performMigration(MigrationSurface.DECIDING) { it.keepBoth(generation) }
                                    }
                                }) { Text("keep both") }
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface == MigrationSurface.CONFIRM ->
                                TextButton(onClick = {
                                    val listing = migrationListing
                                    val selected = migrationSelected
                                    if (listing != null && selected != null) {
                                        migrationSelected = null
                                        performMigration(MigrationSurface.DECIDING) {
                                            it.replaceSelected(listing, selected.cid, nativeConfirmed = true)
                                        }
                                    }
                                }) { Text("replace device") }
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface == MigrationSurface.CHECKING ->
                                TextButton(onClick = {
                                    migrationRecord?.let { record ->
                                        performMigration(MigrationSurface.CHECKING) { it.resume(record.generation) }
                                    }
                                }) { Text("check again") }
                            !migrationBusy && !migrationStoreUnavailable &&
                                migrationSurface in setOf(MigrationSurface.OFFLINE, MigrationSurface.LIST_UNAVAILABLE) ->
                                TextButton(onClick = {
                                    expected?.let { generation ->
                                        performMigration(MigrationSurface.PREPARING) { it.listClients(generation) }
                                    }
                                }) { Text("try again") }
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface == MigrationSurface.UNSUPPORTED ->
                                TextButton(onClick = {
                                    expected?.let { generation ->
                                        performMigration(MigrationSurface.PREPARING) { it.retryUnsupported(generation) }
                                    }
                                }) { Text("try again") }
                            !migrationBusy && migrationSurface in setOf(MigrationSurface.STORAGE, MigrationSurface.REFUSED) ->
                                TextButton(onClick = {
                                    migrationDialogOpen = false
                                    technicalDetailsRequest += 1
                                }) { Text("technical details") }
                            !migrationBusy && migrationSurface == MigrationSurface.KEY_REFUSED ->
                                TextButton(onClick = {
                                    migrationDialogOpen = false
                                    openPairingScanner()
                                }) { Text("pair again") }
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface == MigrationSurface.TARGET_UNAVAILABLE ->
                                TextButton(onClick = {
                                    expected?.let { generation -> performMigration(MigrationSurface.DECIDING) { it.keepBoth(generation) } }
                                }) { Text("keep both") }
                        }
                    },
                    dismissButton = {
                        when {
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface == MigrationSurface.OFFER && migrationNeedsAnswer ->
                                TextButton(onClick = ::deferMigration) { Text("not now") }
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface in setOf(
                                MigrationSurface.PICKER,
                                MigrationSurface.PICKER_EMPTY,
                                MigrationSurface.CONFIRM,
                                MigrationSurface.PREPARING,
                            ) -> TextButton(onClick = ::deferMigration) { Text("cancel") }
                            !migrationBusy && !migrationStoreUnavailable && migrationSurface == MigrationSurface.TARGET_UNAVAILABLE ->
                                TextButton(onClick = ::deferMigration) { Text("not now") }
                        }
                    },
                )
            }
        }
    }

    /** The event-log word for a pairing snapshot. */
    private fun pairingDiagState(snapshot: PairingGraphSnapshot): String =
        when (snapshot) {
            is PairingGraphSnapshot.Committed -> "paired"
            else -> "none"
        }

    /**
     * What has to change before a pairing is worth a line in the event log.
     *
     * ⛔ Not the state word. The publisher republishes on every revision bump, so a log keyed on
     * `paired` / `none` would be noise — but pairing a *second* journal over the first swaps the
     * home in place with no intermediate absent state, so keying on the word alone wrote nothing
     * at all for the one flow the same pane offers (`pair a new journal`). The generation is what
     * actually moves: it is the journal's instance plus this device's own certificate.
     */
    private fun pairingDiagKey(snapshot: PairingGraphSnapshot): String =
        when (snapshot) {
            is PairingGraphSnapshot.Committed ->
                "paired:${snapshot.pairing.instanceId}:${snapshot.pairing.clientCertFingerprint}"
            else -> "none"
        }

    private fun openPairingScanner() {
        startActivity(
            Intent(this, ObserverActivity::class.java)
                .putExtra(ObserverActivity.EXTRA_SCAN_PAIR_QR, true),
        )
    }

    /**
     * Where a design capture asked the shell to open, or home.
     *
     * A design pass has to look at every surface, and reaching one by synthetic taps lands on the
     * wrong surface silently rather than failing — so the capture names the surface and the shell
     * opens it. Debuggable builds only: [captureSurfaceFromIntent] returns [CaptureSurface.Home]
     * unconditionally in a release build, so the launcher activity's exported intent surface is
     * unchanged for a shipped APK.
     */
    private data class CaptureSurface(
        val stack: PhoneRouteStack = PhoneRouteStack.Empty,
        val shelfOpen: Boolean = false,
        val statusOpen: Boolean = false,
        val capturedStatusState: LoadState<PhoneStatusSnapshot>? = null,
        val windowWidthDp: Int? = null,
    ) {
        companion object {
            val Home = CaptureSurface()
        }
    }

    private fun captureSurfaceFromIntent(): CaptureSurface {
        val extras = intent?.extras
        val phoneRouteStack = extras?.getString(EXTRA_PHONE_ROUTE)
            ?.let(::decodePhoneRoute)
            ?.let(PhoneRouteStack.Empty::showInDetail)

        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            return CaptureSurface(stack = phoneRouteStack ?: PhoneRouteStack.Empty)
        }
        if (extras == null) return CaptureSurface.Home
        val shelfOpen = extras.getBoolean(EXTRA_CAPTURE_SHELF, false)
        val statusOpen = extras.getBoolean(EXTRA_CAPTURE_STATUS, false)
        val capturedStatusState = resolvePhoneStatusCapture(
            raw = extras.getString(EXTRA_CAPTURE_DEFAULT_DETAIL_STATUS),
            debuggable = debuggable,
        )
        val windowWidthDp = resolvePhoneCaptureWidthDp(
            raw = extras.getString(EXTRA_CAPTURE_WINDOW_WIDTH),
            debuggable = debuggable,
        )
        val stack = phoneRouteStack
            ?: extras.getString(EXTRA_CAPTURE_ROUTE)
                ?.let(::decodePhoneRoute)
                ?.let(PhoneRouteStack.Empty::showInDetail)
            ?: PhoneRouteStack.Empty
        return CaptureSurface(
            stack = stack,
            shelfOpen = shelfOpen,
            statusOpen = statusOpen,
            capturedStatusState = capturedStatusState,
            windowWidthDp = windowWidthDp,
        )
    }


    /**
     * The installed version, read from the package rather than a generated constant so the shelf
     * footer does not depend on build-config generation being enabled for this module.
     */
    private val appVersion: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            .orEmpty()
    }

    /**
     * Ask for **one source's** permission, and nothing else.
     *
     * 🔴 This replaced a single `requestPermissions(phoneSpec.permissions(…))` that asked for every
     * declared type from whichever source screen the owner was on — four sequential system dialogs
     * from one tap, and a result that could not be attributed to the source the owner had asked
     * for. ⛔ Do not reintroduce the bundle: attribution is what makes an affirmative grant an
     * expression of the owner's wish for *that* source.
     *
     * ⚠ Notifications is deliberately not in here. It is not a source permission, and it now gets
     * its own moment at first intake start ([requestNotificationsOnce]) so each dialog has a cause
     * the owner can see.
     */
    private fun priorWishFor(sourceId: String): PriorSourceWish {
        val isExpressed = container.sources.isWishExpressed(sourceId)
        if (!isExpressed) return PriorSourceWish.Unexpressed
        val wish = container.sources.snapshot().sources.firstOrNull { it.sourceId == sourceId }?.wish
        return if (wish == SourceWish.On) PriorSourceWish.On else PriorSourceWish.Off
    }

    private fun routePermissionRequest(sourceId: String, prior: PriorSourceWish) {
        val permissions = container.sources.requiredPermissions(sourceId)
        if (permissions.isEmpty()) return
        val granted = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val previouslyRequested = capturePermissionRequests.wasRequested(sourceId)
        val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
        val route = capturePermissionRoute(
            granted = granted,
            previouslyRequested = previouslyRequested,
            shouldShowRationale = shouldShowRationale,
        )
        when (route) {
            CapturePermissionRoute.AlreadyGranted -> {
                requestNotificationsOnce()
            }
            CapturePermissionRoute.RequestDialog -> {
                capturePermissionRequests.recordAwaiting(sourceId, prior)
                capturePermissionRequests.recordRequested(sourceId)
                container.sources.setPermissionRequestInFlight(sourceId)
                requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST)
            }
            CapturePermissionRoute.OpenAppSettings -> {
                capturePermissionRequests.recordAwaiting(sourceId, prior)
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null),
                    ),
                )
            }
        }
    }

    /**
     * Turning a source on is an expression of the owner's wish — and if its permission is missing,
     * the same act asks for it.
     *
     * 🔴 Without this the owner flips the switch, the wish persists, and the screen lands straight
     * on `needs attention` / `permissions needed` with **no system dialog in between** — a fault
     * word arriving as the direct result of saying yes. ⛔ Do not split the two: the toggle and the
     * prompt are one owner action.
     */
    private fun onSourceWish(sourceId: String, wish: SourceWish) {
        PhoneDiagLog.appendRaw("kind=source id=$sourceId wish=${wish.name.lowercase()}")
        if (wish == SourceWish.On) {
            val prior = priorWishFor(sourceId)
            val result = sourcesViewModel.setWish(sourceId, SourceWish.On)
            if (result is app.solstone.observer.harness.SourceToggleResult.NotSaved) {
                return
            }
            if (sourceId == "audio") {
                PhoneAudioOffNoticeStore(this).clear()
            }
            ownerStopped.clear()
            container.controller.ensureObserving()
            val missing = container.sources.requiredPermissions(sourceId).any {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing) {
                routePermissionRequest(sourceId, prior)
                return
            }
            requestNotificationsOnce()
        } else {
            if (sourceId == "audio") {
                (application as? PhoneApplication)?.turnAudioOffFromApp()
                sourcesViewModel.refresh()
            } else {
                sourcesViewModel.setWish(sourceId, SourceWish.Off)
                (application as? PhoneApplication)?.applyEffectiveCapture(ownerTurnedSourceOff = true)
            }
        }
    }

    /**
     * Ask for the ongoing notification, once, when intake first starts.
     *
     * ⚠ Denial does not break intake — capture runs and the system privacy indicator still shows;
     * what is lost is the shade surface. So this never blocks and never re-asks: the shelf's
     * `notifications` row is the durable route back. ⚠ *Never re-asks* is a claim about
     * [NotificationPromptStore], not about this method — the flag it reads outlives the process,
     * which is what makes the word true.
     */
    private fun requestNotificationsOnce() {
        val ask = shouldAskForNotifications(
            sdkInt = Build.VERSION.SDK_INT,
            anySourceWishedOn = container.sources.snapshot().sources.any { it.wish == SourceWish.On },
            alreadyAsked = notificationPrompt.wasAsked(),
            alreadyGranted = container.controller.refreshPermissions().notificationsGranted,
        )
        if (!ask) return
        notificationPrompt.recordAsked()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_REQUEST)
    }

    override fun onResume() {
        super.onResume()
        migrationRefreshEpoch += 1
        // ⚠ A capture permission the owner allowed in system Settings is an affirmative grant, and
        // no in-app callback fires for it. Returning here is the event, so this is where it is
        // observed. ⛔ Not inside `refreshPermissions()` — that is a query a dozen internal paths
        // call — and ⛔ not inline: it writes a file and can start an engine.
        container.onOwnerResumed()
        statusViewModel.onHostResumed()
        ObserverNotification.ensureChannel(this)
        notificationsEnabled = ObserverNotification.notificationsEnabled(this)
        captureOwnerToken = container.captureAuthority.acquire()
        mainHandler.post(startWhenReady)
        if (BuildConfig.PUSH_REGISTRATION) {
            pushFactsExecutor?.execute {
                refreshJournalPush(stores.pushDeliveryState, repair = true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pushFactsStopped = true
        pushDeliveryUnsubscribe?.invoke()
        pushFactsExecutor?.shutdown()
    }

    private fun refreshJournalPush(state: PushDeliveryState, repair: Boolean) {
        if (pushFactsStopped) return

        val ownPackage = packageName
        val manager = getSystemService(NotificationManager::class.java)
        val postNotificationsGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val appNotificationsEnabled = if (Build.VERSION.SDK_INT >= 24) {
            manager?.areNotificationsEnabled() == true
        } else {
            true
        }
        val notificationsAllowed = manager != null && postNotificationsGranted && appNotificationsEnabled
        val journalChannelBlocked = if (Build.VERSION.SDK_INT >= 26) {
            manager?.getNotificationChannel(JournalPushPoster.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        } else {
            false
        }

        val paired = displayedSnapshot() is PairingGraphSnapshot.Committed
        val distributorPackages: Set<String>? = if (paired) {
            runCatching { UnifiedPush.getDistributors(applicationContext).toSet() }.getOrNull()
        } else {
            null
        }

        var appLabel: String? = null
        var stopped: Boolean? = null
        if (state is PushDeliveryState.WaitingForDelivery && state.unanswered && state.distributorPackage != ownPackage) {
            val targetPackage = state.distributorPackage
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(targetPackage, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(targetPackage, 0)
                }
                stopped = (appInfo.flags and ApplicationInfo.FLAG_STOPPED) != 0
                val label = appInfo.loadLabel(packageManager).toString().trim()
                appLabel = if (label.isBlank()) targetPackage else label
            } catch (_: Throwable) {
                appLabel = null
                stopped = null
            }
        }

        val coreRow = journalNotificationRow(
            state = state,
            notificationsAllowed = notificationsAllowed,
            journalChannelBlocked = journalChannelBlocked,
            distributorPackages = distributorPackages,
            ownPackage = ownPackage,
            appLabel = appLabel,
            stopped = stopped,
        )
        val phoneRow: PhoneJournalNotificationRow? = when (coreRow) {
            JournalNotificationRow.On -> PhoneJournalNotificationRow.On
            JournalNotificationRow.NeedsDeliveryApp -> PhoneJournalNotificationRow.NeedsDeliveryApp
            JournalNotificationRow.ChooseDeliveryApp -> PhoneJournalNotificationRow.ChooseDeliveryApp
            JournalNotificationRow.InsecureAddress -> PhoneJournalNotificationRow.InsecureAddress
            is JournalNotificationRow.DeliveryAppStopped -> PhoneJournalNotificationRow.DeliveryAppStopped(coreRow.appName)
            null -> null
        }
        val ownerOn = stores.journalPushOn
        // The delivery app is named only when the owner has a choice to make: two or more on this phone.
        val deliveredBy: String? = if (ownerOn && distributorPackages != null && distributorPackages.size >= 2) {
            val current = runCatching { UnifiedPush.getAckDistributor(applicationContext) }.getOrNull()
                ?: runCatching { UnifiedPush.getSavedDistributor(applicationContext) }.getOrNull()
            when {
                current == null -> null
                current == ownPackage -> EMBEDDED_DISTRIBUTOR_LABEL
                else -> runCatching {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getApplicationInfo(current, PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getApplicationInfo(current, 0)
                    }
                    info.loadLabel(packageManager).toString().trim().ifBlank { current }
                }.getOrNull()
            }
        } else {
            null
        }
        mainHandler.post {
            if (!pushFactsStopped) {
                journalNotificationRow = phoneRow
                journalPushOn = ownerOn
                journalDeliveredBy = deliveredBy
            }
        }

        if (repair) {
            val nowMillis = System.currentTimeMillis()
            if (paired && distributorPackages != null) {
                val decision = journalPushRepair(
                    pushRegistration = BuildConfig.PUSH_REGISTRATION && ownerOn,
                    pairingCommitted = true,
                    state = state,
                    nowMillis = nowMillis,
                    memory = repairMemory,
                    ownPackage = ownPackage,
                    readDistributors = { distributorPackages },
                    stopped = { pkg ->
                        if (pkg == (state as? PushDeliveryState.WaitingForDelivery)?.distributorPackage) stopped else null
                    },
                )
                repairMemory = decision.memory
                when (decision.action) {
                    JournalPushRepairAction.None -> {}
                    JournalPushRepairAction.Enqueue -> {
                        // enqueueNow can drop this request.
                        SyncScheduler.enqueueNow(applicationContext, phoneSpec.stream)
                    }
                    JournalPushRepairAction.Reregister -> {
                        stores.pushRegistration?.reregister()
                    }
                }
            } else if (!paired) {
                val decision = journalPushRepair(
                    pushRegistration = BuildConfig.PUSH_REGISTRATION,
                    pairingCommitted = false,
                    state = state,
                    nowMillis = nowMillis,
                    memory = repairMemory,
                    ownPackage = ownPackage,
                    readDistributors = { error("distributors") },
                    stopped = { error("stopped") },
                )
                repairMemory = decision.memory
            }
        }
    }

    private fun setJournalPush(on: Boolean) {
        journalPushOn = on
        if (!on) journalDeliveredBy = null
        pushFactsExecutor?.execute { stores.setJournalPushOn(on) }
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (on && needsPermission) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_REQUEST)
        }
    }

    private fun pickJournalDistributor() {
        val executor = pushFactsExecutor ?: return
        UnifiedPush.tryPickDistributor(this) { success ->
            executor.execute {
                val saved = if (success) UnifiedPush.getSavedDistributor(applicationContext) else null
                when (val action = journalPushPickerResult(success, saved)) {
                    is JournalPushPickerAction.StorePick ->
                        stores.pushRegistration?.onUserPickedDistributor(action.packageName)
                    JournalPushPickerAction.Enqueue -> {
                        // enqueueNow can drop this request.
                        SyncScheduler.enqueueNow(applicationContext, phoneSpec.stream)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as? PhoneApplication)?.addStatusListener(statusListener)
    }

    override fun onStop() {
        super.onStop()
        (application as? PhoneApplication)?.removeStatusListener(statusListener)
        connectionCheck = null
        mainHandler.removeCallbacks(startWhenReady)
        if (!container.captureAuthority.isCurrent(captureOwnerToken)) return
        container.captureAuthority.release(captureOwnerToken)
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            // ⛔ Cleared first: the refresh below recomputes every row, and clearing after it would
            // leave one recomposition still reading `setting up` over a settled answer.
            container.sources.setPermissionRequestInFlight(null)
            container.controller.onPermissionsRequested()
            val awaiting = capturePermissionRequests.readAwaiting()
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (awaiting != null) {
                if (allGranted) {
                    val result = sourcesViewModel.setWish(awaiting.sourceId, SourceWish.On)
                    if (result !is app.solstone.observer.harness.SourceToggleResult.NotSaved && awaiting.sourceId == "audio") {
                        PhoneAudioOffNoticeStore(this).clear()
                    }
                    ownerStopped.clear()
                    container.controller.ensureObserving()
                    if (ObserverForegroundService.heldCaptureForegroundTypes != null) {
                        ObserverForegroundService.refreshRunningCaptureTypes(applicationContext)
                    }
                    capturePermissionRequests.clearAwaiting()
                } else {
                    when (denialWishWrite(awaiting.priorWish)) {
                        DenialWishWrite.RemoveEntry -> container.sources.clearExpressedWish(awaiting.sourceId)
                        DenialWishWrite.KeepOff -> sourcesViewModel.setWish(awaiting.sourceId, SourceWish.Off)
                        DenialWishWrite.KeepOn -> Unit
                    }
                    (application as? PhoneApplication)?.applyEffectiveCapture(ownerTurnedSourceOff = false)
                    capturePermissionRequests.clearAwaiting()
                }
                requestNotificationsOnce()
            }
            sourcesViewModel.refresh()
            statusViewModel.refresh()
        } else if (requestCode == NOTIFICATIONS_REQUEST) {
            container.controller.onPermissionsRequested()
            sourcesViewModel.refresh()
            statusViewModel.refresh()
        }
    }

    private class AudioOffNoticeDecoratedSourcesReader(
        private val delegate: SourcesReader,
        private val readNotice: () -> app.solstone.core.model.ReasonCode,
    ) : SourcesReader by delegate {
        override fun snapshot(): app.solstone.observer.harness.SourcesReadModel {
            val base = delegate.snapshot()
            return app.solstone.observer.formfactor.phone.presentAudioOffNotice(base, readNotice()) ?: base
        }
    }

    private class PhoneShellViewModelFactory(
        private val sources: SourcesReader,
        private val readStatus: () -> app.solstone.observer.harness.HarnessBacklogStatus,
        private val asyncLoad: AsyncLoad,
        private val awaitingMarkConfirmation: () -> Boolean,
        private val recoveryCompleted: () -> Boolean,
        private val audioAwaitingCustody: () -> Boolean,
        private val unresolvedAudioInterruption: () -> Boolean,
        private val unresolvedOtherInterruption: () -> Boolean,
        private val unresolvedUnknownRecovery: () -> Boolean,
        private val capturedStatusState: LoadState<PhoneStatusSnapshot>?,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return when {
                modelClass.isAssignableFrom(SourcesViewModel::class.java) -> SourcesViewModel(sources, asyncLoad) as T
                modelClass.isAssignableFrom(PhoneStatusViewModel::class.java) ->
                    PhoneStatusViewModel(
                        read = readStatus,
                        sources = sources,
                        asyncLoad = asyncLoad,
                        awaitingMarkConfirmation = awaitingMarkConfirmation,
                        recoveryCompleted = recoveryCompleted,
                        audioAwaitingCustody = audioAwaitingCustody,
                        unresolvedAudioInterruption = unresolvedAudioInterruption,
                        unresolvedOtherInterruption = unresolvedOtherInterruption,
                        unresolvedUnknownRecovery = unresolvedUnknownRecovery,
                        capturedStatusState = capturedStatusState,
                    ) as T
                else -> throw IllegalArgumentException("unsupported view model ${modelClass.name}")
            }
        }
    }

    companion object {
        internal var promptedConfirmationThisProcess = false
        const val PERMISSION_REQUEST = 10
        // The embedded distributor delivers through Google Play services.
        const val EMBEDDED_DISTRIBUTOR_LABEL = "Google Play services"
        const val NOTIFICATIONS_REQUEST = 11
        const val RECOVERY_POLL_INTERVAL_MS = 50L

        /** Route key from [decodePhoneRoute] — e.g. `import`, `add-more`, `sd/audio`. */
        const val EXTRA_CAPTURE_ROUTE = "solstone.design.route"
        const val EXTRA_CAPTURE_SHELF = "solstone.design.shelf"
        const val EXTRA_CAPTURE_STATUS = "solstone.design.status"
        const val EXTRA_CAPTURE_DEFAULT_DETAIL_STATUS = "solstone.design.default-detail-status"
        const val EXTRA_CAPTURE_WINDOW_WIDTH = "solstone.design.window-width"
    }
}
