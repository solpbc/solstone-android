// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
        val plan = onStartCommandPlan(hasIntent = intent != null, hasRehydrator = hook != null)
        try {
            startForeground(
                ObserverNotification.SERVICE_NOTIFICATION_ID,
                ObserverNotification.ongoing(this, needsAttention = plan.initialNeedsAttention, decorate = true),
            )
        } catch (e: SecurityException) {
            handleStartFailure(this, e.javaClass.simpleName)
            stopSelf()
            return START_STICKY
        } catch (e: RuntimeException) {
            if (!isForegroundStartNotAllowedException(e)) throw e
            handleStartFailure(this, e.javaClass.simpleName)
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
        handler.removeCallbacks(heartbeat)
        invalidateHeartbeat()
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

        @Volatile var rehydrator: ObserverServiceRehydrator? = null
        @Volatile var lifecycleDiag: ((String) -> Unit)? = null

        fun dispatchRehydrate(hook: ObserverServiceRehydrator?) {
            hook?.onForegroundServiceStarted()
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
            } catch (e: RuntimeException) {
                if (!isForegroundStartNotAllowedException(e)) throw e
                handleStartFailure(context, e.javaClass.simpleName)
            }
        }

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

        fun refreshOngoingNotification(context: Context, needsAttention: Boolean) {
            if (!ObserverNotification.notificationsPermitted(context)) {
                dispatchLifecycle("fgs notif suppressed permission=denied")
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(
                ObserverNotification.SERVICE_NOTIFICATION_ID,
                ObserverNotification.ongoing(context, needsAttention = needsAttention, decorate = true),
            )
        }

        fun postAttentionNotification(context: Context) {
            if (!ObserverNotification.notificationsPermitted(context)) {
                dispatchLifecycle("fgs attention notif suppressed permission=denied")
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.notify(
                ObserverNotification.BOOT_NOTIFICATION_ID,
                ObserverNotification.ongoing(
                    context,
                    needsAttention = true,
                    contentIntent = launchPendingIntent(context),
                ),
            )
        }

        fun cancelAttentionNotification(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(ObserverNotification.BOOT_NOTIFICATION_ID)
        }

        private fun handleStartFailure(context: Context, exceptionClassName: String) {
            dispatchLifecycle(startFailureDiagLine(exceptionClassName))
            postAttentionNotification(context)
        }

        private fun isForegroundStartNotAllowedException(exception: RuntimeException): Boolean =
            Build.VERSION.SDK_INT >= 31 &&
                exception.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"

        private fun launchPendingIntent(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            return PendingIntent.getActivity(context, ATTENTION_CONTENT_REQUEST_CODE, launch, pendingIntentFlags())
        }

        private fun pendingIntentFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
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
}
