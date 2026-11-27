// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles Pause, Resume, and Cancel actions tapped from the export download notification. */
class ExportDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val exportId = intent.getStringExtra(EXTRA_EXPORT_ID)
        val manager = JournalExportTransferManager.get(app)
        when (intent.action) {
            ACTION_PAUSE_EXPORT -> manager.pause(exportId)
            ACTION_RESUME_EXPORT -> manager.resume(exportId)
            ACTION_CANCEL_EXPORT -> manager.cancel(exportId)
        }
    }

    companion object {
        const val ACTION_PAUSE_EXPORT = "app.solstone.observer.phone.ACTION_PAUSE_EXPORT"
        const val ACTION_RESUME_EXPORT = "app.solstone.observer.phone.ACTION_RESUME_EXPORT"
        const val ACTION_CANCEL_EXPORT = "app.solstone.observer.phone.ACTION_CANCEL_EXPORT"
        const val EXTRA_EXPORT_ID = "export_id"
    }
}
