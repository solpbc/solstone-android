// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.NetworkType
import kotlin.test.Test
import java.util.UUID
import kotlin.test.assertEquals

class SyncSchedulerTest {
    @Test
    fun periodicPlanUpdatesWorkAndRequiresConnectedNetwork() {
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, SyncScheduler.PERIODIC_WORK_POLICY)
        assertEquals(NetworkType.CONNECTED, SyncScheduler.networkConstraints().requiredNetworkType)
    }

    @Test
    fun aSyncRequestReplacesAWaitingSyncButNeverARunningOne() {
        // A sync left in retry backoff after an unreachable journal is replaced, so the next
        // request runs as soon as there is a network instead of waiting out the backoff.
        assertEquals(ExistingWorkPolicy.REPLACE, SyncScheduler.nowWorkPolicy(listOf(info(WorkInfo.State.ENQUEUED))))
        assertEquals(ExistingWorkPolicy.REPLACE, SyncScheduler.nowWorkPolicy(listOf(info(WorkInfo.State.SUCCEEDED))))
        assertEquals(ExistingWorkPolicy.REPLACE, SyncScheduler.nowWorkPolicy(emptyList()))
        assertEquals(ExistingWorkPolicy.KEEP, SyncScheduler.nowWorkPolicy(listOf(info(WorkInfo.State.RUNNING))))
        assertEquals(ExistingWorkPolicy.KEEP, SyncScheduler.nowWorkPolicy(null))
    }

    private fun info(state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state, emptySet())

    @Test
    fun streamInputCarriesStreamType() {
        assertEquals("glasses", SyncScheduler.streamInputData("glasses").getString(SyncScheduler.STREAM_TYPE_KEY))
    }
}
