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
}
