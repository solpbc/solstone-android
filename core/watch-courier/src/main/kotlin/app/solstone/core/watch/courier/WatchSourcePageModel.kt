// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.watch.courier

import app.solstone.core.i18n.phoneText
import app.solstone.core.i18n.phoneLocale

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class WatchSourcePageModel(
    val startOnWatchInstruction: String = phoneText("android.phone.watch.start"),
    val firstReceiptEpochMs: Long? = null,
    val journalConfirmedEpochMs: Long? = null,
    val lastContactDisplayText: String = phoneText("android.wear.not.yet"),
    /** The tile's short form of the same time, or null when the watch has never checked in. */
    val lastContactShortText: String? = null,
    val relayNoticeShort: String = phoneText("android.wear.relay.notice.short"),
    val relayNoticeFull: String = phoneText("android.wear.relay.notice.full"),
    val actionLabel: String? = null,
    val oldPlayServicesNotice: String? = null,
    val waitingCount: Int? = null,
    val isSilenceStale: Boolean = true,
    val watchWaitingCount: Int? = null,
    val notice: String? = null,
    val storageLow: Boolean = false,
) {
    companion object {
        /**
         * The watch checks in when its app opens and about every 15 minutes while it is on. Past
         * this, silence is worth saying; before it, the time it was last heard is the whole story.
         */
        const val STALE_CONTACT_THRESHOLD_MS = 30 * 60_000L
        fun formatTime(epochMs: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(phoneLocale()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
        /** The tile's form: past an hour, the time today, otherwise the day, so it fits a tile. */
        fun formatLastContactShort(lastContactEpochMs: Long?, nowEpochMs: Long): String {
            if (lastContactEpochMs == null || nowEpochMs - lastContactEpochMs in 0L until 60 * 60_000L) {
                return formatLastContact(lastContactEpochMs, nowEpochMs)
            }
            val zone = ZoneId.systemDefault()
            val at = Instant.ofEpochMilli(lastContactEpochMs).atZone(zone)
            val sameDay = at.toLocalDate() == Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
            return if (sameDay) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(phoneLocale()).format(at)
            else DateTimeFormatter.ofPattern("MMM d", phoneLocale()).format(at)
        }
        fun formatLastContact(lastContactEpochMs: Long?, nowEpochMs: Long): String {
            if (lastContactEpochMs == null) return phoneText("android.wear.not.yet")
            val ago = nowEpochMs - lastContactEpochMs
            return when {
                ago in 0L until 2 * 60_000L -> phoneText("android.about.freshnessnow")
                ago in 0L until 60 * 60_000L -> phoneText("android.intake.minago", "count" to (ago / 60_000L))
                else -> formatTime(lastContactEpochMs)
            }
        }
    }
}
