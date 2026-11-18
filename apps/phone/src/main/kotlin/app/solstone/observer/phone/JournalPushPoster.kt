// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.i18n.phoneText

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
        val CHANNEL_NAME get() = phoneText("android.phone.from.journal")
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

    /**
     * Renames the channel for the current language, only if it exists. The channel appears when the
     * first push is posted, so an owner who never turns push on has no "from your journal" category.
     */
    fun renameChannelIfPresent() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return
        channel.name = CHANNEL_NAME
        manager.createNotificationChannel(channel)
    }

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = manager.getNotificationChannel(CHANNEL_ID)
            ?: NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        channel.name = CHANNEL_NAME
        manager.createNotificationChannel(channel)
    }

    override fun post(id: Int, title: String, body: String, open: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        ensureChannel()

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
