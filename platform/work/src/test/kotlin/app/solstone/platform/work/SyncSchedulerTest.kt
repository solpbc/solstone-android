// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSchedulerTest {
    @Test
    fun periodicPlanUpdatesWorkAndRequiresConnectedNetwork() {
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, SyncScheduler.PERIODIC_WORK_POLICY)
        assertEquals(NetworkType.CONNECTED, SyncScheduler.networkConstraints().requiredNetworkType)
    }

    @Test
    fun streamInputCarriesStreamType() {
        assertEquals("glasses", SyncScheduler.streamInputData("glasses").getString(SyncScheduler.STREAM_TYPE_KEY))
    }
}
