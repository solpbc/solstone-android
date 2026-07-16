// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.solstone.core.observer.CapturePipeline
import app.solstone.core.observer.isProviderFresh
import app.solstone.core.segment.Segmenter
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.RecoveryScanner
import app.solstone.core.spool.applyRecoveryActions
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessDiagnostics
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.ObserverStartMode
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.VisibleCaptureOwnerRegistry
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.needsAttentionForState
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.SpoolRoomReconciler
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface ObserverRuntimeContainer {
    val controller: HarnessController
    val cameraLock: SingleHolderCameraLock
    val captureAuthority: VisibleCaptureOwnerRegistry
    val asyncLoad: AsyncLoad
    val flavor: SharedObserverFlavor
    val recoveryCompleted: Boolean
    fun rehydrateInBackground()
    fun close()
}

class ObserverAppContainer(
    private val context: Context,
    private val spec: FormFactorSpec,
) : ObserverRuntimeContainer {
    override val cameraLock = SingleHolderCameraLock()
    override val captureAuthority = VisibleCaptureOwnerRegistry()
    private val captureSetup = createCaptureSetup(context, cameraLock)
    private val database: SolstonePersistenceDatabase = openSolstonePersistenceDatabase(context)
    private val spoolDir = context.filesDir.toPath().resolve("spool")
    private val background = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    override val asyncLoad = AsyncLoad(
        background = { task -> background.execute { task() } },
        main = { task -> mainHandler.post { task() } },
    )
    private var activePipeline: CapturePipeline? = null
    private var previousDiagnostics: HarnessDiagnostics? = null
    private var lastPostedNeedsAttention: Boolean = true
    private val lifecycle = IdempotentPipelineLifecycle(
        startForeground = { ObserverForegroundService.startFromVisibleContext(context) },
        stopForeground = { ObserverForegroundService.stop(context) },
        buildPipeline = ::newPipeline,
        startPipeline = { it.start() },
        stopPipeline = { it.stop() },
        isRunning = { sourceSnapshot().engineRunning },
        onActiveChanged = { activePipeline = it },
        canStart = { recoveryCompleted },
        onStartDeferred = { deferredStartPending = true },
        onStartCancelled = { deferredStartPending = false },
    )

    override val flavor: SharedObserverFlavor = buildObserverFlavor(
        context = context,
        spec = spec,
        cameraLock = cameraLock,
        lifecycle = lifecycle,
        sourceSnapshot = ::sourceSnapshot,
        database = database,
        spoolDir = spoolDir,
        visibleCaptureAuthority = captureAuthority,
    )

    override val controller: HarnessController = flavor.controller
    @Volatile override var recoveryCompleted: Boolean = false
        private set
    @Volatile private var deferredStartPending: Boolean = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            background.execute {
                runCatching { controller.reconcile(ObserverStartMode.Rehydrate) }
                runCatching {
                    val current = controller.diagnostics()
                    previousDiagnostics = current
                    refreshServiceNotification(current)
                }
            }
            mainHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
        }
    }

    init {
        controller.schedulePeriodicSync()
        mainHandler.post(pollRunnable)
        background.execute {
            applyRecoveryActions(RecoveryScanner(spoolDir).scan(System.currentTimeMillis()))
            SpoolRoomReconciler(spoolDir, database.segmentDao()).reconcile()
            recoveryCompleted = true
            ObserverHarnessRuntime.hooks?.onRecoveryComplete?.invoke()
            if (deferredStartPending) {
                deferredStartPending = false
                controller.reconcile(ObserverStartMode.VisibleStart)
            }
        }
    }

    override fun close() {
        mainHandler.removeCallbacks(pollRunnable)
        runCatching { controller.stop() }
        background.shutdown()
        runCatching { background.awaitTermination(2, TimeUnit.SECONDS) }
        database.close()
    }

    override fun rehydrateInBackground() {
        background.execute {
            runCatching { controller.reconcile(ObserverStartMode.Rehydrate) }
        }
    }

    private fun newPipeline(): CapturePipeline =
        CapturePipeline(
            segmenter = Segmenter(ZoneId.systemDefault()),
            spoolWriter = FileSpoolWriter(spoolDir),
            sealedSink = RoomSealedSegmentSink(database.segmentDao()),
            payloadBytes = captureSetup.payloadBytesProvider,
            engines = captureSetup.engines,
            nowProvider = System::currentTimeMillis,
            tickIntervalMs = TICK_INTERVAL_MS,
        )

    private fun refreshServiceNotification(current: HarnessDiagnostics) {
        if (!controller.desiredOn) {
            lastPostedNeedsAttention = true
            return
        }
        val needsAttention = needsAttentionForState(current.state)
        if (needsAttention == lastPostedNeedsAttention) return
        ObserverForegroundService.refreshOngoingNotification(context, needsAttention)
        lastPostedNeedsAttention = needsAttention
    }

    private fun sourceSnapshot(): SourceRuntimeSnapshot {
        val condition = captureSetup.engines.firstOrNull()?.condition()
        val pipeline = activePipeline
        return SourceRuntimeSnapshot(
            engineRunning = condition?.running == true,
            providerEmitting = isProviderFresh(
                startedEpochMs = pipeline?.startedEpochMs(),
                lastEmissionEpochMs = pipeline?.lastEmissionEpochMs(),
                nowEpochMs = System.currentTimeMillis(),
            ),
            storageOk = condition?.available != false,
            exemptionVerified = flavor.exemptionVerified(),
        )
    }

    private companion object {
        const val TICK_INTERVAL_MS = 5_000L
        const val STATUS_POLL_INTERVAL_MS = 5_000L
    }
}

internal class IdempotentPipelineLifecycle<T>(
    private val startForeground: () -> Unit,
    private val stopForeground: () -> Unit,
    private val buildPipeline: () -> T,
    private val startPipeline: (T) -> Unit,
    private val stopPipeline: (T) -> Unit,
    private val isRunning: (T) -> Boolean,
    private val onActiveChanged: (T?) -> Unit,
    private val canStart: () -> Boolean,
    private val onStartDeferred: () -> Unit,
    private val onStartCancelled: () -> Unit,
) : ObserverLifecycle {
    private var active: T? = null

    override fun start() {
        if (!canStart()) {
            onStartDeferred()
            return
        }
        startForeground()
        val current = active
        if (current != null && isRunning(current)) return
        if (current != null) {
            stopPipeline(current)
            active = null
            onActiveChanged(null)
        }
        val pipeline = buildPipeline()
        active = pipeline
        onActiveChanged(pipeline)
        startPipeline(pipeline)
    }

    override fun stop() {
        active?.let { pipeline ->
            stopPipeline(pipeline)
            active = null
            onActiveChanged(null)
        }
        onStartCancelled()
        stopForeground()
    }
}
