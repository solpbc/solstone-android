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
import android.text.format.Formatter
import app.solstone.core.i18n.phoneText
import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_CHECKING
import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_PAUSED
import app.solstone.observer.formfactor.phone.EXTRA_PHONE_ROUTE
import app.solstone.observer.formfactor.phone.PhoneRoute
import app.solstone.observer.formfactor.phone.encodePhoneRoute
import java.util.Locale

internal object ImportUploadNotification {
    const val CHANNEL_ID = "solstone_import_upload"
    const val NOTIFICATION_ID = 104
    const val STANDALONE_NOTIFICATION_ID = 107
    private const val OPEN_IMPORT_REQUEST_CODE = 204
    private const val CANCEL_IMPORT_REQUEST_CODE = 205
    private const val PROGRESS_MAX = 1000

    val CHANNEL_NAME: String get() = phoneText("android.import.notify.channel")
    val TITLE: String get() = phoneText("android.import.notify.title")

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
        bytesSent: Long,
        bytesTotal: Long,
        phase: String?,
    ): String {
        val sent = formatImportByteSize(context, bytesSent)
        val total = formatImportByteSize(context, bytesTotal)
        return when {
            phase == RESUMABLE_UPLOAD_PHASE_CHECKING && !filename.isNullOrBlank() ->
                phoneText("android.import.notify.checking", "filename" to filename)
            phase == RESUMABLE_UPLOAD_PHASE_PAUSED && bytesTotal > 0L ->
                phoneText("android.import.notify.paused", "sent" to sent, "total" to total)
            !filename.isNullOrBlank() && bytesTotal > 0L ->
                phoneText("android.import.notify.sending", "filename" to filename, "sent" to sent, "total" to total)
            else -> phoneText("android.import.sending.journal")
        }
    }

    fun scaledProgress(bytesSent: Long, bytesTotal: Long): Int {
        if (bytesTotal <= 0L) return 0
        val clamped = bytesSent.coerceIn(0L, bytesTotal)
        return ((clamped * PROGRESS_MAX.toDouble()) / bytesTotal.toDouble()).toInt().coerceIn(0, PROGRESS_MAX)
    }

    fun build(
        context: Context,
        filename: String?,
        bytesSent: Long,
        bytesTotal: Long,
        phase: String?,
        itemId: String?,
    ): Notification {
        ensureChannel(context)
        val text = contentText(context, filename, bytesSent, bytesTotal, phase)
        val indeterminate = phase == RESUMABLE_UPLOAD_PHASE_CHECKING || bytesTotal <= 0L
        val progress = if (indeterminate) 0 else scaledProgress(bytesSent, bytesTotal)

        val openIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(EXTRA_PHONE_ROUTE, encodePhoneRoute(PhoneRoute.Import))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            OPEN_IMPORT_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = Intent(context, ImportUploadCancelReceiver::class.java).apply {
            action = ImportUploadCancelReceiver.ACTION_CANCEL_UPLOAD
            if (!itemId.isNullOrBlank()) {
                putExtra(ImportUploadCancelReceiver.EXTRA_ITEM_ID, itemId)
            }
        }
        val cancelPending = PendingIntent.getBroadcast(
            context,
            CANCEL_IMPORT_REQUEST_CODE,
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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(phase != RESUMABLE_UPLOAD_PHASE_PAUSED)
            .setOnlyAlertOnce(true)
            .setProgress(PROGRESS_MAX, progress, indeterminate)
            .setContentIntent(openPending)
            .addAction(cancelAction)
            .build()
    }

    fun postStandalone(
        context: Context,
        filename: String?,
        bytesSent: Long,
        bytesTotal: Long,
        phase: String?,
        itemId: String?,
    ) {
        val manager = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            val notification = build(
                context = context.applicationContext,
                filename = filename,
                bytesSent = bytesSent,
                bytesTotal = bytesTotal,
                phase = phase,
                itemId = itemId,
            )
            manager.notify(STANDALONE_NOTIFICATION_ID, notification)
        }
    }

    fun cancelStandalone(context: Context) {
        val manager = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(STANDALONE_NOTIFICATION_ID) }
    }
}

internal fun formatImportByteSize(context: Context?, bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (context != null) {
        val formatted = runCatching { Formatter.formatShortFileSize(context, safe) }.getOrNull()
        if (!formatted.isNullOrBlank()) return formatted
    }
    return formatBytesFallback(safe)
}

internal fun formatBytesFallback(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    val kb = 1_000.0
    val mb = 1_000_000.0
    val gb = 1_000_000_000.0
    return when {
        safe >= gb -> String.format(Locale.US, "%.1f GB", safe / gb)
        safe >= mb -> String.format(Locale.US, "%.1f MB", safe / mb)
        safe >= kb -> String.format(Locale.US, "%.0f KB", safe / kb)
        else -> "$safe B"
    }
}
