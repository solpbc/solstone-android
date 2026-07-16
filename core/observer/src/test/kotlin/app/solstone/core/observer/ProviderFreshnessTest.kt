// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderFreshnessTest {
    @Test
    fun neverStartedIsNotFresh() {
        assertFalse(isProviderFresh(null, null, nowEpochMs = 1_000L, staleMs = 100L))
    }

    @Test
    fun startGraceIncludesBoundaryAndThenExpires() {
        assertTrue(isProviderFresh(1_000L, null, nowEpochMs = 1_099L, staleMs = 100L))
        assertTrue(isProviderFresh(1_000L, null, nowEpochMs = 1_100L, staleMs = 100L))
        assertFalse(isProviderFresh(1_000L, null, nowEpochMs = 1_101L, staleMs = 100L))
    }

    @Test
    fun emissionSupersedesStartTime() {
        assertTrue(isProviderFresh(1_000L, 2_000L, nowEpochMs = 2_100L, staleMs = 100L))
        assertFalse(isProviderFresh(1_000L, 2_000L, nowEpochMs = 2_101L, staleMs = 100L))
    }
}
