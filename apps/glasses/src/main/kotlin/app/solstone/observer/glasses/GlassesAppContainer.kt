// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.solstone.core.observer.CapturePipeline
import app.solstone.core.segment.Segmenter
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.RecoveryScanner
import app.solstone.core.spool.applyRecoveryActions
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

    init {
        controller.schedulePeriodicSync()
        background.execute {
            applyRecoveryActions(RecoveryScanner(spoolDir).scan(System.currentTimeMillis()))
            SpoolRoomReconciler(spoolDir, database.segmentDao()).reconcile()
            GlassesHarnessRuntime.hooks?.onRecoveryComplete?.invoke()
        }
    }

    fun close() {
        runCatching { controller.stop() }
        background.shutdown()
        runCatching { background.awaitTermination(2, TimeUnit.SECONDS) }
        database.close()
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

    private companion object {
        const val TICK_INTERVAL_MS = 5_000L
        const val PROVIDER_STALE_MS = 310_000L
    }
}

data class GlassesHarnessFlavor(
    val controller: HarnessController,
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
