// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.diagnostics.StatusCue
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SyncNowResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GlassesObserverRuntime(
    private val appContext: Context?,
    private val containerFactory: (Context) -> GlassesAppContainer = ::GlassesAppContainer,
    private val bootstrapExecutorFactory: () -> ExecutorService = {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "glasses-bootstrap").also { it.isDaemon = true }
        }
    },
    private val diag: (DiagEvent) -> Unit = GlassesDiagLog::emit,
    private val testContainerFactory: (() -> GlassesRuntimeContainer)? = null,
) : GlassesRuntimeCommands {
    private val lock = Any()
    private var container: GlassesAppContainer? = null
    private var testContainer: GlassesRuntimeContainer? = null
    private var factoryContainer: GlassesRuntimeContainer? = null

    internal constructor(container: GlassesRuntimeContainer) : this(appContext = null) {
        testContainer = container
    }

    internal constructor(
        containerFactory: () -> GlassesRuntimeContainer,
        bootstrapExecutorFactory: () -> ExecutorService = {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "glasses-bootstrap").also { it.isDaemon = true }
            }
        },
        diag: (DiagEvent) -> Unit = GlassesDiagLog::emit,
    ) : this(
        appContext = null,
        bootstrapExecutorFactory = bootstrapExecutorFactory,
        diag = diag,
        testContainerFactory = containerFactory,
    )

    val containerIfInitialized: GlassesRuntimeContainer?
        get() = synchronized(lock) { container ?: testContainer ?: factoryContainer }

    fun container(): GlassesAppContainer =
        synchronized(lock) {
            val context = requireNotNull(appContext) { "appContext is required for a real GlassesAppContainer" }
            container ?: containerFactory(context).also { container = it }
        }

    fun closeForTest() {
        val containers = synchronized(lock) {
            val current = container
            val test = testContainer
            val factory = factoryContainer
            container = null
            testContainer = null
            factoryContainer = null
            listOfNotNull(current, test, factory)
        }
        containers.forEach { it.close() }
    }

    fun rehydrateFromForegroundServiceStart() {
        val bootstrap = bootstrapExecutorFactory()
        try {
            bootstrap.execute {
                try {
                    runtimeContainer().rehydrateInBackground()
                } catch (t: Throwable) {
                    diag(DiagEvent.CaughtException(site = "fgs-rehydrate", type = t.javaClass.simpleName))
                } finally {
                    bootstrap.shutdown()
                }
            }
        } catch (t: Throwable) {
            diag(DiagEvent.CaughtException(site = "fgs-rehydrate", type = t.javaClass.simpleName))
            bootstrap.shutdown()
        }
    }

    fun enqueueCommand(task: () -> Unit): Boolean =
        runtimeContainer().enqueueCommand(task)

    override fun observeStart(): RuntimeCommandResult {
        val controller = runtimeContainer().controller
        if (!controller.start()) {
            if (!controller.isVisibleCaptureOwnerPresent()) {
                GlassesDiagLog.emit(
                    DiagEvent.CaptureRefused(
                        source = DiagEvent.CaptureRefusalSource.RUNTIME_COMMAND,
                        reason = DiagEvent.CaptureRefusalReason.NO_VISIBLE_OWNER,
                    ),
                )
                return CommandBlocked(RuntimeCommandBlockReason.RuntimeUnavailable)
            }
            val permissions = controller.permissionStatus
            when {
                !permissions.cameraGranted -> GlassesDiagLog.emit(
                    DiagEvent.CaptureRefused(
                        source = DiagEvent.CaptureRefusalSource.RUNTIME_COMMAND,
                        reason = DiagEvent.CaptureRefusalReason.CAMERA_PERMISSION_MISSING,
                    ),
                )
                !permissions.microphoneGranted -> GlassesDiagLog.emit(
                    DiagEvent.CaptureRefused(
                        source = DiagEvent.CaptureRefusalSource.RUNTIME_COMMAND,
                        reason = DiagEvent.CaptureRefusalReason.MIC_PERMISSION_MISSING,
                    ),
                )
            }
            return CommandBlocked(RuntimeCommandBlockReason.MissingPermissions)
        }
        return verdictFromDiagnostics()
    }

    override fun observeStop(): RuntimeCommandResult {
        runtimeContainer().controller.stop()
        return CommandSucceeded
    }

    override fun syncNow(): RuntimeCommandResult =
        when (runtimeContainer().controller.syncNow()) {
            SyncNowResult.Enqueued -> CommandSucceeded
            is SyncNowResult.NotPaired -> CommandBlocked(RuntimeCommandBlockReason.NotPaired)
            is SyncNowResult.EnqueueFailed -> CommandBlocked(RuntimeCommandBlockReason.SyncEnqueueFailed)
        }

    override fun pairLink(raw: String): RuntimeCommandResult =
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

    override fun speakStatus(): RuntimeCommandResult {
        runtimeContainer().speakCurrentStatus()
        return verdictFromDiagnostics()
    }

    override fun speakNeedsAttention(): RuntimeCommandResult {
        runtimeContainer().speakNeedsAttention()
        return CommandSucceeded
    }

    fun speakCue(cue: StatusCue) {
        runtimeContainer().speakCue(cue)
    }

    private fun runtimeContainer(): GlassesRuntimeContainer =
        synchronized(lock) { testContainer ?: factoryContainer }
            ?: testContainerFactory?.let { factory ->
                synchronized(lock) {
                    testContainer ?: factoryContainer ?: factory().also { factoryContainer = it }
                }
            }
            ?: container()

    private fun verdictFromDiagnostics(): RuntimeCommandResult {
        val diagnostics = runtimeContainer().controller.diagnostics()
        return if (diagnostics.state == SourceState.ON) {
            CommandSucceeded
        } else {
            CommandNeedsAttention(diagnostics.state, diagnostics.reason)
        }
    }

}
