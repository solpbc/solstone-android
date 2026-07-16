// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.platform.persistence.room.JournalCacheBlockedReason
import app.solstone.platform.persistence.room.JournalCacheEvictionResult
import app.solstone.platform.persistence.room.JournalCacheLimitFallback
import app.solstone.platform.persistence.room.JournalCacheSnapshot

enum class HarnessJournalCacheLimitFallback { ABSENT, CORRUPT }

enum class HarnessJournalCacheBlockedReason {
    MEASUREMENT_FAILED,
    FREE_SPACE_FAILED,
    ARITHMETIC_OVERFLOW,
    TRANSITION_FAILED,
    NO_SAFE_ELIGIBLE_SEGMENT,
    REMOVAL_INCOMPLETE,
}

enum class HarnessJournalCacheSaveError { REJECTED, FAILED }

data class HarnessJournalCachePass(
    val measuredUsageBytes: Long?,
    val measuredFreeBytes: Long?,
    val configuredLimitBytes: Long,
    val pressureRemains: Boolean,
    val durablyMarkedCount: Int,
    val reclaimedDirectoryCount: Int,
    val reclaimedBytes: Long,
    val retryableResidualCount: Int,
    val refusedPathCount: Int,
    val blockedReason: HarnessJournalCacheBlockedReason?,
)

data class HarnessJournalCacheState(
    val configuredLimitBytes: Long,
    val limitFallback: HarnessJournalCacheLimitFallback?,
    val limitChoicesBytes: List<Long>,
    val latestPass: HarnessJournalCachePass?,
    val saveError: HarnessJournalCacheSaveError?,
)

fun journalCacheState(
    snapshot: JournalCacheSnapshot,
    result: JournalCacheEvictionResult?,
    limitChoicesBytes: List<Long>,
    saveError: HarnessJournalCacheSaveError?,
): HarnessJournalCacheState = HarnessJournalCacheState(
    configuredLimitBytes = snapshot.configuredLimitBytes,
    limitFallback = snapshot.limitFallback?.toHarness(),
    limitChoicesBytes = limitChoicesBytes.toList(),
    latestPass = result?.let {
        HarnessJournalCachePass(
            measuredUsageBytes = it.measuredUsageBytes,
            measuredFreeBytes = it.measuredFreeBytes,
            configuredLimitBytes = it.configuredLimitBytes,
            pressureRemains = it.pressureRemains,
            durablyMarkedCount = it.durablyMarkedIds.size,
            reclaimedDirectoryCount = it.reclaimedSpace.removals.size,
            reclaimedBytes = it.reclaimedSpace.totalBytes,
            retryableResidualCount = it.retryableResidualIds.size,
            refusedPathCount = it.refusedPathIds.size,
            blockedReason = it.blockedReason?.toHarness(),
        )
    },
    saveError = saveError,
)

private fun JournalCacheLimitFallback.toHarness(): HarnessJournalCacheLimitFallback =
    HarnessJournalCacheLimitFallback.valueOf(name)

private fun JournalCacheBlockedReason.toHarness(): HarnessJournalCacheBlockedReason =
    HarnessJournalCacheBlockedReason.valueOf(name)
