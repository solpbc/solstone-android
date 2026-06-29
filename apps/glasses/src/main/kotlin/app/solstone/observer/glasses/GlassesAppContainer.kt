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
import app.solstone.observer.harness.HeartbeatFreshness
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.SpoolRoomReconciler
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
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
    private val lifecycle = IdempotentPipelineLifecycle(
        startForeground = { ObserverForegroundService.startFromVisibleContext(context) },
        stopForeground = { ObserverForegroundService.stop(context) },
        buildPipeline = ::newPipeline,
        startPipeline = { it.start() },
        stopPipeline = { it.stop() },
        onActiveChanged = { activePipeline = it },
    )

    val flavor: GlassesHarnessFlavor = createGlassesHarnessFlavor(
        context = context,
        cameraLock = cameraLock,
        lifecycle = lifecycle,
        sourceSnapshot = ::sourceSnapshot,
        database = database,
        spoolDir = spoolDir,
    )

    override val controller: HarnessController = flavor.controller
    private val photoPairCoordinator = PhotoPairCoordinator(
        PhotoPairSeams(
            recentImageCandidates = ::recentImageCandidates,
            decode = ::decodePhotoPairCandidate,
            pairingFact = controller::pairingFact,
            onScannedPairLink = controller::onScannedPairLink,
            looksLikePairLink = ::looksLikePairLink,
            unregisterWatcher = ::stopPhotoPairWatch,
            onPairingReadyCue = {
                mainHandler.post { runCatching { flavor.audioFeedback.play(rawResFor(StatusCue.PAIRING_READY)) } }
            },
            onPairingFailedCue = {
                mainHandler.post { runCatching { flavor.audioFeedback.play(rawResFor(StatusCue.PAIRING_FAILED)) } }
            },
            log = { android.util.Log.i("GlassesPair", it) },
            nowSeconds = ::nowSeconds,
        ),
    )
    private val cuePoller = StatusCuePoller({ cueSnapshot(controller) }, flavor.audioFeedback)
    private val pollRunnable = object : Runnable {
        override fun run() {
            background.execute { runCatching { cuePoller.tick() } }
            mainHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
        }
    }

    init {
        // Keep the flavor seam observable for mock tests; WorkManager UPDATE keeps this idempotent.
        controller.schedulePeriodicSync()
        mainHandler.post(pollRunnable)
        startPhotoPairWatch()
        if (imageReadGranted()) {
            background.execute { photoPairCoordinator.onStartup() }
        }
        background.execute {
            applyRecoveryActions(RecoveryScanner(spoolDir).scan(System.currentTimeMillis()))
            SpoolRoomReconciler(spoolDir, database.segmentDao()).reconcile()
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
                flavor.audioFeedback.play(rawResFor(statusCueFor(cueSnapshot(controller))))
            }
        }
    }

    override fun speakNeedsAttention() {
        background.execute {
            runCatching {
                flavor.audioFeedback.play(rawResFor(StatusCue.NEEDS_ATTENTION))
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
        )
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
        val cutoffSeconds = nowSeconds() - PhotoPairCoordinator.MAX_AGE_SECONDS
        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf(cutoffSeconds.toString()),
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
    val heartbeatControl: HeartbeatControl? = null,
    val syncControl: SyncControl? = null,
    val exemptionVerified: () -> Boolean = { true },
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
    private val onActiveChanged: (T?) -> Unit,
) : ObserverLifecycle {
    private var active: T? = null

    override fun start() {
        startForeground()
        if (active != null) return
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
