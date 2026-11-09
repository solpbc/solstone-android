// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

data class WatchSourcePageModel(
    val startOnWatchInstruction: String = "start on your watch",
    val firstReceiptEpochMs: Long?,
    val journalConfirmedEpochMs: Long?,
    val lastContactDisplayText: String,
    val relayNoticeShort: String = WatchCourierStrings.RELAY_NOTICE_SHORT,
    val relayNoticeFull: String = WatchCourierStrings.RELAY_NOTICE_FULL,
    val actionLabel: String? = null,
    val oldPlayServicesNotice: String? = null,
    val waitingCount: Int,
    val isSilenceStale: Boolean = false,
) {
    companion object {
        const val STALE_CONTACT_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours

        fun formatLastContact(lastContactEpochMs: Long?, nowEpochMs: Long): String {
            if (lastContactEpochMs == null || lastContactEpochMs <= 0L) return "unknown"
            val age = nowEpochMs - lastContactEpochMs
            return if (age >= STALE_CONTACT_THRESHOLD_MS || age < 0) {
                "unknown"
            } else {
                lastContactEpochMs.toString()
            }
        }
    }
}
