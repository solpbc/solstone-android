// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/** Refresh host-cached words only. A language change cannot start a session. */
class WearLocaleChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return
        val appContext = context.applicationContext
        TileService.getUpdater(appContext).requestUpdate(WatchTileService::class.java)
        ComplicationDataSourceUpdateRequester.create(appContext,
            ComponentName(appContext, WatchComplicationService::class.java)).requestUpdateAll()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.getNotificationChannel("solstone_wear_capture")?.let { channel ->
            channel.name = AndroidWatchCopy(context.resources).text(WatchMessage.APP_NAME)
            manager.createNotificationChannel(channel)
        }
    }
}
