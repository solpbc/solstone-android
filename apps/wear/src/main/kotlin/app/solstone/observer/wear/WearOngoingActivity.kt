// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import app.solstone.core.watch.courier.WatchCourierStrings

/** One local ongoing surface. Its tap only opens the face; it never starts audio. */
internal object WearOngoingActivity {
    fun build(context: Context, channelId: String, notificationId: Int, status: String): Notification {
        val openFace = PendingIntent.getActivity(
            context,
            0,
            Intent(context, WearMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(WatchCourierStrings.SOLSTONE)
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_sun_mono)
            .setContentIntent(openFace)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setLocalOnly(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
        OngoingActivity.Builder(context, notificationId, notification)
            .setStaticIcon(R.drawable.ic_sun_mono)
            .setTouchIntent(openFace)
            .setStatus(Status.Builder().addTemplate(status).build())
            .build()
            .apply(context)
        return notification.build()
    }
}
