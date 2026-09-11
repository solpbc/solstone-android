// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStartMode
import app.solstone.observer.harness.SourceWish
import app.solstone.platform.fgs.shouldAskForNotifications
import app.solstone.observer.harness.SourcesReader
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverApplication
import app.solstone.observer.scaffold.ObserverHarnessRuntime

class PhoneShellActivity : ComponentActivity() {
    private lateinit var container: ObserverAppContainer
    private lateinit var sourcesViewModel: SourcesViewModel
    private lateinit var statusViewModel: PhoneStatusViewModel
    private var captureOwnerToken: Long = -1L
    private val notificationPrompt by lazy { NotificationPromptStore(this) }
    private val ownerStopped by lazy { OwnerStoppedStore(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as ObserverApplication
        val runtime = ObserverHarnessRuntime.runtime ?: app.runtime.also {
            ObserverHarnessRuntime.runtime = it
        }
        container = runtime.container()
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
            PhoneObserverScreen(
                loadState = sourcesViewModel.sourcesState,
                status = snapshot?.status,
                waiting = snapshot?.waiting.orEmpty(),
                defaultDetailStatus = phoneDefaultDetailStatusOf(statusState),
                onRefreshStatus = statusViewModel::refresh,
                onToggle = { id, wish -> onSourceWish(id, wish) },
                onStartObserving = {
                    // ⚠ Asking again is asking: this is `resume intake` and `start intake again`,
                    // and both have to clear the owner's stop or the service will not come back.
                    ownerStopped.clear()
                    container.controller.ensureObserving()
                },
                onGrantPermissions = { sourceId -> requestSourcePermissions(sourceId) },
                onConnectJournal = {
                    startActivity(
                        Intent(this, ObserverActivity::class.java)
                            .putExtra(ObserverActivity.EXTRA_SCAN_PAIR_QR, true),
                    )
                },
                onManageLocalStorage = {
                    startActivity(
                        Intent(this, ObserverActivity::class.java)
                            .putExtra(ObserverActivity.EXTRA_SHOW_LOCAL_CACHE, true),
                    )
                },
                initial = capture.stack,
                initialShelfOpen = capture.shelfOpen,
                initialStatusOpen = capture.statusOpen,
                version = appVersion,
                captureWidthDp = capture.windowWidthDp,
            )
        }
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
        captureOwnerToken = container.captureAuthority.acquire()
        mainHandler.post(startWhenReady)
    }

    override fun onStop() {
        super.onStop()
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
