// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JournalCacheTextTest {
    /**
     * 🔴 An engine's fail-safe is not a statement about the device.
     *
     * `JournalCacheEvictionService` hard-codes `pressureRemains = true` on any measurement failure,
     * which is right for an eviction engine — assume pressure you cannot rule out. ⛔ Rendering it
     * to the owner is not: driven on the A36, a fresh install whose free-space read failed showed
     * `in use: 0.0 MB` and `there still isn't enough room` on the same screen.
     */
    @Test
    fun aFailedMeasurementNeverClaimsTheDeviceIsShortOfRoom() {
        listOf(
            HarnessJournalCacheBlockedReason.MEASUREMENT_FAILED,
            HarnessJournalCacheBlockedReason.FREE_SPACE_FAILED,
            HarnessJournalCacheBlockedReason.ARITHMETIC_OVERFLOW,
        ).forEach { reason ->
            val text = journalCacheText(state(pass = pass(blocked = reason)))
            assertFalse(text.contains("there still isn't enough room"), reason.name)
            // ✅ And it still says the true thing rather than going quiet.
            assertTrue(text.contains("needs attention:"), reason.name)
        }
        // ✅ Positive control: a real pressure verdict, with the measurement intact, still says so.
        val measured = journalCacheText(
            state(pass = pass(blocked = HarnessJournalCacheBlockedReason.NO_SAFE_ELIGIBLE_SEGMENT)),
        )
        assertTrue(measured.contains("there still isn't enough room"))
    }

    /**
     * 🔴 The register, as a property. ⚠ Every retired string on this screen was pinned exactly by
     * the tests above, which is how an operator voice survives a green gate: the assertion encodes
     * the defect. Asserting the SHAPE is what makes the next `Cache usage:` line fail.
     */
    @Test
    fun everyLineIsOwnerVoiceAndNamesNoInternalMechanism() {
        val texts = HarnessJournalCacheBlockedReason.entries.map {
            journalCacheText(state(pass = pass(blocked = it, residuals = 1, refusedPaths = 1)))
        } + listOf(
            journalCacheText(state()),
            journalCacheText(state(pass = pass())),
            journalCacheText(state(saveError = HarnessJournalCacheSaveError.FAILED)),
            journalCacheText(state(saveError = HarnessJournalCacheSaveError.REJECTED)),
            journalCacheText(state(fallback = HarnessJournalCacheLimitFallback.ABSENT)),
            journalCacheText(state(fallback = HarnessJournalCacheLimitFallback.CORRUPT)),
        )
        val internalWords = listOf("cache", "segment", "transition", "eviction", "spool", "residual")
        texts.forEach { text ->
            text.lines().forEach { line ->
                assertTrue(line.isEmpty() || line.first().isLowerCase(), "sentence case: $line")
                internalWords.forEach { word ->
                    assertFalse(line.contains(word, ignoreCase = true), "internal word '$word': $line")
                }
                // ⛔ `Attention:` appeared nowhere else in the app; `needs attention` is the locked
                // state word and the only one an owner has been taught.
                assertFalse(line.startsWith("Attention"), line)
            }
        }
        // ✅ Positive controls: each matcher finds what it looks for when it is present.
        assertFalse("Cache usage: 4 GB".first().isLowerCase())
        assertTrue("Unsafe cache paths left in place: 1".contains("cache", ignoreCase = true))
        assertTrue("Attention: cache removal is incomplete".startsWith("Attention"))
    }

    @Test
    fun decimalLimitAndNeverMeasuredStateAreExplicit() {
        val text = journalCacheText(state())
        assertTrue(text.contains("space on this device"))
        assertTrue(text.contains("your limit: 4 GB"))
        assertTrue(text.contains("the solstone app hasn't checked this yet."))
        assertFalse(text.contains("in use: 0"))
        assertEquals("4 GB", decimalBytes(4_000_000_000L))
    }

    @Test
    fun measurementFailureIsPlainlyDifferentFromBenignNeverMeasuredState() {
        val neverMeasured = journalCacheText(state())
        val failed = journalCacheText(state(pass = pass(blocked = HarnessJournalCacheBlockedReason.MEASUREMENT_FAILED, usage = null)))
        assertNotEquals(neverMeasured, failed)
        assertTrue(failed.contains("needs attention: couldn't measure how much space is in use."))
        assertFalse(failed.contains("hasn't checked this yet"))
    }

    @Test
    fun everyBlockedReasonAndResidualRenderAsNonSuccess() {
        HarnessJournalCacheBlockedReason.entries.forEach { reason ->
            val text = journalCacheText(state(pass = pass(blocked = reason)))
            assertTrue(text.contains("needs attention:"), reason.name)
            assertFalse(text.contains("nothing to do"), reason.name)
        }
        val residual = journalCacheText(state(pass = pass(residuals = 2)))
        assertTrue(residual.contains("still to remove: 2 — the solstone app will try again."))
        assertFalse(residual.contains("freed up"))
        assertFalse(residual.contains("nothing to do"))
    }

    @Test
    fun saveFailureKeepsPriorChoiceVisible() {
        val text = journalCacheText(state(saveError = HarnessJournalCacheSaveError.FAILED))
        assertTrue(text.contains("your limit: 4 GB"))
        assertTrue(text.contains("your previous limit is still in place."))
    }

    @Test
    fun stalePassKeepsTrueUsageButDoesNotClaimCurrentLimitWasChecked() {
        val text = journalCacheText(
            state(
                currentLimit = 1_000_000_000L,
                pass = pass(limit = 32_000_000_000L, usage = 4_000_000_000L),
            ),
        )

        assertTrue(text.contains("your limit: 1 GB"))
        assertTrue(text.contains("in use: 4 GB"))
        assertTrue(text.contains("your new limit hasn't been checked yet."))
        assertFalse(text.contains("nothing to do"))
    }

    @Test
    fun matchingCleanPassStillRendersCompletion() {
        val text = journalCacheText(state(pass = pass()))

        assertTrue(text.contains("checked, and there's nothing to do."))
        assertFalse(text.contains("hasn't been checked yet"))
    }

    @Test
    fun refusedPathPreventsCompletionClaim() {
        val text = journalCacheText(state(pass = pass(refusedPaths = 1)))

        assertTrue(text.contains("kept in place because removing them wouldn't be safe: 1"))
        assertFalse(text.contains("nothing to do"))
    }

    private fun state(
        pass: HarnessJournalCachePass? = null,
        saveError: HarnessJournalCacheSaveError? = null,
        currentLimit: Long = 4_000_000_000L,
        fallback: HarnessJournalCacheLimitFallback? = null,
    ) = HarnessJournalCacheState(
        configuredLimitBytes = currentLimit,
        limitFallback = fallback,
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
