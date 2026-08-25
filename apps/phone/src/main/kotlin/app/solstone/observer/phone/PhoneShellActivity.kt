// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.solstone.observer.formfactor.phone.PhoneObserverScreen
import app.solstone.observer.formfactor.phone.PhoneStatusViewModel
import app.solstone.observer.formfactor.phone.SourcesViewModel
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
                version = appVersion,
            )
        }
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
    }
}
