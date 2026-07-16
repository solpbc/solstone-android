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

    @Test
    fun stalePassKeepsTrueUsageButDoesNotClaimCurrentLimitWasChecked() {
        val text = journalCacheText(
            state(
                currentLimit = 1_000_000_000L,
                pass = pass(limit = 32_000_000_000L, usage = 4_000_000_000L),
            ),
        )

        assertTrue(text.contains("Current limit: 1 GB"))
        assertTrue(text.contains("Cache usage: 4 GB"))
        assertTrue(text.contains("Current limit has not been checked yet"))
        assertFalse(text.contains("Cache check complete"))
    }

    @Test
    fun matchingCleanPassStillRendersCompletion() {
        val text = journalCacheText(state(pass = pass()))

        assertTrue(text.contains("Cache check complete"))
        assertFalse(text.contains("has not been checked yet"))
    }

    @Test
    fun refusedPathPreventsCompletionClaim() {
        val text = journalCacheText(state(pass = pass(refusedPaths = 1)))

        assertTrue(text.contains("Unsafe cache paths left in place: 1"))
        assertFalse(text.contains("Cache check complete"))
    }

    private fun state(
        pass: HarnessJournalCachePass? = null,
        saveError: HarnessJournalCacheSaveError? = null,
        currentLimit: Long = 4_000_000_000L,
    ) = HarnessJournalCacheState(
        configuredLimitBytes = currentLimit,
        limitFallback = null,
        limitChoicesBytes = emptyList(),
        latestPass = pass,
        saveError = saveError,
    )

    private fun pass(
        blocked: HarnessJournalCacheBlockedReason? = null,
        usage: Long? = 0L,
        residuals: Int = 0,
        refusedPaths: Int = 0,
        limit: Long = 4_000_000_000L,
    ) = HarnessJournalCachePass(
        measuredUsageBytes = usage,
        measuredFreeBytes = 8_000_000_000L,
        configuredLimitBytes = limit,
        pressureRemains = blocked != null,
        durablyMarkedCount = 0,
        reclaimedBytes = 0L,
        retryableResidualCount = residuals,
        refusedPathCount = refusedPaths,
        blockedReason = blocked,
    )
}
