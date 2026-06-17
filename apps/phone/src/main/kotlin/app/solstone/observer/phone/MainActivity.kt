// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import app.solstone.core.diagnostics.SourceFacts
import app.solstone.core.diagnostics.reduce
import app.solstone.core.model.SourceState
import app.solstone.core.observer.CapturePipeline
import app.solstone.core.segment.Segmenter
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.RecoveryScanner
import app.solstone.core.spool.applyRecoveryActions
import app.solstone.platform.fgs.AndroidPermissionStatusReader
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.SpoolRoomReconciler
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.power.AndroidBatteryExemptionStatus
import app.solstone.platform.power.ExemptionVerifier
import app.solstone.platform.power.SharedPreferencesAutostartConfirmationStore
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var labelView: TextView
    private var setup: CaptureSetup? = null
    private var pipeline: CapturePipeline? = null
    private var database: SolstonePersistenceDatabase? = null
    private val started = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labelView = TextView(this)
        setContentView(labelView)
        updateLabel()
        setupPipeline()
        if (hasRequiredPermissions()) {
            startVisibleWork()
        } else {
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && hasRequiredPermissions()) {
            startVisibleWork()
        }
        updateLabel()
    }

    override fun onDestroy() {
        pipeline?.stop()
        database?.close()
        super.onDestroy()
    }

    private fun setupPipeline() {
        if (setup != null) return
        val captureSetup = createCaptureSetup(this)
        val db = openSolstonePersistenceDatabase(this)
        val spoolDir = filesDir.toPath().resolve("spool")
        applyRecoveryActions(RecoveryScanner(spoolDir).scan(System.currentTimeMillis()))
        SpoolRoomReconciler(spoolDir, db.segmentDao()).reconcile()
        setup = captureSetup
        database = db
        pipeline = CapturePipeline(
            segmenter = Segmenter(ZoneId.systemDefault()),
            spoolWriter = FileSpoolWriter(spoolDir),
            sealedSink = RoomSealedSegmentSink(db.segmentDao()),
            payloadBytes = captureSetup.payloadBytesProvider,
            engines = captureSetup.engines,
            nowProvider = System::currentTimeMillis,
            tickIntervalMs = TICK_INTERVAL_MS,
        )
    }

    private fun startVisibleWork() {
        if (!started.compareAndSet(false, true)) return
        ObserverForegroundService.startFromVisibleContext(this)
        pipeline?.start()
        updateLabel()
    }

    private fun updateLabel() {
        val state = reduce(sourceFacts()).first
        labelView.text = state.label()
    }

    private fun sourceFacts(): SourceFacts {
        val permissionStatus = AndroidPermissionStatusReader(this).read()
        val condition = setup?.engines?.firstOrNull()?.condition()
        val exemptionVerified = ExemptionVerifier(
            AndroidBatteryExemptionStatus(this),
            SharedPreferencesAutostartConfirmationStore(this),
        ).isExemptionVerified()
        return SourceFacts(
            desiredOn = true,
            engineRunning = condition?.running == true,
            permissionGranted = permissionStatus.microphoneGranted,
            fgsHeartbeatFresh = ObserverForegroundService.isHeartbeatFresh(),
            providerEmitting = pipeline?.lastEmissionEpochMs()
                ?.let { System.currentTimeMillis() - it <= PROVIDER_STALE_MS } == true,
            storageOk = condition?.available != false,
            linkPaired = true,
            authValid = true,
            exemptionVerified = exemptionVerified,
        )
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    private fun SourceState.label(): String =
        when (this) {
            SourceState.OFF -> "Off"
            SourceState.SETTING_UP -> "Setting up"
            SourceState.ON -> "On"
            SourceState.PAUSED -> "Paused"
            SourceState.NEEDS_ATTENTION -> "Needs attention"
        }

    private companion object {
        const val PERMISSION_REQUEST = 10
        const val PROVIDER_STALE_MS = 310_000L
        const val TICK_INTERVAL_MS = 5_000L
    }
}
