// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.watch.courier

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class WatchSourcePageModel(
    val startOnWatchInstruction: String = "start on your watch",
    val firstReceiptEpochMs: Long? = null,
    val journalConfirmedEpochMs: Long? = null,
    val lastContactDisplayText: String = "not yet",
    /** The tile's short form of the same time, or null when the watch has never checked in. */
    val lastContactShortText: String? = null,
    val relayNoticeShort: String = WatchCourierStrings.RELAY_NOTICE_SHORT,
    val relayNoticeFull: String = WatchCourierStrings.RELAY_NOTICE_FULL,
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
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
        /** The tile's form: past an hour, the time today, otherwise the day, so it fits a tile. */
        fun formatLastContactShort(lastContactEpochMs: Long?, nowEpochMs: Long): String {
            if (lastContactEpochMs == null || nowEpochMs - lastContactEpochMs in 0L until 60 * 60_000L) {
                return formatLastContact(lastContactEpochMs, nowEpochMs)
            }
            val zone = ZoneId.systemDefault()
            val at = Instant.ofEpochMilli(lastContactEpochMs).atZone(zone)
            val sameDay = at.toLocalDate() == Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
            return if (sameDay) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
            else DateTimeFormatter.ofPattern("MMM d").format(at)
        }
        fun formatLastContact(lastContactEpochMs: Long?, nowEpochMs: Long): String {
            if (lastContactEpochMs == null) return "not yet"
            val ago = nowEpochMs - lastContactEpochMs
            return when {
                ago in 0L until 2 * 60_000L -> "just now"
                ago in 0L until 60 * 60_000L -> "${ago / 60_000L} min ago"
                else -> formatTime(lastContactEpochMs)
            }
        }
    }
}
