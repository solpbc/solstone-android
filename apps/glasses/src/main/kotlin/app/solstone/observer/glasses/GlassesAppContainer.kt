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
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.SpoolRoomReconciler
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.power.OemGuidance
import app.solstone.platform.power.OemGuidanceCatalog
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface GlassesRuntimeContainer {
    val controller: HarnessController
    fun speakCurrentStatus()
    fun speakNeedsAttention()
    fun close()
}

class GlassesAppContainer(private val context: Context) : GlassesRuntimeContainer {
    val cameraLock = SingleHolderCameraLock()
    val captureAuthority = VisibleCaptureOwnerRegistry()
    private val captureSetup = createCaptureSetup(context, cameraLock)
    private val database: SolstonePersistenceDatabase = openSolstonePersistenceDatabase(context)
    private val spoolDir = context.filesDir.toPath().resolve("spool")
    private val background = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    val asyncLoad = AsyncLoad(
        background = { task -> background.execute { task() } },
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
    private val pollRunnable = object : Runnable {
        override fun run() {
            background.execute {
                runCatching { controller.reconcile(ObserverStartMode.Rehydrate) }
                    .onFailure { GlassesDiagLog.emit(DiagEvent.CaughtException(site = "poll", type = it.javaClass.simpleName)) }
                runCatching {
                    emitStateTransition(controller.diagnostics())
                    cuePoller.tick()
                }.onFailure {
                    GlassesDiagLog.emit(DiagEvent.CaughtException(site = "poll", type = it.javaClass.simpleName))
                }
            }
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
        background.execute {
            applyRecoveryActions(RecoveryScanner(spoolDir).scan(System.currentTimeMillis()))
            SpoolRoomReconciler(spoolDir, database.segmentDao()).reconcile()
            recoveryCompleted = true
            GlassesHarnessRuntime.hooks?.onRecoveryComplete?.invoke()
        }
    }

    override fun close() {
        stopPhotoPairWatch()
        mainHandler.removeCallbacks(pollRunnable)
        runCatching { controller.stop() }
        background.shutdown()
        runCatching { background.awaitTermination(2, TimeUnit.SECONDS) }
        database.close()
    }

    override fun speakCurrentStatus() {
        background.execute {
            runCatching {
                flavor.audioFeedback.play(statusCueFor(cueSnapshot(controller)))
            }.onFailure {
                GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName))
            }
        }
    }

    fun handleSwipe(action: SwipeAction, keyCode: Int) {
        background.execute {
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
        background.execute {
            runCatching {
                flavor.audioFeedback.play(StatusCue.NEEDS_ATTENTION)
            }.onFailure {
                GlassesDiagLog.emit(DiagEvent.CaughtException(site = "cue", type = it.javaClass.simpleName))
            }
        }
    }

    fun startPhotoPairWatch() {
        if (photoPairObserver != null || controller.pairingFact() == PairingFact.PAIRED) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                background.execute { handlePhotoPairChange() }
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

    private fun sourceSnapshot(): SourceRuntimeSnapshot {
        val condition = captureSetup.engines.firstOrNull()?.condition()
        val lastEmission = activePipeline?.lastEmissionEpochMs()
        return SourceRuntimeSnapshot(
            engineRunning = condition?.running == true,
            providerEmitting = lastEmission?.let { System.currentTimeMillis() - it <= PROVIDER_STALE_MS } == true,
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
        const val PROVIDER_STALE_MS = 310_000L
    }
}

data class GlassesHarnessFlavor(
    val controller: HarnessController,
    val audioFeedback: AudioFeedback,
    val oemGuidance: OemGuidance = OemGuidanceCatalog.generic,
    val heartbeatControl: HeartbeatControl? = null,
    val syncControl: SyncControl? = null,
    val exemptionVerified: () -> Boolean = { true },
    val isUsableNetworkPresent: () -> Boolean = { true },
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
) : ObserverLifecycle {
    private var active: T? = null

    override fun start() {
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
        stopForeground()
    }
}
