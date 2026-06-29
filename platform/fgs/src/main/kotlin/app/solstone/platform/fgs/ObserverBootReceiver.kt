// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ObserverBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val action = observerBootAction()
        if (!action.postNotification) return
        ObserverNotification.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            ObserverNotification.BOOT_NOTIFICATION_ID,
            ObserverNotification.ongoing(context, needsAttention = true),
        )
    }
}

data class ObserverBootAction(
    val postNotification: Boolean,
    val startForegroundService: Boolean,
    val startCapture: Boolean,
    val scheduleSync: Boolean,
)

fun observerBootAction(): ObserverBootAction =
    ObserverBootAction(
        postNotification = true,
        startForegroundService = false,
        startCapture = false,
        scheduleSync = false,
    )
