// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncDrainGateTest {
    @Test
    fun tryAcquireExcludesConcurrentDrainAndReleaseReopens() {
        assertTrue(SyncDrainGate.tryAcquire())
        try {
            assertFalse(SyncDrainGate.tryAcquire())
        } finally {
            SyncDrainGate.release()
        }

        assertTrue(SyncDrainGate.tryAcquire())
        SyncDrainGate.release()
    }

    @Test
    fun aClosedGateRefusesEveryDrainAndPairAttemptUntilItOpens() {
        val previous = SyncDrainGate.closed
        try {
            SyncDrainGate.closed = { true }
            assertFalse(SyncDrainGate.tryAcquire())
            assertFalse(SyncDrainGate.inUse())

            SyncDrainGate.closed = { false }
            assertTrue(SyncDrainGate.tryAcquire())
            assertTrue(SyncDrainGate.inUse())
            SyncDrainGate.release()
            assertFalse(SyncDrainGate.inUse())
        } finally {
            SyncDrainGate.closed = previous
        }
    }
}
