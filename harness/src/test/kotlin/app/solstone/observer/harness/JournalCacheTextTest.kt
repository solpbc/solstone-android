// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JournalCacheTextTest {
    @Test
    fun decimalLimitAndNeverMeasuredStateAreExplicit() {
        val text = journalCacheText(state())
        assertTrue(text.contains("Local cache"))
        assertTrue(text.contains("Current limit: 4 GB"))
        assertTrue(text.contains("Cache check has not run yet"))
        assertFalse(text.contains("Cache usage: 0"))
        assertEquals("4 GB", decimalBytes(4_000_000_000L))
    }

    @Test
    fun measurementFailureIsPlainlyDifferentFromBenignNeverMeasuredState() {
        val neverMeasured = journalCacheText(state())
        val failed = journalCacheText(state(pass = pass(blocked = HarnessJournalCacheBlockedReason.MEASUREMENT_FAILED, usage = null)))
        assertNotEquals(neverMeasured, failed)
        assertTrue(failed.contains("Attention: cache usage check failed"))
        assertFalse(failed.contains("has not run yet"))
    }

    @Test
    fun everyBlockedReasonAndResidualRenderAsNonSuccess() {
        HarnessJournalCacheBlockedReason.entries.forEach { reason ->
            val text = journalCacheText(state(pass = pass(blocked = reason)))
            assertTrue(text.contains("Attention:"), reason.name)
            assertFalse(text.contains("Cache check complete"), reason.name)
        }
        val residual = journalCacheText(state(pass = pass(residuals = 2)))
        assertTrue(residual.contains("Removal retry pending: 2"))
        assertFalse(residual.contains("Reclaimed space"))
        assertFalse(residual.contains("Cache check complete"))
    }

    @Test
    fun saveFailureKeepsPriorChoiceVisible() {
        val text = journalCacheText(state(saveError = HarnessJournalCacheSaveError.FAILED))
        assertTrue(text.contains("Current limit: 4 GB"))
        assertTrue(text.contains("Previous limit kept"))
    }

    private fun state(
        pass: HarnessJournalCachePass? = null,
        saveError: HarnessJournalCacheSaveError? = null,
    ) = HarnessJournalCacheState(
        configuredLimitBytes = 4_000_000_000L,
        limitFallback = null,
        limitChoicesBytes = emptyList(),
        latestPass = pass,
        saveError = saveError,
    )

    private fun pass(
        blocked: HarnessJournalCacheBlockedReason? = null,
        usage: Long? = 0L,
        residuals: Int = 0,
    ) = HarnessJournalCachePass(
        measuredUsageBytes = usage,
        measuredFreeBytes = 8_000_000_000L,
        configuredLimitBytes = 4_000_000_000L,
        pressureRemains = blocked != null,
        durablyMarkedCount = 0,
        reclaimedDirectoryCount = 0,
        reclaimedBytes = 0L,
        retryableResidualCount = residuals,
        refusedPathCount = 0,
        blockedReason = blocked,
    )
}
