// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
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

class GlassesAppContainer(private val context: Context) {
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
    private val stillQrDecoder = StillQrDecoder()
    private var photoPairObserver: ContentObserver? = null
    private val lifecycle = object : ObserverLifecycle {
        override fun start() {
            ObserverForegroundService.startFromVisibleContext(context)
            val pipeline = newPipeline().also { activePipeline = it }
            pipeline.start()
        }

        override fun stop() {
            activePipeline?.stop()
            activePipeline = null
            ObserverForegroundService.stop(context)
        }
    }

    val flavor: GlassesHarnessFlavor = createGlassesHarnessFlavor(
        context = context,
        cameraLock = cameraLock,
        lifecycle = lifecycle,
        sourceSnapshot = ::sourceSnapshot,
        database = database,
        spoolDir = spoolDir,
    )

    val controller: HarnessController = flavor.controller
    private val cuePoller = StatusCuePoller({ cueSnapshot(controller) }, flavor.audioFeedback)
    private val pollRunnable = object : Runnable {
        override fun run() {
            background.execute { runCatching { cuePoller.tick() } }
            mainHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
        }
    }

    init {
        controller.schedulePeriodicSync()
        mainHandler.post(pollRunnable)
        startPhotoPairWatch()
        background.execute {
            applyRecoveryActions(RecoveryScanner(spoolDir).scan(System.currentTimeMillis()))
            SpoolRoomReconciler(spoolDir, database.segmentDao()).reconcile()
            GlassesHarnessRuntime.hooks?.onRecoveryComplete?.invoke()
        }
    }

    fun close() {
        stopPhotoPairWatch()
        mainHandler.removeCallbacks(pollRunnable)
        runCatching { controller.stop() }
        background.shutdown()
        runCatching { background.awaitTermination(2, TimeUnit.SECONDS) }
        database.close()
    }

    fun speakCurrentStatus() {
        background.execute {
            runCatching {
                flavor.audioFeedback.play(rawResFor(statusCueFor(cueSnapshot(controller))))
            }
        }
    }

    fun speakNeedsAttention() {
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
                handlePhotoPairChange(uri)
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
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

    private fun handlePhotoPairChange(uri: Uri?) {
        if (controller.pairingFact() == PairingFact.PAIRED || uri == null) return
        background.execute {
            val decoded = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stillQrDecoder.decode(stream)
                }
            } catch (_: Exception) {
                null
            }
            android.util.Log.i("GlassesPair", "photo uri=$uri decoded=" + (decoded?.take(60) ?: "null"))
            if (decoded != null && looksLikePairLink(decoded)) {
                mainHandler.post { runCatching { flavor.audioFeedback.play(rawResFor(StatusCue.PAIRING_READY)) } }
            }
            when (decidePhotoPair(decoded, ::looksLikePairLink, controller::onScannedPairLink)) {
                PhotoPairOutcome.FAILED -> mainHandler.post {
                    runCatching { flavor.audioFeedback.play(rawResFor(StatusCue.PAIRING_FAILED)) }
                }
                PhotoPairOutcome.IGNORED,
                PhotoPairOutcome.PAIRED,
                PhotoPairOutcome.ALREADY_CONNECTED,
                PhotoPairOutcome.RECONNECTING,
                PhotoPairOutcome.RETRY,
                -> Unit
            }
        }
    }

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
    var container: GlassesAppContainer? = null
    var hooks: GlassesRuntimeHooks? = null
}

class GlassesRuntimeHooks {
    @Volatile var onRecoveryComplete: (() -> Unit)? = null
    @Volatile var onEvidenceLoadComplete: (() -> Unit)? = null
    @Volatile var onSyncLoadComplete: (() -> Unit)? = null
}
