// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.platform.persistence.room.ConfirmedDirectoryRemoval
import app.solstone.platform.persistence.room.JournalCacheBlockedReason
import app.solstone.platform.persistence.room.JournalCacheEvictionResult
import app.solstone.platform.persistence.room.JournalCacheLimitFallback
import app.solstone.platform.persistence.room.JournalCacheSnapshot
import app.solstone.platform.persistence.room.ReclaimedCacheSpace
import kotlin.test.Test
import kotlin.test.assertEquals

class JournalCacheDtosTest {
    @Test
    fun mapsEveryOwnerVisibleResultFactWithoutExposingMutableInputs() {
        val choices = mutableListOf(1L, 2L)
        val state = journalCacheState(
            snapshot = JournalCacheSnapshot(2L, JournalCacheLimitFallback.CORRUPT),
            result = JournalCacheEvictionResult(
                measuredUsageBytes = null,
                measuredFreeBytes = 9L,
                configuredLimitBytes = 1L,
                pressureRemains = true,
                durablyMarkedIds = listOf("a", "b"),
                reclaimedSpace = ReclaimedCacheSpace(listOf(ConfirmedDirectoryRemoval("a", 7L))),
                retryableResidualIds = listOf("b"),
                refusedPathIds = listOf("c"),
                blockedReason = JournalCacheBlockedReason.REMOVAL_INCOMPLETE,
            ),
            limitChoicesBytes = choices,
            saveError = HarnessJournalCacheSaveError.FAILED,
        )
        choices.clear()

        assertEquals(2L, state.configuredLimitBytes)
        assertEquals(HarnessJournalCacheLimitFallback.CORRUPT, state.limitFallback)
        assertEquals(listOf(1L, 2L), state.limitChoicesBytes)
        assertEquals(null, state.latestPass!!.measuredUsageBytes)
        assertEquals(9L, state.latestPass!!.measuredFreeBytes)
        assertEquals(1L, state.latestPass!!.configuredLimitBytes)
        assertEquals(2, state.latestPass!!.durablyMarkedCount)
        assertEquals(1, state.latestPass!!.reclaimedDirectoryCount)
        assertEquals(7L, state.latestPass!!.reclaimedBytes)
        assertEquals(1, state.latestPass!!.retryableResidualCount)
        assertEquals(1, state.latestPass!!.refusedPathCount)
        assertEquals(HarnessJournalCacheBlockedReason.REMOVAL_INCOMPLETE, state.latestPass!!.blockedReason)
    }

    @Test
    fun nullResultIsDistinctFromMeasuredZero() {
        val snapshot = JournalCacheSnapshot(4L, null)
        val never = journalCacheState(snapshot, null, emptyList(), null)
        val measured = journalCacheState(
            snapshot,
            JournalCacheEvictionResult(0L, 0L, 4L, false, emptyList(), ReclaimedCacheSpace(emptyList()), emptyList(), emptyList(), null),
            emptyList(),
            null,
        )

        assertEquals(null, never.latestPass)
        assertEquals(0L, measured.latestPass!!.measuredUsageBytes)
    }
}
