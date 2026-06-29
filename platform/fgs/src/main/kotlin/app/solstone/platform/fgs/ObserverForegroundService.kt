// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import android.app.Service
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
        refreshHeartbeat()
        handler.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ObserverNotification.SERVICE_NOTIFICATION_ID, ObserverNotification.ongoing(this))
        refreshHeartbeat()
        dispatchRehydrate(rehydrator)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        invalidateHeartbeat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshHeartbeat() {
        lastBeatNanos.set(System.nanoTime())
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private val lastBeatNanos = AtomicLong(0L)

        @Volatile var rehydrator: ObserverServiceRehydrator? = null

        fun dispatchRehydrate(hook: ObserverServiceRehydrator?) {
            hook?.onForegroundServiceStarted()
        }

        fun startFromVisibleContext(context: Context) {
            val intent = Intent(context, ObserverForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            invalidateHeartbeat()
            context.stopService(Intent(context, ObserverForegroundService::class.java))
        }

        fun lastHeartbeatNanos(): Long? =
            lastBeatNanos.get().takeIf { it > 0L }

        fun isHeartbeatFresh(nowNanos: Long = System.nanoTime(), staleAfterNanos: Long = 15_000_000_000L): Boolean =
            HeartbeatMonitor.isFresh(nowNanos, lastHeartbeatNanos(), staleAfterNanos)

        private fun invalidateHeartbeat() {
            lastBeatNanos.set(0L)
        }
    }

    fun interface ObserverServiceRehydrator {
        fun onForegroundServiceStarted()
    }
}
