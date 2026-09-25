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
import app.solstone.core.push.validJournalOpenPath

class JournalPushPoster(
    private val context: Context,
) : PushNotifier {

    companion object {
        const val CHANNEL_ID = "journal_push"
        const val CHANNEL_NAME = "from your journal"
        internal const val EXTRA_JOURNAL_OPEN = "app.solstone.phone.journal_open"

        internal fun buildLaunchIntent(context: Context, open: String?): Intent {
            return Intent(context, PhoneShellActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                val path = validJournalOpenPath(open)
                if (path != null) {
                    putExtra(EXTRA_JOURNAL_OPEN, path)
                }
            }
        }
    }

    override fun post(id: Int, title: String, body: String, open: String?) {
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

        val launchIntent = buildLaunchIntent(context, open)
        val pendingIntent = PendingIntent.getActivity(
            context,
            id,
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
