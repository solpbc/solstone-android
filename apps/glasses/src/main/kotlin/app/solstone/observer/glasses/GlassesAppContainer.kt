// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.diagnostics.StatusCue
import app.solstone.core.diagnostics.statusCueFor
import app.solstone.core.observer.CapturePipeline
import app.solstone.core.observer.isProviderFresh
import app.solstone.core.pl.looksLikePairLink
import app.solstone.core.segment.Segmenter
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.RecoveryScanner
import app.solstone.core.spool.applyRecoveryActions
import app.solstone.observer.formfactor.glasses.StillQrDecoder
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessDiagnostics
import app.solstone.observer.harness.HeartbeatFreshness
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
import app.solstone.platform.power.OemGuidance
import app.solstone.platform.power.OemGuidanceCatalog
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface GlassesRuntimeContainer {
    val controller: HarnessController
    fun rehydrateInBackground()
    fun enqueueCommand(task: () -> Unit): Boolean {
        task()
        return true
    }
    fun startVisibleAsync(onResult: (Boolean) -> Unit) {
        onResult(controller.start())
    }
    fun teardownVisibleOwner(ownerToken: Long, onComplete: () -> Unit = {}) {
        controller.stop()
        onComplete()
    }
    fun speakCurrentStatus()
    fun speakNeedsAttention()
    fun speakCue(cue: StatusCue)
    fun close()
}

class GlassesAppContainer(private val context: Context) : GlassesRuntimeContainer {
    val cameraLock = SingleHolderCameraLock()
    val captureAuthority = VisibleCaptureOwnerRegistry()
    private val captureSetup = createCaptureSetup(context, cameraLock)
    private val database: SolstonePersistenceDatabase = openSolstonePersistenceDatabase(context)
    private val spoolDir = context.filesDir.toPath().resolve("spool")
    private val funnel = GlassesMutationFunnel(
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "glasses-funnel").also { it.isDaemon = true }
        },
        diag = GlassesDiagLog::appendRaw,
    )
    private val reads = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "glasses-reads").also { it.isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    val asyncLoad = AsyncLoad(
        background = { task -> reads.execute { task() } },
        main = { task -> mainHandler.post { task() } },
    )
    private var activePipeline: CapturePipeline? = null
    var pipelineBuildCount: Int = 0
        private set
    private val stillQrDecoder = StillQrDecoder()
    private var photoPairObserver: ContentObserver? = null
    private val appStartSeconds: Long = System.currentTimeMillis() / 1000
    private val lifecycle = IdempotentPipelineLifecycle(
        startForeground = { ObserverForegroundService.startFromVisibleContext(context) },
        stopForeground = { ObserverForegroundService.stop(context) },
        buildPipeline = ::newPipeline,
        startPipeline = { it.start() },
        stopPipeline = { it.stop() },
        isRunning = { sourceSnapshot().engineRunning },
        onActiveChanged = { activePipeline = it },
        canStart = { recoveryCompleted },
        onStartDeferred = { GlassesDiagLog.appendRaw("start-deferred reason=recovery-pending") },
        onStartCancelled = { GlassesDiagLog.appendRaw("start-deferred cancelled") },
    )

    val flavor: GlassesHarnessFlavor = createGlassesHarnessFlavor(
        context = context,
        cameraLock = cameraLock,
        lifecycle = lifecycle,
        sourceSnapshot = ::sourceSnapshot,
        database = database,
        spoolDir = spoolDir,
        visibleCaptureAuthority = captureAuthority,
    )

    override val controller: HarnessController = flavor.controller
    private val photoPairCoordinator = PhotoPairCoordinator(
        PhotoPairSeams(
            recentImageCandidates = ::recentImageCandidates,
            appStartSeconds = { appStartSeconds },
            decode = ::decodePhotoPairCandidate,
            pairingFact = controller::pairingFact,
            onScannedPairLink = controller::onScannedPairLinkClassified,
            looksLikePairLink = ::looksLikePairLink,
            unregisterWatcher = ::stopPhotoPairWatch,
            onPairingStartedCue = {
                mainHandler.post {
                    runCatching { flavor.audioFeedback.play(StatusCue.PAIRING_STARTED) }
                        .onFailure { GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName)) }
                }
            },
            onNetworkUnavailableCue = {
                mainHandler.post {
                    runCatching { flavor.audioFeedback.play(StatusCue.NETWORK_UNAVAILABLE) }
                        .onFailure { GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName)) }
                }
            },
            onRefreshCodeCue = {
                mainHandler.post {
                    runCatching { flavor.audioFeedback.play(StatusCue.REFRESH_CODE) }
                        .onFailure { GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName)) }
                }
            },
            onReconnectingCue = {
                mainHandler.post {
                    runCatching { flavor.audioFeedback.play(StatusCue.HANDSHAKE_VALID) }
                        .onFailure { GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName)) }
                }
            },
            onRetryCue = {
                mainHandler.post {
                    runCatching { flavor.audioFeedback.play(StatusCue.NEEDS_ATTENTION) }
                        .onFailure { GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName)) }
                }
            },
            onPairingFailedCue = {
                mainHandler.post {
                    runCatching { flavor.audioFeedback.play(StatusCue.PAIRING_FAILED) }
                        .onFailure { GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName)) }
                }
            },
            log = { line ->
                android.util.Log.i("GlassesPair", line)
                GlassesDiagLog.appendRaw(line)
            },
            isUsableNetworkPresent = flavor.isUsableNetworkPresent,
            nowSeconds = ::nowSeconds,
        ),
    )
    private val cuePoller = StatusCuePoller({ cueSnapshot(controller) }, flavor.audioFeedback)
    private var previousDiagnostics: HarnessDiagnostics? = null
    private var lastPostedNeedsAttention: Boolean = true
    private val reconcileQueued = AtomicBoolean(false)
    private val pollRunnable = object : Runnable {
        override fun run() {
            enqueueReconcile("poll", emitNoOwnerRefusal = false)
            mainHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
        }
    }

    // Recovery runs once on the container's background executor; the container may be built at
    // Application scope, before any observer registers hooks, so expose the completed fact too.
    @Volatile
    var recoveryCompleted: Boolean = false
        private set

    init {
        // Keep the flavor seam observable for mock tests; WorkManager UPDATE keeps this idempotent.
        controller.schedulePeriodicSync()
        android.util.Log.i(
            "GlassesPower",
            "oem guidance ${flavor.oemGuidance.id} autostartAvailable=${flavor.oemGuidance.autostartAvailable}",
        )
        mainHandler.post(pollRunnable)
        startPhotoPairWatch()
        funnel.execute("recovery") {
            applyRecoveryActions(RecoveryScanner(spoolDir).scan(System.currentTimeMillis()))
            SpoolRoomReconciler(spoolDir, database.segmentDao()).reconcile()
            recoveryCompleted = true
            GlassesHarnessRuntime.hooks?.onRecoveryComplete?.invoke()
            lifecycle.replayDeferredStartIfPending()
        }
    }

    override fun close() {
        stopPhotoPairWatch()
        mainHandler.removeCallbacks(pollRunnable)
        funnel.execute("close-stop") {
            runCatching { controller.stop() }
                .onFailure { GlassesDiagLog.emit(DiagEvent.CaughtException(site = "close-stop", type = it.javaClass.simpleName)) }
        }
        funnel.closeAndAwait(2, TimeUnit.SECONDS)
        reads.shutdown()
        runCatching { reads.awaitTermination(2, TimeUnit.SECONDS) }
        database.close()
    }

    override fun rehydrateInBackground() {
        enqueueReconcile("fgs-rehydrate", emitNoOwnerRefusal = true)
    }

    override fun enqueueCommand(task: () -> Unit): Boolean =
        funnel.execute("command", task)

    override fun startVisibleAsync(onResult: (Boolean) -> Unit) {
        funnel.execute("visible-start") {
            val started = runCatching { controller.start() }
                .onFailure { GlassesDiagLog.emit(DiagEvent.CaughtException(site = "visible-start", type = it.javaClass.simpleName)) }
                .getOrDefault(false)
            mainHandler.post { onResult(started) }
        }
    }

    override fun teardownVisibleOwner(ownerToken: Long, onComplete: () -> Unit) {
        funnel.execute("visible-stop") {
            runVisibleCaptureTeardown(
                stopController = { controller.stop() },
                stopForeground = { ObserverForegroundService.stop(context) },
                releaseOwner = { captureAuthority.release(ownerToken) },
                diag = { site, throwable ->
                    GlassesDiagLog.emit(DiagEvent.CaughtException(site = site, type = throwable.javaClass.simpleName))
                },
            )
            mainHandler.post { onComplete() }
        }
    }

    override fun speakCurrentStatus() {
        funnel.execute("cue") {
            runCatching {
                flavor.audioFeedback.play(statusCueFor(cueSnapshot(controller)))
            }.onFailure {
                GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName))
            }
        }
    }

    fun handleSwipe(action: SwipeAction, keyCode: Int) {
        funnel.execute("swipe") {
            runCatching {
                GlassesDiagLog.emit(DiagEvent.Swipe(keyCode, action.name))
                dispatchSwipe(
                    action,
                    ensureObserving = controller::ensureObserving,
                    stop = controller::stop,
                    announce = { flavor.audioFeedback.play(statusCueFor(cueSnapshot(controller))) },
                )
            }.onFailure {
                GlassesDiagLog.emit(DiagEvent.CaughtException(site = "swipe", type = it.javaClass.simpleName))
            }
        }
    }

    override fun speakNeedsAttention() {
        speakCue(StatusCue.NEEDS_ATTENTION)
    }

    override fun speakCue(cue: StatusCue) {
        funnel.execute("cue") {
            runCatching {
                flavor.audioFeedback.play(cue)
            }.onFailure {
                GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName))
            }
        }
    }

    fun startPhotoPairWatch() {
        if (photoPairObserver != null || controller.pairingFact() == PairingFact.PAIRED) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                funnel.execute("photo-pair") { handlePhotoPairChange() }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        photoPairObserver = observer
    }

    fun stopPhotoPairWatch() {
        val observer = photoPairObserver ?: return
        context.contentResolver.unregisterContentObserver(observer)
        photoPairObserver = null
    }

    private fun enqueueReconcile(site: String, emitNoOwnerRefusal: Boolean) {
        if (!reconcileQueued.compareAndSet(false, true)) return
        val enqueued = funnel.execute(site) {
            try {
                runCatching {
                    if (emitNoOwnerRefusal && !controller.isVisibleCaptureOwnerPresent()) {
                        GlassesDiagLog.emit(
                            DiagEvent.CaptureRefused(
                                source = DiagEvent.CaptureRefusalSource.FGS_REHYDRATE,
                                reason = DiagEvent.CaptureRefusalReason.NO_VISIBLE_OWNER,
                            ),
                        )
                    }
                    controller.reconcile(ObserverStartMode.Rehydrate)
                }.onFailure {
                    GlassesDiagLog.emit(DiagEvent.CaughtException(site = site, type = it.javaClass.simpleName))
                }
                if (site == "poll") {
                    runCatching {
                        val current = controller.diagnostics()
                        emitStateTransition(current)
                        refreshServiceNotification(current)
                        cuePoller.tick()
                    }.onFailure {
                        GlassesDiagLog.emit(DiagEvent.CaughtException(site = site, type = it.javaClass.simpleName))
                    }
                }
            } finally {
                reconcileQueued.set(false)
            }
        }
        if (!enqueued) {
            reconcileQueued.set(false)
        }
    }

    private fun newPipeline(): CapturePipeline {
        pipelineBuildCount += 1
        return CapturePipeline(
            segmenter = Segmenter(ZoneId.systemDefault()),
            spoolWriter = FileSpoolWriter(spoolDir),
            sealedSink = RoomSealedSegmentSink(database.segmentDao()),
            payloadBytes = captureSetup.payloadBytesProvider,
            engines = captureSetup.engines,
            nowProvider = System::currentTimeMillis,
            tickIntervalMs = TICK_INTERVAL_MS,
            diag = { GlassesDiagLog.appendRaw(it) },
        )
    }

    private fun emitStateTransition(current: HarnessDiagnostics) {
        val previous = previousDiagnostics
        if (previous != null && (previous.state != current.state || previous.reason != current.reason)) {
            GlassesDiagLog.emit(
                DiagEvent.StateTransition(
                    from = previous.state,
                    to = current.state,
                    reason = current.reason,
                ),
            )
        }
        previousDiagnostics = current
    }

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

    private fun handlePhotoPairChange() {
        if (controller.pairingFact() == PairingFact.PAIRED) {
            stopPhotoPairWatch()
            return
        }
        if (!imageReadGranted()) return
        photoPairCoordinator.onChange()
    }

    private fun recentImageCandidates(): List<ImageRef> {
        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
                "${MediaStore.Images.Media.DATE_ADDED} > ?",
                arrayOf(appStartSeconds.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                buildList {
                    while (cursor.moveToNext()) {
                        if (size >= PhotoPairCoordinator.MAX_CANDIDATES) break
                        add(
                            ImageRef(
                                id = cursor.getLong(idColumn),
                                dateAddedSeconds = cursor.getLong(dateAddedColumn),
                            ),
                        )
                    }
                }
            } ?: emptyList()
        } catch (exception: Exception) {
            android.util.Log.i("GlassesPair", "recent image query failed: ${exception.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun decodePhotoPairCandidate(ref: ImageRef): String? =
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ref.id)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stillQrDecoder.decode(stream)
            }
        } catch (_: Exception) {
            null
        }

    private fun imageReadGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val TICK_INTERVAL_MS = 5_000L
        const val STATUS_POLL_INTERVAL_MS = 5_000L
    }
}

data class GlassesHarnessFlavor(
    val controller: HarnessController,
    val audioFeedback: AudioFeedback,
    val oemGuidance: OemGuidance = OemGuidanceCatalog.generic,
    val heartbeatControl: HeartbeatControl? = null,
    val syncControl: SyncControl? = null,
    val exemptionVerified: () -> Boolean,
    val isUsableNetworkPresent: () -> Boolean,
)

interface HeartbeatControl {
    fun setFresh(fresh: Boolean)
}

interface SyncControl {
    val enqueuePeriodicCalls: Int
    val enqueueNowCalls: Int
}

object GlassesHarnessRuntime {
    @Volatile var runtime: GlassesObserverRuntime? = null
    val container: GlassesRuntimeContainer?
        get() = runtime?.containerIfInitialized
    var hooks: GlassesRuntimeHooks? = null
}

class GlassesRuntimeHooks {
    @Volatile var onRecoveryComplete: (() -> Unit)? = null
    @Volatile var onEvidenceLoadComplete: (() -> Unit)? = null
    @Volatile var onSyncLoadComplete: (() -> Unit)? = null
}

internal class IdempotentPipelineLifecycle<T>(
    private val startForeground: () -> Unit,
    private val stopForeground: () -> Unit,
    private val buildPipeline: () -> T,
    private val startPipeline: (T) -> Unit,
    private val stopPipeline: (T) -> Unit,
    private val isRunning: (T) -> Boolean,
    private val onActiveChanged: (T?) -> Unit,
    private val canStart: () -> Boolean = { true },
    private val onStartDeferred: () -> Unit = {},
    private val onStartCancelled: () -> Unit = {},
) : ObserverLifecycle {
    private val lock = Any()
    private var active: T? = null
    private var deferredStartPending: Boolean = false

    override fun start() {
        val shouldNotifyDeferred = synchronized(lock) {
            if (!canStart()) {
                val firstDeferred = !deferredStartPending
                deferredStartPending = true
                firstDeferred
            } else {
                null
            }
        }
        if (shouldNotifyDeferred != null) {
            if (shouldNotifyDeferred) onStartDeferred()
            return
        }
        startForeground()
        synchronized(lock) {
            val current = active
            if (current != null && isRunning(current)) return
            if (current != null) {
                try {
                    stopPipeline(current)
                } finally {
                    active = null
                    onActiveChanged(null)
                }
            }
            val pipeline = buildPipeline()
            active = pipeline
            onActiveChanged(pipeline)
            startPipeline(pipeline)
        }
    }

    override fun stop() {
        val shouldCancelDeferred = synchronized(lock) {
            val pending = deferredStartPending
            deferredStartPending = false
            pending
        }
        if (shouldCancelDeferred) onStartCancelled()
        try {
            synchronized(lock) {
                active?.let { pipeline ->
                    try {
                        stopPipeline(pipeline)
                    } finally {
                        active = null
                        onActiveChanged(null)
                    }
                }
            }
        } finally {
            stopForeground()
        }
    }

    fun replayDeferredStartIfPending() {
        val shouldStart = synchronized(lock) {
            if (deferredStartPending && canStart()) {
                deferredStartPending = false
                true
            } else {
                false
            }
        }
        if (shouldStart) start()
    }
}

internal class GlassesMutationFunnel(
    private val executor: ExecutorService,
    private val diag: (String) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val closedDiagEmitted = AtomicBoolean(false)

    fun execute(site: String, task: () -> Unit): Boolean {
        if (closed.get()) {
            emitClosedNoop(site)
            return false
        }
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            emitClosedNoop(site)
            false
        }
    }

    fun closeAndAwait(timeout: Long, unit: TimeUnit) {
        closed.set(true)
        executor.shutdown()
        runCatching { executor.awaitTermination(timeout, unit) }
    }

    private fun emitClosedNoop(site: String) {
        if (closedDiagEmitted.compareAndSet(false, true)) {
            diag("funnel-noop site=$site reason=closed")
        }
    }
}

internal fun runVisibleCaptureTeardown(
    stopController: () -> Unit,
    stopForeground: () -> Unit,
    releaseOwner: () -> Unit,
    diag: (String, Throwable) -> Unit,
) {
    try {
        runCatching { stopController() }
            .onFailure { diag("visible-stop", it) }
    } finally {
        runCatching { stopForeground() }
            .onFailure { diag("visible-stop-fgs", it) }
        runCatching { releaseOwner() }
            .onFailure { diag("visible-stop-release", it) }
    }
}
