// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryPassTest {
    @Test
    fun aFailingStepDoesNotSkipLaterStepsAndThePassEndsWithoutTheGapStore() {
        val ran = mutableListOf<String>()
        val pass = RecoveryPass()
        pass.run(
            steps = listOf(
                { ran += "one" },
                { error("gap store") },
                { ran += "three" },
            ),
            openNotices = { false },
        )
        assertEquals(listOf("one", "three"), ran)
        assertTrue(pass.passEnded)
        assertFalse(pass.noticesMayShow)
    }

    @Test
    fun noticesOpenOnlyWhenThePassFinishesAndTheOpeningSucceeds() {
        val opened = RecoveryPass()
        opened.run(steps = emptyList(), openNotices = { true })
        assertTrue(opened.passEnded)
        assertTrue(opened.noticesMayShow)

        val failedOpen = RecoveryPass()
        failedOpen.run(steps = emptyList(), openNotices = { error("sweep") })
        assertTrue(failedOpen.passEnded)
        assertFalse(failedOpen.noticesMayShow)

        val glasses = RecoveryPass()
        glasses.run(steps = listOf { error("gap store") })
        assertTrue(glasses.passEnded)
        assertFalse(glasses.noticesMayShow)
    }

    @Test
    fun cancellationStopsThePassAndSetsNeitherFlag() {
        var cancelled = false
        val ran = mutableListOf<String>()
        val pass = RecoveryPass { cancelled }
        pass.run(
            steps = listOf(
                { ran += "one"; cancelled = true },
                { ran += "two" },
            ),
            openNotices = { ran += "open"; true },
        )
        assertEquals(listOf("one"), ran)
        assertFalse(pass.passEnded)
        assertFalse(pass.noticesMayShow)
    }
}
