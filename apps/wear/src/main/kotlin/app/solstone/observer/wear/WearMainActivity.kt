// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import app.solstone.core.watch.courier.*
import app.solstone.observer.wear.service.WatchCaptureStatusStore
import app.solstone.observer.wear.service.WearCaptureForegroundService
import app.solstone.observer.wear.spool.WatchSpoolStore
import app.solstone.observer.wear.worker.WatchTransferWorker
import app.solstone.observer.wear.worker.WatchPeerStatusStore
import java.io.File

class WearMainActivity : ComponentActivity() {
    private lateinit var face: WatchFaceLayout
    private lateinit var statusStore: WatchCaptureStatusStore
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStartUntil = 0L
    private var ambient = false
    private val tick = object : Runnable {
        override fun run() {
            render()
            if (!ambient) handler.postDelayed(this, 1_000L)
        }
    }
    private val ambientObserver by lazy {
        AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambient = true
                handler.removeCallbacks(tick)
                face.setAmbient(true, ambientDetails.deviceHasLowBitAmbient, ambientDetails.burnInProtectionRequired)
            }
            override fun onUpdateAmbient() { face.updateAmbient() }
            override fun onExitAmbient() {
                ambient = false
                face.setAmbient(false)
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusStore = WatchCaptureStatusStore(noBackupFilesDir)
        val prior = runCatching { statusStore.read() }.getOrNull()
        if (!WearCaptureForegroundService.isRunning && prior != null && prior.state != WatchSessionState.ENDED) {
            val reason = if (has(Manifest.permission.RECORD_AUDIO)) WindowPumpStopReason.PROCESS_LOSS else WindowPumpStopReason.PERMISSION_FAILURE
            runCatching { statusStore.write(WatchSessionState.ENDED, reason, prior.startedAt) }
        }
        face = WatchFaceLayout(this) { primaryAction() }
        setContentView(face)
        lifecycle.addObserver(ambientObserver)
        WatchTransferWorker.enqueue(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun settings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun primaryAction() {
        when {
            WearCaptureForegroundService.isRunning -> {
                startService(Intent(this, WearCaptureForegroundService::class.java).setAction(WearCaptureForegroundService.ACTION_STOP))
            }
            pendingStartUntil > SystemClock.elapsedRealtime() -> Unit
            currentReduction().endReason != null && currentReduction().endReason != WatchSessionEndReason.OWNER_STOP -> face.openDetails()
            else -> checkAndStart()
        }
    }

    private fun checkAndStart() {
        if (WearCaptureForegroundService.isRunning) return
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        } else requestOptionalLocation()
    }
    private fun requestOptionalLocation() {
        val asked = File(noBackupFilesDir, "location-permission-asked")
        if (!asked.exists() && !has(Manifest.permission.ACCESS_COARSE_LOCATION) && !has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            runCatching { asked.createNewFile() }
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 102)
        } else requestOptionalNotification()
    }
    private fun requestOptionalNotification() {
        val asked = File(noBackupFilesDir, "notification-permission-asked")
        if (Build.VERSION.SDK_INT >= 33 && !asked.exists() && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            runCatching { asked.createNewFile() }
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
        } else startCapture()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            101 -> if (has(Manifest.permission.RECORD_AUDIO)) requestOptionalLocation() else {
                runCatching { statusStore.write(WatchSessionState.ENDED, WindowPumpStopReason.PERMISSION_FAILURE, null) }
                render()
            }
            102 -> requestOptionalNotification()
            103 -> startCapture()
        }
    }
    private fun startCapture() {
        if (WearCaptureForegroundService.isRunning || !has(Manifest.permission.RECORD_AUDIO)) return
        pendingStartUntil = SystemClock.elapsedRealtime() + 5_000L
        try {
            startForegroundService(Intent(this, WearCaptureForegroundService::class.java).setAction(WearCaptureForegroundService.ACTION_START))
        } catch (_: Exception) {
            pendingStartUntil = 0L
            runCatching { statusStore.write(WatchSessionState.ENDED, WindowPumpStopReason.PERMISSION_FAILURE, null) }
        }
        render()
    }
    private fun currentReduction(): WatchSessionReduction {
        if (WearCaptureForegroundService.isRunning) {
            pendingStartUntil = 0L
            return WatchSessionReducer.reduce(WearCaptureForegroundService.recorderSnapshot,
                freeSpaceBytes = filesDir.usableSpace, nowElapsedMs = SystemClock.elapsedRealtime())
        }
        if (pendingStartUntil > SystemClock.elapsedRealtime()) return WatchSessionReduction(WatchSessionState.STARTING)
        val saved = runCatching { statusStore.read() }.getOrNull()
        val reason = saved?.reason ?: if (saved != null && saved.state != WatchSessionState.ENDED) WindowPumpStopReason.PROCESS_LOSS else null
        return WatchSessionReducer.reduce(WatchRecorderSnapshot(terminalReason = reason))
    }

    private fun render() {
        val reduction = currentReduction()
        val engaged = WearCaptureForegroundService.isRunning || pendingStartUntil > SystemClock.elapsedRealtime()
        val failure = reduction.endReason != null && reduction.endReason != WatchSessionEndReason.OWNER_STOP
        val label = WatchSessionCopy.label(reduction)
        val attention = when {
            reduction.state == WatchSessionState.INTERRUPTED || reduction.state == WatchSessionState.ATTENTION || failure -> label
            !NotificationManagerCompat.from(this).areNotificationsEnabled() -> "notifications are off"
            else -> ""
        }
        val started = WearCaptureForegroundService.recorderSnapshot.startedAtElapsedMs
        val duration = if (engaged && started != null) "session: ${((SystemClock.elapsedRealtime() - started).coerceAtLeast(0L) / 60_000L)} min\n" else ""
        val waiting = runCatching { WatchSpoolStore(applicationContext).listPendingFiles().size }.getOrNull()
        val holding = waiting?.let { "$it sealed segments held on your watch" } ?: "held material needs attention"
        val next = when (reduction.endReason) {
            WatchSessionEndReason.PERMISSION_FAILURE -> "allow microphone access in settings, then tap start on your watch."
            WatchSessionEndReason.STORAGE_FULL -> "free space in watch settings, or bring your phone back so held material can move. then start again."
            WatchSessionEndReason.SENSOR_FAILURE -> "try a new session. if the microphone still stops, restart your watch."
            WatchSessionEndReason.PROCESS_LOSS -> "start a new session when you are ready."
            else -> if (reduction.state == WatchSessionState.INTERRUPTED || reduction.state == WatchSessionState.ATTENTION) "another app may be using the microphone. this session keeps trying; you can stop it below." else ""
        }
        val notifications = if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) "\nnotifications are off. the ongoing activity may not appear. allow notifications in settings to make it available." else ""
        val location = if (!has(Manifest.permission.ACCESS_COARSE_LOCATION) && !has(Manifest.permission.ACCESS_FINE_LOCATION)) "\nlocation access is off. audio intake can still run." else ""
        val peer = runCatching { WatchPeerStatusStore(this).read() }.getOrNull()
        val delivery = when {
            peer?.version != null && peer.version < WatchCourierProtocol.PROTOCOL_VERSION -> "update solstone on your phone to continue delivery."
            peer?.version != null && peer.version > WatchCourierProtocol.PROTOCOL_VERSION -> "update solstone on your watch to continue delivery."
            peer?.contactAt == null || System.currentTimeMillis() - peer.contactAt !in 0L..60_000L -> "phone delivery is unconfirmed. keep your phone connected and open solstone there. if it is missing, install the phone app. check Google Play services for updates on both devices. your watch keeps held material."
            else -> "your phone replied recently."
        }
        face.show(if (engaged) "stop" else if (failure) "details" else "start", label, attention,
            "$label\n$duration$holding\n$next$notifications$location\n\n$delivery\n\n${WatchCourierStrings.RELAY_NOTICE_SHORT}")
        when {
            reduction.endReason == WatchSessionEndReason.PERMISSION_FAILURE && !has(Manifest.permission.RECORD_AUDIO) -> face.setDetailAction("open settings") { settings() }
            reduction.endReason == WatchSessionEndReason.STORAGE_FULL && filesDir.usableSpace < WatchCourierProtocol.STORAGE_FLOOR_BYTES -> face.setDetailAction("open watch settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            failure -> face.setDetailAction("start again") { checkAndStart() }
            !NotificationManagerCompat.from(this).areNotificationsEnabled() -> face.setDetailAction("open settings") { settings() }
            else -> face.setDetailAction(null)
        }
    }
}
