// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.solstone.core.push.PushNotifier

class JournalPushPoster(
    private val context: Context,
) : PushNotifier {

    companion object {
        const val CHANNEL_ID = "journal_push"
        const val CHANNEL_NAME = "from your journal"
        private const val PUSH_PENDING_INTENT_REQUEST_CODE = 301
    }

    override fun post(id: Int, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            manager.createNotificationChannel(channel)
        }

        val notificationsEnabled = if (Build.VERSION.SDK_INT >= 24) manager.areNotificationsEnabled() else true
        val channelEnabled = if (Build.VERSION.SDK_INT >= 26) {
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }

        if (!notificationsEnabled || !channelEnabled) {
            PhoneDiagLog.appendRaw("kind=push reason=notifications_off")
        }

        val launchIntent = Intent(context, PhoneShellActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            PUSH_PENDING_INTENT_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(id, notification)
    }
}
