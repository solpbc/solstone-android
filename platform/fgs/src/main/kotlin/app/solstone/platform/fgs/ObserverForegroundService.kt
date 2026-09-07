// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import app.solstone.core.model.ReasonCode
import java.util.concurrent.atomic.AtomicLong

class ObserverForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            refreshHeartbeat()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        dispatchLifecycle("fgs phase=create")
        refreshHeartbeat()
        handler.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hook = rehydrator
        val widgetSourceId = widgetSourceId(intent)
        val isIntakeStart = intent?.getBooleanExtra(EXTRA_INTAKE_START, false) == true
        val liveHeldTypes = heldCaptureForegroundTypes
        if (liveHeldTypes != null) {
            val plan = onStartCommandPlan(hasIntent = intent != null, hasRehydrator = hook != null, subsetEmpty = false)
            refreshOngoingNotification(this, needsAttention = plan.initialNeedsAttention)
            if (!plan.stopSelf) {
                cancelAttentionNotification(this)
            }
            dispatchLifecycle("fgs phase=start startId=$startId flags=$flags intent=${intent != null}")
            refreshHeartbeat()
            if (plan.dispatchRehydrate) {
                dispatchRehydrate(hook)
            }
            widgetSourceId?.let(::dispatchWidgetStartAccepted)
            if (isIntakeStart && widgetSourceId == null) {
                intakeStartHandler?.invoke()
            }
            if (plan.postAttentionOn102) {
                postAttentionNotification(this)
            }
            if (plan.stopSelf) {
                removeForegroundNotification()
                stopSelf()
            }
            return START_STICKY
        }

        val permissions = AndroidPermissionStatusReader(this).read()
        val declared = declaredCaptureForegroundTypes ?: emptySet()
        val granted = linkedSetOf<CaptureForegroundType>().apply {
            if (permissions.microphoneGranted) add(CaptureForegroundType.MICROPHONE)
            if (permissions.locationGranted) add(CaptureForegroundType.LOCATION)
            if (permissions.cameraGranted) add(CaptureForegroundType.CAMERA)
        }
        val subset = satisfiableCaptureForegroundTypes(
            microphoneGranted = permissions.microphoneGranted,
            cameraGranted = permissions.cameraGranted,
            locationGranted = permissions.locationGranted,
            declared = declared,
        )
        dispatchLifecycle(typesDiagLine(granted = granted, declared = declared, subset = subset))
        val plan = onStartCommandPlan(
            hasIntent = intent != null,
            hasRehydrator = hook != null,
            subsetEmpty = subset.isEmpty(),
        )
        if (!plan.enterForeground) {
            handleStartFailure(this, "EmptyCaptureForegroundSubset")
            widgetSourceId?.let { dispatchWidgetStartRefused(it, ReasonCode.PERMISSION_REVOKED) }
            stopSelf()
            return START_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                ObserverNotification.SERVICE_NOTIFICATION_ID,
                ObserverNotification.ongoing(
                    this,
                    needsAttention = plan.initialNeedsAttention,
                    decorate = true,
                    requestPromotion = true,
                    initialForegroundEntry = true,
                ),
                captureForegroundTypeMask(subset),
            )
            heldCaptureForegroundTypes = subset
            dispatchForegroundChanged(true)
        } catch (e: SecurityException) {
            handleStartFailure(this, e.javaClass.simpleName)
            widgetSourceId?.let { dispatchWidgetStartRefused(it, ReasonCode.PERMISSION_REVOKED) }
            stopSelf()
            return START_STICKY
        } catch (e: IllegalArgumentException) {
            handleStartFailure(this, e.javaClass.simpleName)
            widgetSourceId?.let { dispatchWidgetStartRefused(it, ReasonCode.PERMISSION_REVOKED) }
            stopSelf()
            return START_STICKY
        } catch (e: RuntimeException) {
            handleStartFailure(this, e.javaClass.simpleName)
            val reason = widgetRefusalReasonForStartException(e.javaClass.simpleName)
            widgetSourceId?.let { dispatchWidgetStartRefused(it, reason) }
            stopSelf()
            return START_STICKY
        }
        if (!plan.stopSelf) {
            cancelAttentionNotification(this)
        }
        dispatchLifecycle("fgs phase=start startId=$startId flags=$flags intent=${intent != null}")
        refreshHeartbeat()
        if (plan.dispatchRehydrate) {
            dispatchRehydrate(hook)
        }
        widgetSourceId?.let(::dispatchWidgetStartAccepted)
        if (isIntakeStart && widgetSourceId == null) {
            intakeStartHandler?.invoke()
        }
        if (plan.postAttentionOn102) {
            postAttentionNotification(this)
        }
        if (plan.stopSelf) {
            removeForegroundNotification()
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        dispatchLifecycle("fgs phase=destroy")
        heldCaptureForegroundTypes = null
        handler.removeCallbacks(heartbeat)
        invalidateHeartbeat()
        dispatchForegroundChanged(false)
        onDestroyCallback?.invoke()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        dispatchLifecycle("fgs phase=task-removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshHeartbeat() {
        lastBeatNanos.set(System.nanoTime())
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val START_GRACE_NANOS = 5_000_000_000L
        private const val ATTENTION_CONTENT_REQUEST_CODE = 102
        private val lastBeatNanos = AtomicLong(0L)
        private val lastStartRequestedNanos = AtomicLong(0L)

        @Volatile var declaredCaptureForegroundTypes: Set<CaptureForegroundType>? = null
        @Volatile var heldCaptureForegroundTypes: Set<CaptureForegroundType>? = null
        @Volatile var rehydrator: ObserverServiceRehydrator? = null
        @Volatile var widgetStartHandler: ObserverWidgetStartHandler? = null
        @Volatile var lifecycleDiag: ((String) -> Unit)? = null
        @Volatile var onDestroyCallback: (() -> Unit)? = null
        @Volatile var onForegroundChanged: ((Boolean) -> Unit)? = null

        fun dispatchRehydrate(hook: ObserverServiceRehydrator?) {
            hook?.onForegroundServiceStarted()
        }

        fun dispatchWidgetStartAccepted(sourceId: String) {
            widgetStartHandler?.onForegroundServiceStarted(sourceId)
        }

        fun dispatchWidgetStartRefused(sourceId: String, reason: ReasonCode) {
            widgetStartHandler?.onForegroundServiceStartRefused(sourceId, reason)
        }

        fun dispatchLifecycle(line: String) {
            lifecycleDiag?.invoke(line)
        }

        fun startFromVisibleContext(context: Context) {
            markStartRequested()
            val intent = Intent(context, ObserverForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: SecurityException) {
                handleStartFailure(context, e.javaClass.simpleName)
            } catch (e: IllegalArgumentException) {
                handleStartFailure(context, e.javaClass.simpleName)
            } catch (e: RuntimeException) {
                handleStartFailure(context, e.javaClass.simpleName)
            }
        }

        fun widgetStartIntent(context: Context, sourceId: String): Intent =
            Intent(context, ObserverForegroundService::class.java)
                .putExtra(EXTRA_WIDGET_SOURCE_ID, sourceId)

        fun stop(context: Context) {
            invalidateHeartbeat()
            context.stopService(Intent(context, ObserverForegroundService::class.java))
        }

        fun lastHeartbeatNanos(): Long? =
            lastBeatNanos.get().takeIf { it > 0L }

        fun isHeartbeatFresh(
            nowNanos: Long = System.nanoTime(),
            staleAfterNanos: Long = 15_000_000_000L,
            startGraceNanos: Long = START_GRACE_NANOS,
        ): Boolean =
            HeartbeatMonitor.isFresh(
                nowNanos,
                lastHeartbeatNanos(),
                lastStartRequestedNanos.get().takeIf { it > 0L },
                staleAfterNanos,
                startGraceNanos,
            )

        internal fun markStartRequested(nowNanos: Long = System.nanoTime()) {
            lastStartRequestedNanos.set(nowNanos)
        }

        private fun invalidateHeartbeat() {
            lastBeatNanos.set(0L)
        }

        private fun captureForegroundTypeMask(types: Set<CaptureForegroundType>): Int {
            var mask = 0
            if (CaptureForegroundType.MICROPHONE in types) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (CaptureForegroundType.LOCATION in types) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (CaptureForegroundType.CAMERA in types) {
                mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            return mask
        }

        const val EXTRA_INTAKE_START = "app.solstone.platform.fgs.extra.INTAKE_START"

        fun intakeStartIntent(context: Context): Intent =
            Intent(context, ObserverForegroundService::class.java).putExtra(EXTRA_INTAKE_START, true)

        @Volatile
        var intakeStartHandler: (() -> Unit)? = null

        private fun dispatchForegroundChanged(live: Boolean) {
            runCatching { onForegroundChanged?.invoke(live) }.onFailure {
                dispatchLifecycle("fgs notification refresh failed=${it.javaClass.simpleName}")
            }
        }

        private fun onNotificationThread(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action()
            else Handler(Looper.getMainLooper()).post { action() }
        }

        fun refreshOngoingNotification(context: Context, needsAttention: Boolean) = onNotificationThread {
            // The check and notify share the service lifecycle's main thread. A queued
            // refresh cannot pass the check, race onDestroy, then resurrect notification 101.
            if (heldCaptureForegroundTypes == null || !ObserverNotification.notificationsPermitted(context)) {
                return@onNotificationThread
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@onNotificationThread
            manager.notify(
                ObserverNotification.SERVICE_NOTIFICATION_ID,
                ObserverNotification.ongoing(context, needsAttention = needsAttention, decorate = true, requestPromotion = true),
            )
            manager.cancel(ObserverNotification.BOOT_NOTIFICATION_ID)
        }

        fun postAttentionNotification(context: Context) = postInactiveNotification(context, stopped = false)

        fun postStoppedNotification(context: Context) = postInactiveNotification(context, stopped = true)

        fun refreshInactiveNotification(context: Context, stopped: Boolean) =
            postInactiveNotification(context, stopped, onlyIfPresent = true)

        private fun postInactiveNotification(context: Context, stopped: Boolean, onlyIfPresent: Boolean = false) =
            onNotificationThread {
                if (heldCaptureForegroundTypes != null || !ObserverNotification.notificationsPermitted(context)) {
                    return@onNotificationThread
                }
                val manager = context.getSystemService(NotificationManager::class.java) ?: return@onNotificationThread
                if (onlyIfPresent && manager.activeNotifications.none { it.id == ObserverNotification.BOOT_NOTIFICATION_ID }) {
                    return@onNotificationThread
                }
                manager.notify(
                    ObserverNotification.BOOT_NOTIFICATION_ID,
                    ObserverNotification.ongoing(
                        context,
                        needsAttention = !stopped,
                        stopped = stopped,
                        decorate = true,
                        includeStopAction = false,
                        contentIntent = launchPendingIntent(context),
                    ),
                )
            }

        fun cancelAttentionNotification(context: Context) = onNotificationThread {
            context.getSystemService(NotificationManager::class.java)?.cancel(ObserverNotification.BOOT_NOTIFICATION_ID)
        }

        private fun handleStartFailure(context: Context, exceptionClassName: String) {
            dispatchLifecycle(startFailureDiagLine(exceptionClassName))
            postAttentionNotification(context)
        }

        private fun launchPendingIntent(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            return PendingIntent.getActivity(context, ATTENTION_CONTENT_REQUEST_CODE, launch, pendingIntentFlags())
        }

        private fun pendingIntentFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

        private fun widgetSourceId(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_WIDGET_SOURCE_ID)?.takeIf(String::isNotBlank)

        const val EXTRA_WIDGET_SOURCE_ID = "app.solstone.platform.fgs.extra.WIDGET_SOURCE_ID"
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    fun interface ObserverServiceRehydrator {
        fun onForegroundServiceStarted()
    }

    interface ObserverWidgetStartHandler {
        fun onForegroundServiceStarted(sourceId: String)
        fun onForegroundServiceStartRefused(sourceId: String, reason: ReasonCode)
    }
}
