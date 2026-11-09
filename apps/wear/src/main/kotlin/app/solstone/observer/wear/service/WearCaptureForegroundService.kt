// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.solstone.core.watch.courier.*
import app.solstone.observer.wear.WearOngoingActivity
import app.solstone.observer.wear.WatchSessionCopy
import app.solstone.observer.wear.capture.ContinuousAudioRecorder
import app.solstone.observer.wear.spool.WatchSpoolStore
import app.solstone.observer.wear.worker.WatchTransferWorker

class WearCaptureForegroundService : Service() {
    companion object {
        const val ACTION_START = "app.solstone.observer.wear.action.START"
        const val ACTION_STOP = "app.solstone.observer.wear.action.STOP"
        private const val CHANNEL_ID = "solstone_wear_capture"
        private const val NOTIFICATION_ID = 1001
        @Volatile var isRunning = false; private set
        @Volatile var recorderSnapshot = WatchRecorderSnapshot(); private set
    }

    private var recorder: ContinuousAudioRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusStore: WatchCaptureStatusStore
    private var previousState: WatchSessionState? = null
    private var ending = false
    private val tick = object : Runnable {
        override fun run() {
            if (!isRunning || ending) return
            if (ContextCompat.checkSelfPermission(this@WearCaptureForegroundService, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                finish(WindowPumpStopReason.PERMISSION_FAILURE)
                return
            }
            publish()
            if (isRunning) handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        statusStore = WatchCaptureStatusStore(noBackupFilesDir)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish(WindowPumpStopReason.OWNER_STOP)
            ACTION_START -> if (!isRunning) startCapture()
            else -> if (!isRunning) stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout") // Released on every terminal path; capture follows explicit owner intent.
    private fun startCapture() {
        ending = false
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, WatchCourierStrings.SOLSTONE, NotificationManager.IMPORTANCE_LOW))
        try {
            val hasLocation = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(NOTIFICATION_ID, WearOngoingActivity.build(this, CHANNEL_ID, NOTIFICATION_ID, "starting"), type)
            isRunning = true
            recorderSnapshot = WatchRecorderSnapshot(startSucceeded = true, startedAtEpochMs = System.currentTimeMillis(), startedAtElapsedMs = SystemClock.elapsedRealtime())
            statusStore.write(WatchSessionState.STARTING, null, recorderSnapshot.startedAtEpochMs)
            previousState = WatchSessionState.STARTING
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:owner-capture").apply {
                setReferenceCounted(false)
                acquire()
            }
            recorder = ContinuousAudioRecorder(applicationContext, WatchSpoolStore(applicationContext), onSessionStatusChanged = {
                handler.post { if (isRunning && !ending) publish() }
            })
            recorder!!.start()
            publish()
            if (isRunning) handler.postDelayed(tick, 1_000L)
        } catch (_: SecurityException) {
            finish(WindowPumpStopReason.PERMISSION_FAILURE)
        } catch (_: Exception) {
            finish(if (filesDir.usableSpace < WatchCourierProtocol.STORAGE_FLOOR_BYTES) WindowPumpStopReason.STORAGE_FULL else WindowPumpStopReason.SENSOR_FAILURE)
        }
    }

    private fun publish() {
        recorderSnapshot = recorder?.snapshot() ?: recorderSnapshot
        val reduction = WatchSessionReducer.reduce(recorderSnapshot, freeSpaceBytes = filesDir.usableSpace, nowElapsedMs = SystemClock.elapsedRealtime())
        if (reduction.state == WatchSessionState.ENDED) {
            finish(recorderSnapshot.terminalReason ?: WindowPumpStopReason.STORAGE_FULL)
            return
        }
        if (previousState != reduction.state) {
            try {
                statusStore.write(reduction.state, null, recorderSnapshot.startedAtEpochMs)
            } catch (_: Exception) {
                finish(WindowPumpStopReason.STORAGE_FULL)
                return
            }
            previousState = reduction.state
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                WearOngoingActivity.build(this, CHANNEL_ID, NOTIFICATION_ID, WatchSessionCopy.label(reduction)))
        }
    }

    private fun finish(reason: WindowPumpStopReason) {
        if (ending) return
        ending = true
        handler.removeCallbacksAndMessages(null)
        val actualReason = recorder?.snapshot()?.terminalReason ?: reason
        recorder?.let { if (it.snapshot().terminalReason == null) it.stop(actualReason) }
        recorderSnapshot = (recorder?.snapshot() ?: recorderSnapshot).copy(terminalReason = actualReason)
        recorder = null
        isRunning = false
        try { statusStore.write(WatchSessionState.ENDED, actualReason, recorderSnapshot.startedAtEpochMs) } catch (_: Exception) { /* Snapshot still carries the failure in this process. */ }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        WatchTransferWorker.enqueue(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!ending && isRunning) finish(WindowPumpStopReason.PROCESS_LOSS)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
