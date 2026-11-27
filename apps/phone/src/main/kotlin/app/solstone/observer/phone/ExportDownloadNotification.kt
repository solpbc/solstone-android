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
import app.solstone.core.i18n.phoneText
import app.solstone.observer.formfactor.phone.EXTRA_PHONE_ROUTE
import app.solstone.observer.formfactor.phone.PhoneRoute
import app.solstone.observer.formfactor.phone.encodePhoneRoute

internal object ExportDownloadNotification {
    const val CHANNEL_ID = "solstone_export_download"
    const val NOTIFICATION_ID = 105
    /** Separate ID for API 26-33 or UIDT-paused standalone notifications so JobScheduler's
     *  JOB_END_NOTIFICATION_POLICY_REMOVE on [NOTIFICATION_ID] cannot race-cancel a paused notice. */
    const val STANDALONE_NOTIFICATION_ID = 106
    private const val OPEN_JOURNAL_REQUEST_CODE = 210
    private const val PAUSE_EXPORT_REQUEST_CODE = 211
    private const val RESUME_EXPORT_REQUEST_CODE = 212
    private const val CANCEL_EXPORT_REQUEST_CODE = 213
    private const val PROGRESS_MAX = 1000

    val CHANNEL_NAME: String get() = phoneText("android.export.notify.channel")
    val TITLE: String get() = phoneText("android.export.notify.title")

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        val channel = existing ?: NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        channel.name = CHANNEL_NAME
        manager.createNotificationChannel(channel)
    }

    /** Rename the channel only if it was already created, keeping fresh installs clean. */
    fun renameChannelIfPresent(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID) ?: return
        existing.name = CHANNEL_NAME
        manager.createNotificationChannel(existing)
    }

    fun contentText(
        context: Context?,
        filename: String?,
        bytesWritten: Long,
        bytesTotal: Long,
        phase: String?,
    ): String {
        val saved = formatImportByteSize(context, bytesWritten)
        val total = formatImportByteSize(context, bytesTotal)
        return when {
            phase == "verifying" && !filename.isNullOrBlank() ->
                phoneText("android.export.notify.checking", "filename" to filename)
            phase == "paused" && bytesTotal > 0L ->
                phoneText("android.export.notify.paused", "saved" to saved, "total" to total)
            !filename.isNullOrBlank() && bytesTotal > 0L ->
                phoneText("android.export.notify.saving", "filename" to filename, "saved" to saved, "total" to total)
            else -> phoneText("android.export.notify.starting")
        }
    }

    fun build(
        context: Context,
        filename: String?,
        bytesWritten: Long,
        bytesTotal: Long,
        phase: String?,
        exportId: String?,
    ): Notification {
        ensureChannel(context)
        val text = contentText(context, filename, bytesWritten, bytesTotal, phase)
        val indeterminate = phase == "verifying" || bytesTotal <= 0L
        val progress = if (indeterminate) 0 else ImportUploadNotification.scaledProgress(bytesWritten, bytesTotal)

        val openIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(EXTRA_PHONE_ROUTE, encodePhoneRoute(PhoneRoute.YourJournal))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            OPEN_JOURNAL_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val isPaused = phase == "paused"
        val toggleActionIntent = Intent(context, ExportDownloadActionReceiver::class.java).apply {
            action = if (isPaused) {
                ExportDownloadActionReceiver.ACTION_RESUME_EXPORT
            } else {
                ExportDownloadActionReceiver.ACTION_PAUSE_EXPORT
            }
            if (!exportId.isNullOrBlank()) {
                putExtra(ExportDownloadActionReceiver.EXTRA_EXPORT_ID, exportId)
            }
        }
        val togglePending = PendingIntent.getBroadcast(
            context,
            if (isPaused) RESUME_EXPORT_REQUEST_CODE else PAUSE_EXPORT_REQUEST_CODE,
            toggleActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val toggleAction = Notification.Action.Builder(
            0,
            if (isPaused) phoneText("android.export.notify.resume") else phoneText("android.export.notify.pause"),
            togglePending,
        ).build()

        val cancelIntent = Intent(context, ExportDownloadActionReceiver::class.java).apply {
            action = ExportDownloadActionReceiver.ACTION_CANCEL_EXPORT
            if (!exportId.isNullOrBlank()) {
                putExtra(ExportDownloadActionReceiver.EXTRA_EXPORT_ID, exportId)
            }
        }
        val cancelPending = PendingIntent.getBroadcast(
            context,
            CANCEL_EXPORT_REQUEST_CODE,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val cancelAction = Notification.Action.Builder(
            0,
            phoneText("android.phone.cancel"),
            cancelPending,
        ).build()

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder
            .setContentTitle(TITLE)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(!isPaused)
            .setOnlyAlertOnce(true)
            .setProgress(PROGRESS_MAX, progress, indeterminate)
            .setContentIntent(openPending)
            .addAction(toggleAction)
            .addAction(cancelAction)
            .build()
    }

    /** Posts an ordinary (non-FGS) notification on API 26–33 or while a UIDT download is paused. */
    fun postStandalone(context: Context, snapshot: JournalExportStatusSnapshot, filename: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(
                STANDALONE_NOTIFICATION_ID,
                build(
                    context = context,
                    filename = filename.ifBlank { snapshot.destinationDisplay },
                    bytesWritten = snapshot.bytesWritten,
                    bytesTotal = snapshot.totalBytes,
                    phase = snapshot.phase,
                    exportId = snapshot.exportId,
                ),
            )
        }
    }

    fun cancelStandalone(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(STANDALONE_NOTIFICATION_ID) }
    }
}
