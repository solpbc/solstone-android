// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.solstone.core.observer.CapturePipeline
import app.solstone.core.observer.isProviderFresh
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.segment.Segmenter
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.RecoveryScanner
import app.solstone.core.spool.applyRecoveryActions
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.BacklogStatusReader
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessDiagnostics
import app.solstone.observer.harness.HarnessJournalCacheState
import app.solstone.observer.harness.JournalCacheCoordinator
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.ObserverStartMode
import app.solstone.observer.harness.FileSourceWishStore
import app.solstone.observer.harness.RealBacklogStatusReader
import app.solstone.observer.harness.SourceRegistry
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.SourceToggleResult
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.sourceRuntimeSnapshotFromEngines
import app.solstone.observer.harness.VisibleCaptureOwnerRegistry
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.JournalCacheEvictionService
import app.solstone.platform.persistence.room.JournalCacheLimitStore
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.SpoolRoomReconciler
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.observer.harness.CaptureRestartSequencer
import app.solstone.observer.harness.ServiceDestroyWaitSeam
import app.solstone.observer.harness.SharedPreferencesDesiredObservingStore
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

interface ObserverRuntimeContainer {
    val controller: HarnessController
    val cameraLock: SingleHolderCameraLock
    val captureAuthority: VisibleCaptureOwnerRegistry
    val asyncLoad: AsyncLoad
    val flavor: SharedObserverFlavor
    val sources: SourceRegistry
    val backlogStatus: BacklogStatusReader
    val recoveryCompleted: Boolean
    fun setBackgroundStatusRefreshListener(listener: (() -> Unit)?)
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
    private val journalCacheLimitStore = JournalCacheLimitStore(context.filesDir.resolve("journal-cache-limit"))
    private val journalCacheService = JournalCacheEvictionService(spoolDir, database.segmentDao(), journalCacheLimitStore)
    private val background = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    override val asyncLoad = AsyncLoad(
        background = { task -> background.execute { task() } },
        main = { task -> mainHandler.post { task() } },
    )
    private val journalCacheCoordinator = JournalCacheCoordinator(
        canRun = { recoveryCompleted },
        submit = { task ->
            try {
                background.execute(task)
                true
            } catch (_: RejectedExecutionException) {
                false
            }
        },
        monotonicElapsedMs = SystemClock::elapsedRealtime,
        snapshot = journalCacheService::snapshot,
        saveLimitToStore = journalCacheLimitStore::save,
        nowEpochMs = System::currentTimeMillis,
        runPass = journalCacheService::runPass,
    )

    @Volatile private var activePipeline: CapturePipeline? = null
    private var previousDiagnostics: HarnessDiagnostics? = null
    private var lastPostedSignature: String? = null
    @Volatile private var backgroundStatusRefreshListener: (() -> Unit)? = null
    private val destroyLock = Object()
    private val destroyWaitSeam = ServiceDestroyWaitSeam { timeoutMs ->
        synchronized(destroyLock) {
            if (ObserverForegroundService.heldCaptureForegroundTypes == null) return@ServiceDestroyWaitSeam true
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (ObserverForegroundService.heldCaptureForegroundTypes == null) return@ServiceDestroyWaitSeam true
                val waitMs = deadline - System.currentTimeMillis()
                if (waitMs <= 0) break
                destroyLock.wait(waitMs.coerceAtMost(100L))
            }
            ObserverForegroundService.heldCaptureForegroundTypes == null
        }
    }
    private val lifecycle = IdempotentPipelineLifecycle(
        startForeground = { ObserverForegroundService.startFromVisibleContext(context) },
        stopForeground = { ObserverForegroundService.stop(context) },
        buildPipeline = ::newPipeline,
        startPipeline = { it.start() },
        stopPipeline = { it.stop() },
        isRunning = { sourceSnapshot().engineRunning },
        onActiveChanged = { activePipeline = it },
        canStart = { recoveryCompleted },
        onStartDeferred = { deferredStartMode = ObserverStartMode.VisibleStart },
        onAlreadyForegroundStartDeferred = { deferredStartMode = ObserverStartMode.ForegroundServiceStart },
        onStartCancelled = { deferredStartMode = null },
        destroySeam = destroyWaitSeam,
        isDesiredOn = { controller.desiredOn },
        isVisibleOwnerPresent = { captureAuthority.isVisibleOwnerPresent() },
        asyncExecutor = { task -> background.execute(task) },
        onRestartFinished = { backgroundStatusRefreshListener?.invoke() },
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
    override val backlogStatus: BacklogStatusReader =
        flavor.backlogStatus ?: RealBacklogStatusReader(database.segmentDao(), controller::probePlStatus)
    override val sources = SourceRegistry(
        controller = controller,
        registrations = captureSetup.registrations,
        main = { task -> mainHandler.post { task() } },
        wishStore = FileSourceWishStore(context.filesDir.resolve("source-wishes")),
    )
    @Volatile override var recoveryCompleted: Boolean = false
        private set
    @Volatile private var deferredStartMode: ObserverStartMode? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            journalCacheCoordinator.requestRoutinePass()
            background.execute {
                runCatching { controller.reconcile(ObserverStartMode.Rehydrate) }
                runCatching {
                    val current = controller.diagnostics()
                    previousDiagnostics = current
                    backgroundStatusRefreshListener?.invoke()
                    sources.refreshSubscribers()
                    refreshServiceNotification(current)
                }
            }
            mainHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
        }
    }

    init {
        ObserverForegroundService.onDestroyCallback = {
            synchronized(destroyLock) {
                destroyLock.notifyAll()
            }
        }
        controller.schedulePeriodicSync()
        mainHandler.post(pollRunnable)
        background.execute {
            applyRecoveryActions(RecoveryScanner(spoolDir).scan(System.currentTimeMillis()))
            SpoolRoomReconciler(spoolDir, database.segmentDao()).reconcile()
            recoveryCompleted = true
            journalCacheCoordinator.requestImmediatePass()
            ObserverHarnessRuntime.hooks?.onRecoveryComplete?.invoke()
            deferredStartMode?.let { mode ->
                deferredStartMode = null
                controller.reconcile(mode)
            }
        }
    }

    override fun close() {
        ObserverForegroundService.onDestroyCallback = null
        journalCacheCoordinator.close()
        mainHandler.removeCallbacks(pollRunnable)
        runCatching { controller.stop() }
        background.shutdown()
        runCatching { background.awaitTermination(2, TimeUnit.SECONDS) }
        database.close()
    }

    /**
     * The owner has come back to the app.
     *
     * 🔴 **Off the main thread, and that is not an optimisation.** Doing it inline in `onResume`
     * meant every activity launch could express wishes, **write the wish-store file** and **start
     * capture engines** synchronously on the main thread — real jank at every launch, and it
     * destabilised the instrumented phone-shell suite so badly that three consecutive runs failed
     * in three *different* places. A different failure set each run is contention, not a broken
     * assertion.
     *
     * ⚠ The work itself is what the founder-ruled grant-is-an-expression rule owes an owner who
     * allowed a capture permission in system Settings: no in-app callback fires for that, so
     * returning here is the only place it can be observed.
     */
    fun onOwnerResumed() {
        background.execute {
            val before = sources.snapshot().sources.count { it.wishExpressed }
            runCatching { sources.onPermissionStatus(controller.refreshPermissions()) }
            // ⚠ A Settings grant that newly expresses a source is the owner asking for intake, so it
            // owes the same `ensureObserving` that the in-app toggle does. ⛔ Only when something
            // was newly expressed — a plain resume must not re-assert an intent the owner revoked
            // with `stop intake`.
            val after = sources.snapshot().sources.count { it.wishExpressed }
            if (after > before) runCatching { controller.ensureObserving() }
        }
    }

    /**
     * Block until everything already queued on the background thread has run.
     *
     * ⚠ **A barrier, not a sleep.** The executor is single-threaded and FIFO, so a task submitted
     * now completes only after every task submitted before it — which is exactly the ordering
     * guarantee an assertion about "has the effect landed yet" needs.
     *
     * 🔴 **This exists because the instrumented phone-shell suite shares one process-wide container
     * and this one thread, so a test's assertions race work queued by whatever ran before it.**
     * Five device-gate runs over the same afternoon failed in five *different* places and one was
     * fully green; each failing test passed in isolation. That is contention, and re-running until
     * green is how a flaky gate stops meaning anything. ⛔ Do not replace a call to this with a
     * longer timeout: the timeout hides the queue instead of draining it.
     */
    fun awaitBackgroundIdle(timeoutMs: Long = 10_000L): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        return try {
            background.execute { done.countDown() }
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            true
        }
    }

    override fun rehydrateInBackground() {
        background.execute {
            runCatching { controller.reconcile(ObserverStartMode.Rehydrate) }
        }
    }

    override fun setBackgroundStatusRefreshListener(listener: (() -> Unit)?) {
        backgroundStatusRefreshListener = listener
    }

    fun journalCacheState(): HarnessJournalCacheState = journalCacheCoordinator.state()

    fun saveJournalCacheLimit(bytes: Long): HarnessJournalCacheState = journalCacheCoordinator.saveLimit(bytes)

    fun activateSourceWhenAlreadyForeground(sourceId: String): ForegroundSourceActivation {
        require(sources.snapshot().sources.any { it.sourceId == sourceId }) { "unknown source $sourceId" }
        val readiness = controller.startWhenAlreadyForeground()
        if (!readiness.allowed) return ForegroundSourceActivation.StartRefused(readiness.blockers.first())
        return ForegroundSourceActivation.Actuated(sources.setWish(sourceId, SourceWish.On))
    }

    private fun newPipeline(): CapturePipeline =
        CapturePipeline(
            segmenter = Segmenter(ZoneId.systemDefault()),
            spoolWriter = FileSpoolWriter(spoolDir),
            sealedSink = RoomSealedSegmentSink(database.segmentDao()),
            payloadBytes = captureSetup.payloadBytesProvider,
            engines = sources.engines,
            nowProvider = System::currentTimeMillis,
            tickIntervalMs = TICK_INTERVAL_MS,
        )

    fun refreshServiceNotification(current: HarnessDiagnostics = controller.diagnostics()) {
        if (!controller.desiredOn || ObserverForegroundService.heldCaptureForegroundTypes == null) {
            lastPostedSignature = null
            return
        }
        val snapshot = sources.snapshot()
        val needsAttention = snapshot.sources.any { it.wish == SourceWish.On && it.state == SourceState.NEEDS_ATTENTION } || current.state == SourceState.NEEDS_ATTENTION
        val signature = "${snapshot.sources.map { "${it.sourceId}:${it.wish}:${it.state}:${it.reason}" }}:$needsAttention"
        if (signature == lastPostedSignature) return
        ObserverForegroundService.refreshOngoingNotification(context, needsAttention)
        lastPostedSignature = signature
    }

    private fun sourceSnapshot(): SourceRuntimeSnapshot {
        val pipeline = activePipeline
        // Global reducer inputs: observer wish, all-required permissions, FGS freshness, and pairing
        // come from HarnessController; start-issued comes from the active pipeline; running and silenced
        // aggregate conditions; provider freshness is pipeline-wide; storage uses CaptureSetup's shared seam.
        // Per-source wish, declared permissions, condition running/silenced/paused/attention are assembled
        // in SourceRegistry.
        return sourceRuntimeSnapshotFromEngines(
            engines = sources.engines,
            providerEmitting = isProviderFresh(
                startedEpochMs = pipeline?.startedEpochMs(),
                lastEmissionEpochMs = pipeline?.lastEmissionEpochMs(),
                nowEpochMs = System.currentTimeMillis(),
            ),
            storageOk = captureSetup.storageOk(),
            engineStartIssued = pipeline != null,
        )
    }

    private companion object {
        const val TICK_INTERVAL_MS = 5_000L
        const val STATUS_POLL_INTERVAL_MS = 5_000L
    }
}

sealed interface ForegroundSourceActivation {
    data class StartRefused(val reason: ReasonCode) : ForegroundSourceActivation
    data class Actuated(val result: SourceToggleResult) : ForegroundSourceActivation
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
    private val onAlreadyForegroundStartDeferred: () -> Unit = onStartDeferred,
    private val onStartCancelled: () -> Unit,
    destroySeam: ServiceDestroyWaitSeam = ServiceDestroyWaitSeam { true },
    isDesiredOn: () -> Boolean = { false },
    isVisibleOwnerPresent: () -> Boolean = { false },
    private val asyncExecutor: (Runnable) -> Unit = { it.run() },
    private val onRestartFinished: () -> Unit = {},
) : ObserverLifecycle {
    private val lifecycleLock = Any()
    private val restartQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var active: T? = null
    private val sequencer = CaptureRestartSequencer(
        stopPipeline = {
            synchronized(lifecycleLock) {
                active?.let { pipeline ->
                    stopPipeline(pipeline)
                    active = null
                    onActiveChanged(null)
                }
            }
        },
        stopForeground = stopForeground,
        startServiceAndPipeline = {
            start(
                startForeground = startForeground,
                onDeferred = onStartDeferred,
            )
        },
        destroySeam = destroySeam,
        isDesiredOn = isDesiredOn,
        isVisibleOwnerPresent = isVisibleOwnerPresent,
    )

    override fun restartCaptureForHeldTypes() {
        if (!restartQueued.compareAndSet(false, true)) return
        try {
            asyncExecutor {
                try {
                    sequencer.requestRestart()
                } finally {
                    restartQueued.set(false)
                    onRestartFinished()
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            restartQueued.set(false)
            throw error
        }
    }

    override fun start() {
        if (!restartQueued.get()) start(startForeground, onStartDeferred)
    }

    override fun startWhenAlreadyForeground() {
        if (!restartQueued.get()) start(null, onAlreadyForegroundStartDeferred)
    }

    private fun start(
        startForeground: (() -> Unit)?,
        onDeferred: () -> Unit,
    ) {
        synchronized(lifecycleLock) {
            if (!canStart()) {
                onDeferred()
                return
            }
            startForeground?.invoke()
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
    }

    override fun stop() {
        sequencer.onOwnerStop()
        synchronized(lifecycleLock) {
            active?.let { pipeline ->
                stopPipeline(pipeline)
                active = null
                onActiveChanged(null)
            }
            onStartCancelled()
            stopForeground()
        }
    }
}
