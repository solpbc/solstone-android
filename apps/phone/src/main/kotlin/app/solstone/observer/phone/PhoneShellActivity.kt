// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.solstone.observer.formfactor.phone.PhoneObserverScreen
import app.solstone.observer.formfactor.phone.PhoneRouteStack
import app.solstone.observer.formfactor.phone.PhoneStatusViewModel
import app.solstone.observer.formfactor.phone.SourcesViewModel
import app.solstone.observer.formfactor.phone.decodePhoneRoute
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.LoadState
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startWhenReady = object : Runnable {
        override fun run() {
            if (container.recoveryCompleted) {
                container.controller.ensureObserving()
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
        val factory = PhoneShellViewModelFactory(
            sources = container.sources,
            readStatus = PhoneStatusSupplier.forContainer(container),
            asyncLoad = container.asyncLoad,
        )
        sourcesViewModel = ViewModelProvider(
            this,
            factory,
        ).get(SourcesViewModel::class.java)
        statusViewModel = ViewModelProvider(this, factory).get(PhoneStatusViewModel::class.java)
        val capture = captureSurfaceFromIntent()
        setContent {
            val snapshot = (statusViewModel.statusState as? LoadState.Loaded)?.value
            PhoneObserverScreen(
                loadState = sourcesViewModel.sourcesState,
                status = snapshot?.status,
                waiting = snapshot?.waiting.orEmpty(),
                onToggle = { id, wish -> sourcesViewModel.setWish(id, wish) },
                onStartObserving = { container.controller.ensureObserving() },
                onConnectJournal = {
                    startActivity(
                        Intent(this, ObserverActivity::class.java)
                            .putExtra(ObserverActivity.EXTRA_SCAN_PAIR_QR, true),
                    )
                },
                initial = capture.stack,
                initialShelfOpen = capture.shelfOpen,
                initialStatusOpen = capture.statusOpen,
                version = appVersion,
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
    ) {
        companion object {
            val Home = CaptureSurface()
        }
    }

    private fun captureSurfaceFromIntent(): CaptureSurface {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return CaptureSurface.Home
        val extras = intent?.extras ?: return CaptureSurface.Home
        val shelfOpen = extras.getBoolean(EXTRA_CAPTURE_SHELF, false)
        val statusOpen = extras.getBoolean(EXTRA_CAPTURE_STATUS, false)
        val stack = extras.getString(EXTRA_CAPTURE_ROUTE)
            ?.let(::decodePhoneRoute)
            ?.let(PhoneRouteStack.Empty::showInDetail)
            ?: PhoneRouteStack.Empty
        return CaptureSurface(stack = stack, shelfOpen = shelfOpen, statusOpen = statusOpen)
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

    override fun onResume() {
        super.onResume()
        captureOwnerToken = container.captureAuthority.acquire()
        mainHandler.post(startWhenReady)
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(startWhenReady)
        if (!container.captureAuthority.isCurrent(captureOwnerToken)) return
        container.captureAuthority.release(captureOwnerToken)
    }

    private class PhoneShellViewModelFactory(
        private val sources: SourcesReader,
        private val readStatus: () -> app.solstone.observer.harness.HarnessBacklogStatus,
        private val asyncLoad: AsyncLoad,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return when {
                modelClass.isAssignableFrom(SourcesViewModel::class.java) -> SourcesViewModel(sources, asyncLoad) as T
                modelClass.isAssignableFrom(PhoneStatusViewModel::class.java) ->
                    PhoneStatusViewModel(readStatus, sources, asyncLoad) as T
                else -> throw IllegalArgumentException("unsupported view model ${modelClass.name}")
            }
        }
    }

    private companion object {
        const val RECOVERY_POLL_INTERVAL_MS = 50L

        /** Route key from [decodePhoneRoute] — e.g. `import`, `add-more`, `sd/audio`. */
        const val EXTRA_CAPTURE_ROUTE = "solstone.design.route"
        const val EXTRA_CAPTURE_SHELF = "solstone.design.shelf"
        const val EXTRA_CAPTURE_STATUS = "solstone.design.status"
    }
}
