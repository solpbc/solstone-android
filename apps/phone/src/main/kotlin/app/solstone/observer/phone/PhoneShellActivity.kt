// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.NotificationManager
import android.content.ActivityNotFoundException
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
import app.solstone.observer.formfactor.phone.supportState
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStartMode
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.FileSourceWishStore
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.diagnostics.DiagnosticLogRead
import app.solstone.platform.work.forgetPushAfterCleared
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.observer.formfactor.phone.CHECK_CONNECTION_REACHED
import app.solstone.observer.formfactor.phone.PhoneTheme
import app.solstone.observer.formfactor.phone.CHECK_CONNECTION_RUNNING
import app.solstone.observer.formfactor.phone.CHECK_CONNECTION_UNREACHED
import app.solstone.platform.work.JournalRevokeOutcome
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
import app.solstone.core.push.JournalNotificationRow
import app.solstone.core.push.journalNotificationRow
import app.solstone.core.push.journalOpenPath
import app.solstone.core.push.journalPushRepair
import app.solstone.core.push.journalPushPickerResult
import app.solstone.core.push.JournalPushPickerAction
import app.solstone.core.push.JournalPushRepairAction
import app.solstone.core.push.JournalPushRepairMemory
import app.solstone.core.push.PushDeliveryState
import app.solstone.observer.formfactor.phone.PhoneJournalNotificationRow
import org.unifiedpush.android.connector.UnifiedPush
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PhoneShellActivity : ComponentActivity() {
    private lateinit var container: ObserverAppContainer
    private lateinit var sourcesViewModel: SourcesViewModel
    private lateinit var statusViewModel: PhoneStatusViewModel
    private lateinit var stores: SyncStores
    private var captureOwnerToken: Long = -1L
    private val notificationPrompt by lazy { NotificationPromptStore(this) }
    private val ownerStopped by lazy { OwnerStoppedStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusListener: (HarnessBacklogStatus) -> Unit = { backlog ->
        mainHandler.post { if (::statusViewModel.isInitialized) statusViewModel.publish(backlog) }
    }
    private var notificationsEnabled by mutableStateOf(false)
    private var journalNotificationRow by mutableStateOf<PhoneJournalNotificationRow?>(null)
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
        val factory = PhoneShellViewModelFactory(
            sources = container.sources,
            readStatus = PhoneStatusSupplier.forContainer(container),
            asyncLoad = container.asyncLoad,
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
            var journalKeptItsRecord by remember { mutableStateOf(false) }
            // One routine, two entry points. The pane that was pressed is what decides which
            // pane's failure note the owner is shown.
            val unpairFrom: (Boolean) -> Unit = { fromThisDevice ->
                journalMutationFailed = false
                journalMutationFromThisDevice = fromThisDevice
                journalKeptItsRecord = false
                PhoneDiagLog.appendRaw("kind=unpair")
                shellScope.launch {
                    // 🔴 The journal half runs FIRST and on its own thread: it authenticates with
                    // the very credential `forget()` is about to delete. It never blocks the local
                    // half — an owner who has lost their journal still unpairs, and is told the
                    // journal kept its record rather than refused.
                    val revoke = withContext(Dispatchers.IO) {
                        revokeThisDeviceOnJournal(stores.publisher)
                    }
                    PhoneDiagLog.appendRaw("kind=unpair revoke=${revoke.name.lowercase()}")
                    journalKeptItsRecord = revoke == JournalRevokeOutcome.UNREACHED
                    if (stores.publisher.forget() is GraphMutationResult.Cleared) {
                        forgetPushAfterCleared(stores)
                        statusViewModel.refresh()
                    } else {
                        journalMutationFailed = true
                        journalKeptItsRecord = false
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
                onDispose {
                    pairingSubscription.cancel()
                    removeMarkListener()
                }
            }
            LaunchedEffect(pairingSnapshot.sequenceNumber) {
                if (pairingSnapshot !is PairingGraphSnapshot.Committed) journalOpen = false
                // The note is about the journal this device just left. Once another pairing commits,
                // "your journal" names the new one, and the note would read as being about it.
                if (pairingSnapshot is PairingGraphSnapshot.Committed) journalKeptItsRecord = false
            }
            val currentPairing = (pairingSnapshot as? PairingGraphSnapshot.Committed)?.pairing
            val currentMarkPresentation = if (currentPairing != null && markGeneration != currentPairing) {
                JournalMarkPresentation.Loading
            } else {
                markPresentation
            }
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
            PhoneObserverScreen(
                loadState = sourcesViewModel.sourcesState,
                status = snapshot?.status,
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
                onGrantPermissions = { sourceId -> requestSourcePermissions(sourceId) },
                onConnectJournal = {
                    openPairingScanner()
                },
                onOpenJournal = {
                    journalPath = null
                    journalOpen = true
                },
                journalPaired = pairingSnapshot is PairingGraphSnapshot.Committed,
                journalMarkPresentation = currentMarkPresentation,
                journalSheetOpen = journalOpen,
                journalFacts = journalFacts,
                journalPushEnabled = BuildConfig.PUSH_REGISTRATION,
                journalNotificationRow = journalNotificationRow,
                onChooseJournalDeliveryApp = { pickJournalDistributor() },
                hapticsEnabled = hapticsEnabled,
                notificationsEnabled = notificationsEnabled,
                eventLog = eventLog,
                problemReports = problemReports.map { it.savedAt },
                journalMutationFailed = journalMutationFailed,
                journalMutationFromThisDevice = journalMutationFromThisDevice,
                journalKeptItsRecord = journalKeptItsRecord,
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
                        val reachable = withContext(Dispatchers.IO) {
                            runCatching { container.controller.probePlStatus() }
                                .getOrNull() is HarnessPlStatus.Reachable
                        }
                        PhoneDiagLog.appendRaw(
                            "kind=check-connection result=${if (reachable) "reached" else "unreached"}",
                        )
                        connectionCheck =
                            if (reachable) CHECK_CONNECTION_REACHED else CHECK_CONNECTION_UNREACHED
                        statusViewModel.refresh()
                    }
                },
                onForgetJournal = { unpairFrom(false) },
                onUnpairThisDevice = { unpairFrom(true) },
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
                    val build = runCatching {
                        val info = packageManager.getPackageInfo(packageName, 0)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toString()
                        else @Suppress("DEPRECATION") info.versionCode.toString()
                    }.getOrDefault("unknown")
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                supportReportUrl(
                                    version = appVersion,
                                    build = build,
                                    osVersion = Build.VERSION.RELEASE,
                                    state = supportState(phoneDefaultDetailStatusOf(statusState)),
                                ),
                            ),
                        ),
                    )
                },
                onSaveProblemReport = {
                    val body = buildString {
                        appendLine("saved_at=${java.time.Instant.now()}")
                        appendLine("app_version=$appVersion")
                        appendLine("android=${Build.VERSION.RELEASE}")
                        appendLine("state=${supportState(phoneDefaultDetailStatusOf(statusState))}")
                        appendLine()
                        append(eventLog)
                    }
                    runCatching { problemReportStore.save(body) }
                        .onSuccess { problemReports = it }
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
            )
            if (journalOpen && pairingSnapshot is PairingGraphSnapshot.Committed) {
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
    private fun requestSourcePermissions(sourceId: String) {
        val permissions = container.sources.requiredPermissions(sourceId)
        if (permissions.isEmpty()) return
        // ⚠ Told BEFORE the dialog opens, so the screen behind it reads `setting up` rather than
        // `needs attention: permissions needed` — the fault word as the direct result of saying yes.
        container.sources.setPermissionRequestInFlight(sourceId)
        requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST)
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
        sourcesViewModel.setWish(sourceId, wish)
        if (wish != SourceWish.On) return
        // 🔴 Turning a source on IS asking again, so it clears the stop and brings intake back.
        // ⛔ Without this, an owner who had pressed `stop intake` could turn a source on and watch
        // it sit there forever, because nothing would start the service.
        ownerStopped.clear()
        container.controller.ensureObserving()
        val missing = container.sources.requiredPermissions(sourceId).any {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            requestSourcePermissions(sourceId)
            // ⛔ And nothing else here. Notifications wait for the result, so the owner sees one
            // dialog at a time — stacking them is what piece one of this arc removed.
            return
        }
        // Already granted, so no dialog is coming and this is the moment intake begins.
        requestNotificationsOnce()
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
        mainHandler.post {
            if (!pushFactsStopped) journalNotificationRow = phoneRow
        }

        if (repair) {
            val nowMillis = System.currentTimeMillis()
            if (paired && distributorPackages != null) {
                val decision = journalPushRepair(
                    pushRegistration = BuildConfig.PUSH_REGISTRATION,
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
        }
        if (requestCode == PERMISSION_REQUEST || requestCode == NOTIFICATIONS_REQUEST) {
            container.controller.onPermissionsRequested()
            sourcesViewModel.refresh()
            statusViewModel.refresh()
        }
        // ⚠ Its own moment, after the source's dialog has closed rather than beside it. Gated on a
        // source actually being wished on, so a denial of the source permission does not lead
        // straight into a second prompt about notifying the owner of nothing.
        if (requestCode == PERMISSION_REQUEST) requestNotificationsOnce()
    }

    private class PhoneShellViewModelFactory(
        private val sources: SourcesReader,
        private val readStatus: () -> app.solstone.observer.harness.HarnessBacklogStatus,
        private val asyncLoad: AsyncLoad,
        private val capturedStatusState: LoadState<PhoneStatusSnapshot>?,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return when {
                modelClass.isAssignableFrom(SourcesViewModel::class.java) -> SourcesViewModel(sources, asyncLoad) as T
                modelClass.isAssignableFrom(PhoneStatusViewModel::class.java) ->
                    PhoneStatusViewModel(readStatus, sources, asyncLoad, capturedStatusState) as T
                else -> throw IllegalArgumentException("unsupported view model ${modelClass.name}")
            }
        }
    }

    private companion object {
        const val PERMISSION_REQUEST = 10
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
