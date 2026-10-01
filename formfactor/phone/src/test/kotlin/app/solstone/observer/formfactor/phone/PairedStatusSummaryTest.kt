// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairedStatusSummaryTest {
    @Test
    fun notPairedReturnsNull() {
        val summary = pairedStatusSummary(
            PhoneStatusModel(
                paired = false,
                online = true,
                pendingCount = 0,
                hasContentPending = false,
                awaitingMarkConfirmation = false,
            ),
        )
        assertNull(summary)
    }

    @Test
    fun awaitingMarkConfirmationZeroPending() {
        val summary = pairedStatusSummary(
            PhoneStatusModel(
                paired = true,
                online = true,
                pendingCount = 0,
                hasContentPending = false,
                awaitingMarkConfirmation = true,
            ),
        )
        assertNotNull(summary)
        assertEquals(PairedStatusLead.MARK_LINE, summary.lead)
        assertFalse(summary.markLineFollowsCount)
        assertTrue(summary.subLine)
        assertTrue(summary.action)
    }

    @Test
    fun awaitingMarkConfirmationWithPending() {
        val summary = pairedStatusSummary(
            PhoneStatusModel(
                paired = true,
                online = true,
                pendingCount = 3,
                hasContentPending = true,
                awaitingMarkConfirmation = true,
            ),
        )
        assertNotNull(summary)
        assertEquals(PairedStatusLead.COUNT, summary.lead)
        assertTrue(summary.markLineFollowsCount)
        assertTrue(summary.subLine)
        assertTrue(summary.action)
    }

    @Test
    fun connectedWhenCaughtUp() {
        val summary = pairedStatusSummary(
            PhoneStatusModel(
                paired = true,
                online = true,
                pendingCount = 0,
                hasContentPending = false,
                awaitingMarkConfirmation = false,
            ),
        )
        assertNotNull(summary)
        assertEquals(PairedStatusLead.CAUGHT_UP, summary.lead)
        assertFalse(summary.markLineFollowsCount)
        assertTrue(summary.subLine)
        assertFalse(summary.action)
    }

    @Test
    fun syncingWhenOnlineWithPending() {
        val summary = pairedStatusSummary(
            PhoneStatusModel(
                paired = true,
                online = true,
                pendingCount = 2,
                hasContentPending = true,
                awaitingMarkConfirmation = false,
            ),
        )
        assertNotNull(summary)
        assertEquals(PairedStatusLead.COUNT, summary.lead)
        assertFalse(summary.markLineFollowsCount)
        assertTrue(summary.subLine)
        assertFalse(summary.action)
    }

    @Test
    fun offlineWhenOfflineWithPending() {
        val summary = pairedStatusSummary(
            PhoneStatusModel(
                paired = true,
                online = false,
                pendingCount = 4,
                hasContentPending = true,
                awaitingMarkConfirmation = false,
            ),
        )
        assertNotNull(summary)
        assertEquals(PairedStatusLead.COUNT, summary.lead)
        assertFalse(summary.markLineFollowsCount)
        assertTrue(summary.subLine)
        assertFalse(summary.action)
    }
}
