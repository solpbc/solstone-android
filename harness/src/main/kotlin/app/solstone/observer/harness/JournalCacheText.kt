// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import java.util.Locale

fun journalCacheText(state: HarnessJournalCacheState): String = buildList {
    add("Local cache")
    add("Current limit: ${decimalBytes(state.configuredLimitBytes)}")
    when (state.limitFallback) {
        HarnessJournalCacheLimitFallback.ABSENT -> add("Using the default limit because no saved choice exists")
        HarnessJournalCacheLimitFallback.CORRUPT -> add("Using the default limit because the saved choice could not be read")
        null -> Unit
    }
    when (state.saveError) {
        HarnessJournalCacheSaveError.REJECTED -> add("That cache limit isn't available. Previous limit kept.")
        HarnessJournalCacheSaveError.FAILED -> add("Couldn't save cache limit. Previous limit kept.")
        null -> Unit
    }
    val pass = state.latestPass
    if (pass == null) {
        add("Cache check has not run yet")
    } else {
        val checkedCurrentLimit = pass.configuredLimitBytes == state.configuredLimitBytes
        if (pass.measuredUsageBytes == null) {
            add("Attention: cache usage check failed")
        } else {
            add("Cache usage: ${decimalBytes(pass.measuredUsageBytes)}")
        }
        pass.measuredFreeBytes?.let { add("Free space: ${decimalBytes(it)}") }
        pass.blockedReason?.let { add("Attention: ${blockedText(it)}") }
        if (pass.pressureRemains) add("Storage pressure remains")
        if (pass.durablyMarkedCount > 0) add("Marked for removal: ${pass.durablyMarkedCount}")
        if (pass.reclaimedBytes > 0) add("Reclaimed space: ${decimalBytes(pass.reclaimedBytes)}")
        if (pass.retryableResidualCount > 0) add("Removal retry pending: ${pass.retryableResidualCount}")
        if (pass.refusedPathCount > 0) add("Unsafe cache paths left in place: ${pass.refusedPathCount}")
        if (!checkedCurrentLimit) add("Current limit has not been checked yet")
        if (
            checkedCurrentLimit &&
            pass.blockedReason == null &&
            !pass.pressureRemains &&
            pass.retryableResidualCount == 0 &&
            pass.refusedPathCount == 0
        ) {
            add("Cache check complete")
        }
    }
}.joinToString("\n")

fun decimalBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (bytes >= 1_000_000_000L) {
        return if (bytes % 1_000_000_000L == 0L) "${bytes / 1_000_000_000L} GB" else String.format(Locale.US, "%.1f GB", gb)
    }
    return String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
}

private fun blockedText(reason: HarnessJournalCacheBlockedReason): String = when (reason) {
    HarnessJournalCacheBlockedReason.MEASUREMENT_FAILED -> "cache usage could not be measured"
    HarnessJournalCacheBlockedReason.FREE_SPACE_FAILED -> "free space could not be measured"
    HarnessJournalCacheBlockedReason.ARITHMETIC_OVERFLOW -> "cache size could not be calculated safely"
    HarnessJournalCacheBlockedReason.TRANSITION_FAILED -> "cache records could not be updated"
    HarnessJournalCacheBlockedReason.NO_SAFE_ELIGIBLE_SEGMENT -> "no safely removable uploaded data is available"
    HarnessJournalCacheBlockedReason.REMOVAL_INCOMPLETE -> "cache removal is incomplete"
}
