// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object ObserverNotification {
    const val CHANNEL_ID = "solstone_observer"
    const val SERVICE_NOTIFICATION_ID = 101
    const val BOOT_NOTIFICATION_ID = 102
    const val TEXT_ON = "on"
    const val TEXT_NEEDS_ATTENTION = "needs attention"

    @Volatile var decorator: ObserverNotificationDecorator? = null

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "sol", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    fun ongoing(context: Context, needsAttention: Boolean = false, decorate: Boolean = false): Notification {
        ensureChannel(context)
        val builder = builder(context)
            .setContentTitle("sol")
            .setContentText(if (needsAttention) TEXT_NEEDS_ATTENTION else TEXT_ON)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
        if (decorate) {
            dispatchDecoration { decorator?.decorate(context, builder) }
        }
        return builder
            .build()
    }

    fun dispatchDecoration(hook: (() -> Unit)?) {
        hook?.invoke()
    }

    private fun builder(context: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
}

fun interface ObserverNotificationDecorator {
    fun decorate(context: Context, builder: Notification.Builder)
}
