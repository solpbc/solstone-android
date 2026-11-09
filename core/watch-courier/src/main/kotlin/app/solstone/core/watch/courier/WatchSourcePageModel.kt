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
    val lastContactDisplayText: String = "unknown",
    val relayNoticeShort: String = WatchCourierStrings.RELAY_NOTICE_SHORT,
    val relayNoticeFull: String = WatchCourierStrings.RELAY_NOTICE_FULL,
    val actionLabel: String? = null,
    val oldPlayServicesNotice: String? = null,
    val waitingCount: Int? = null,
    val isSilenceStale: Boolean = true,
    val watchWaitingCount: Int? = null,
    val notice: String? = null,
) {
    companion object {
        const val STALE_CONTACT_THRESHOLD_MS = 60_000L
        fun formatTime(epochMs: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))
        fun formatLastContact(lastContactEpochMs: Long?, nowEpochMs: Long): String {
            if (lastContactEpochMs == null) return "unknown"
            return if (nowEpochMs - lastContactEpochMs in 0L..59_999L) "just now" else formatTime(lastContactEpochMs)
        }
    }
}
