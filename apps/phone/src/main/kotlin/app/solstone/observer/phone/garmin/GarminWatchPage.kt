// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.garmin

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.solstone.core.i18n.phoneText
import app.solstone.core.watch.courier.GarminSlv3Protocol
import app.solstone.core.watch.courier.WatchSourcePageModel
import app.solstone.observer.phone.wear.PhoneWatchContactStore

/**
 * The watch source page for a Garmin watch: the same page as the Wear OS watch, with the Garmin
 * hop line in place of Google's relay lines, and Garmin's dead ends each naming one next step.
 * Reads local state only; it never starts the Garmin SDK.
 */
internal object GarminWatchPage {
    /** A held slab averages about 9,050 bytes of 16 kHz Opus at about 3,700 bytes a second. */
    private const val BYTES_PER_SLAB = 9050
    private const val BYTES_PER_SECOND = 3700

    /** The one notice the page shows, worst first, and its action when it has one. */
    internal enum class DeadEnd { CONNECT_MISSING, CONNECT_OLD, NOT_PAIRED, APP_MISSING, WATCH_TOO_OLD, PHONE_TOO_OLD, WATCH_FULL, QUIET }

    internal fun deadEnd(
        status: GarminStatusSnapshot?,
        contact: PhoneWatchContactStore.Contact,
        stale: Boolean,
    ): DeadEnd? = when {
        status?.connectAppStatus == GarminEnvironmentProbe.ConnectAppStatus.NOT_INSTALLED -> DeadEnd.CONNECT_MISSING
        status?.connectAppStatus == GarminEnvironmentProbe.ConnectAppStatus.UPGRADE_NEEDED -> DeadEnd.CONNECT_OLD
        status?.deviceStatus == GarminDeviceStatus.NOT_PAIRED -> DeadEnd.NOT_PAIRED
        status?.watchAppStatus == GarminWatchAppStatus.NotInstalled -> DeadEnd.APP_MISSING
        contact.version != null && contact.version < GarminSlv3Protocol.PROTO_VERSION -> DeadEnd.WATCH_TOO_OLD
        contact.version != null && contact.version > GarminSlv3Protocol.PROTO_VERSION -> DeadEnd.PHONE_TOO_OLD
        contact.storageFull -> DeadEnd.WATCH_FULL
        stale -> DeadEnd.QUIET
        else -> null
    }

    fun build(
        context: Context,
        contact: PhoneWatchContactStore.Contact,
        firstReceiptEpochMs: Long?,
        heldOnPhone: Int?,
        storageLow: Boolean,
        nowMs: Long,
    ): WatchSourcePageModel {
        val at = contact.at.takeIf { contact.hasWatchContact }
        val stale = at == null || nowMs - at !in 0L..WatchSourcePageModel.STALE_CONTACT_THRESHOLD_MS
        val end = deadEnd(GarminCourierRuntime.probeStatusIfActivated(context), contact, stale)
        val app = context.applicationContext
        val (notice, label, action) = when (end) {
            DeadEnd.CONNECT_MISSING -> Triple(phoneText("android.source.garmin.connectmissing"), phoneText("android.source.garmin.getconnect"), { openStore(app) })
            DeadEnd.CONNECT_OLD -> Triple(phoneText("android.source.garmin.connectold"), phoneText("android.source.garmin.updateconnect"), { openStore(app) })
            DeadEnd.NOT_PAIRED -> Triple(phoneText("android.source.garmin.notpaired"), phoneText("android.source.garmin.openconnect"), { openConnect(app) })
            DeadEnd.APP_MISSING -> Triple(phoneText("android.source.garmin.appmissing"), null, null)
            DeadEnd.WATCH_TOO_OLD -> Triple(phoneText("android.shellactivity.updatesolstonewatchcontinue"), null, null)
            DeadEnd.PHONE_TOO_OLD -> Triple(phoneText("android.shellactivity.updatesolstonephonecontinue"), null, null)
            DeadEnd.WATCH_FULL -> Triple(phoneText("android.source.garmin.watchfull"), null, null)
            DeadEnd.QUIET -> Triple(phoneText("android.source.garmin.norecent"), null, null)
            null -> Triple(null, null, null)
        }
        val heldMinutes = contact.sealedWaiting?.let { (it * BYTES_PER_SLAB / BYTES_PER_SECOND + 59) / 60 }
        return WatchSourcePageModel(
            firstReceiptEpochMs = firstReceiptEpochMs,
            lastContactDisplayText = WatchSourcePageModel.formatLastContact(at, nowMs),
            lastContactShortText = at?.let { WatchSourcePageModel.formatLastContactShort(it, nowMs) },
            relayNoticeShort = phoneText("android.source.garmin.hop"),
            relayNoticeFull = null,
            actionLabel = label,
            onAction = action,
            waitingCount = heldOnPhone,
            isSilenceStale = stale,
            watchWaitingText = heldMinutes?.let { phoneText("android.source.garmin.heldminutes", "count" to it) },
            notice = notice,
            storageLow = storageLow,
        )
    }

    private fun openStore(context: Context) {
        val pkg = GarminEnvironmentProbe.GCM_PACKAGE_NAME
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun openConnect(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(GarminEnvironmentProbe.GCM_PACKAGE_NAME)
        if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else openStore(context)
    }
}
