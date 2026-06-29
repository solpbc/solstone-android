// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import app.solstone.core.model.SourceState

class GlassesObserverRuntime(
    private val appContext: Context?,
    private val containerFactory: (Context) -> GlassesAppContainer = ::GlassesAppContainer,
) {
    private val lock = Any()
    private var container: GlassesAppContainer? = null
    private var testContainer: GlassesRuntimeContainer? = null

    internal constructor(container: GlassesRuntimeContainer) : this(appContext = null) {
        testContainer = container
    }

    val containerIfInitialized: GlassesRuntimeContainer?
        get() = synchronized(lock) { container ?: testContainer }

    fun container(): GlassesAppContainer =
        synchronized(lock) {
            val context = requireNotNull(appContext) { "appContext is required for a real GlassesAppContainer" }
            container ?: containerFactory(context).also { container = it }
        }

    fun closeForTest() {
        val containers = synchronized(lock) {
            val current = container
            val test = testContainer
            container = null
            testContainer = null
            current to test
        }
        containers.first?.close()
        containers.second?.close()
    }

    fun rehydrateFromForegroundServiceStart() {
        runCatching { runtimeContainer().controller.rehydrate() }
    }

    fun observeStart(): RuntimeCommandResult {
        val controller = runtimeContainer().controller
        if (!controller.start()) {
            return CommandBlocked(RuntimeCommandBlockReason.MissingPermissions)
        }
        return verdictFromDiagnostics()
    }

    fun observeStop(): RuntimeCommandResult {
        runtimeContainer().controller.stop()
        return CommandSucceeded
    }

    fun syncNow(): RuntimeCommandResult {
        runtimeContainer().controller.syncNow()
        return CommandSucceeded
    }

    fun pairLink(raw: String): RuntimeCommandResult =
        try {
            val result = runtimeContainer().controller.onScannedPairLink(raw)
                ?: return CommandBlocked(RuntimeCommandBlockReason.InvalidPairLinkOrCameraBusy)
            if (result.pairStatus in 200..299 && result.statusStatus in 200..299) {
                CommandSucceeded
            } else {
                CommandBlocked(RuntimeCommandBlockReason.PairingProbeFailed)
            }
        } catch (_: Exception) {
            CommandBlocked(RuntimeCommandBlockReason.PairingProbeFailed)
        }

    fun speakStatus(): RuntimeCommandResult {
        runtimeContainer().speakCurrentStatus()
        return verdictFromDiagnostics()
    }

    fun speakNeedsAttention(): RuntimeCommandResult {
        runtimeContainer().speakNeedsAttention()
        return CommandSucceeded
    }

    private fun runtimeContainer(): GlassesRuntimeContainer =
        synchronized(lock) { testContainer } ?: container()

    private fun verdictFromDiagnostics(): RuntimeCommandResult {
        val diagnostics = runtimeContainer().controller.diagnostics()
        return if (diagnostics.state == SourceState.ON) {
            CommandSucceeded
        } else {
            CommandNeedsAttention(diagnostics.state, diagnostics.reason)
        }
    }

}
