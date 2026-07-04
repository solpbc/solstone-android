// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ObserverBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val persistedDesiredOn = context
            .getSharedPreferences(ObserverRuntimePrefs.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(ObserverRuntimePrefs.KEY_DESIRED_ON, false)
        val action = observerBootAction(persistedDesiredOn)
        if (!action.postNotification) return
        if (!ObserverNotification.notificationsPermitted(context)) {
            ObserverForegroundService.dispatchLifecycle("fgs boot notif suppressed permission=denied")
            return
        }
        val pending = bootContentIntent(context)
        if (pending == null) {
            ObserverForegroundService.dispatchLifecycle("fgs boot no-launch-intent")
        }
        ObserverNotification.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            ObserverNotification.BOOT_NOTIFICATION_ID,
            ObserverNotification.ongoing(context, needsAttention = true, contentIntent = pending),
        )
    }

    private fun bootContentIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, BOOT_CONTENT_REQUEST_CODE, launch, flags)
    }

    private companion object {
        const val BOOT_CONTENT_REQUEST_CODE = 102
    }
}

data class ObserverBootAction(
    val postNotification: Boolean,
    val startForegroundService: Boolean,
    val startCapture: Boolean,
    val scheduleSync: Boolean,
)

fun observerBootAction(persistedDesiredOn: Boolean): ObserverBootAction =
    ObserverBootAction(
        postNotification = persistedDesiredOn,
        startForegroundService = false,
        startCapture = false,
        scheduleSync = false,
    )
