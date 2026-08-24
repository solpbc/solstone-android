// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.solstone.observer.formfactor.phone.PhoneObserverScreen
import app.solstone.observer.formfactor.phone.PhoneStatusModel
import app.solstone.observer.formfactor.phone.SourcesViewModel
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.SourcesReader
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverApplication
import app.solstone.observer.scaffold.ObserverHarnessRuntime

class PhoneShellActivity : ComponentActivity() {
    private lateinit var container: ObserverAppContainer
    private lateinit var sourcesViewModel: SourcesViewModel
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
        sourcesViewModel = ViewModelProvider(
            this,
            PhoneShellSourcesViewModelFactory(container.sources, container.asyncLoad),
        ).get(SourcesViewModel::class.java)
        setContent {
            PhoneObserverScreen(
                loadState = sourcesViewModel.sourcesState,
                status = SHELL_STATUS,
                onToggle = { id, wish -> sourcesViewModel.setWish(id, wish) },
            )
        }
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

    private class PhoneShellSourcesViewModelFactory(
        private val sources: SourcesReader,
        private val asyncLoad: AsyncLoad,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SourcesViewModel::class.java)) {
                "unsupported view model ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return SourcesViewModel(sources, asyncLoad) as T
        }
    }

    private companion object {
        const val RECOVERY_POLL_INTERVAL_MS = 50L

        // The shell does not yet derive link, queue, or network status. This value
        // renders the conservative not-paired pill rather than claiming a link or
        // network state we have not established.
        val SHELL_STATUS = PhoneStatusModel(
            paired = false,
            online = false,
            pendingCount = 0,
            hasContentPending = false,
        )
    }
}
