// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.solstone.core.watch.courier.WatchCourierStrings
import app.solstone.core.watch.courier.WatchRecorderSnapshot
import app.solstone.core.watch.courier.WindowPumpStopReason
import app.solstone.observer.wear.WearMainActivity
import app.solstone.observer.wear.capture.ContinuousAudioRecorder
import app.solstone.observer.wear.spool.WatchSpoolStore

class WearCaptureForegroundService : Service() {
    companion object {
        const val ACTION_START = "app.solstone.observer.wear.action.START"
        const val ACTION_STOP = "app.solstone.observer.wear.action.STOP"
        private const val CHANNEL_ID = "solstone_wear_capture"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var recorderSnapshot: WatchRecorderSnapshot = WatchRecorderSnapshot(
            startSucceeded = false,
            terminalReason = WindowPumpStopReason.OWNER_STOP,
        )
            private set
    }

    private var recorder: ContinuousAudioRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action != ACTION_START) {
            // Ignore every action except owner start from activity after permission
            return START_NOT_STICKY
        }

        startCaptureForeground()
        return START_NOT_STICKY
    }

    private fun startCaptureForeground() {
        createNotificationChannel()
        val notification = buildOngoingNotification()

        val hasLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServiceType = if (hasLocation) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundServiceType,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val spoolStore = WatchSpoolStore(applicationContext)
        spoolStore.recoverAbandonedSessions()
        val audioRecorder = ContinuousAudioRecorder(
            context = applicationContext,
            spoolStore = spoolStore,
            onSessionStatusChanged = {
                recorderSnapshot = recorder?.snapshot() ?: WatchRecorderSnapshot(
                    startSucceeded = false,
                    terminalReason = WindowPumpStopReason.OWNER_STOP,
                )
            },
        )
        recorder = audioRecorder
        audioRecorder.start()
        recorderSnapshot = audioRecorder.snapshot()
    }

    private fun stopCapture() {
        recorder?.stop(WindowPumpStopReason.OWNER_STOP)
        recorderSnapshot = recorder?.snapshot() ?: WatchRecorderSnapshot(
            startSucceeded = false,
            terminalReason = WindowPumpStopReason.OWNER_STOP,
        )
        recorder = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                WatchCourierStrings.SOLSTONE,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "${WatchCourierStrings.SOLSTONE} wear capture"
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val launchIntent = Intent(this, WearMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            },
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(WatchCourierStrings.SOLSTONE)
            .setContentText("listening")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
