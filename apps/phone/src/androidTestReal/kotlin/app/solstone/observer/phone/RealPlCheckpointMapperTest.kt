// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.gate.PlCheckpointKind
import app.solstone.observer.harness.HarnessPlStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealPlCheckpointMapperTest {
    @Test
    fun reachableRequiresHttp200FromThatProductionFact() {
        val reachable = checkpointFromProductionStatus("reachable", "probe-1", HarnessPlStatus.Reachable(200))
        val non200 = checkpointFromProductionStatus("reachable", "probe-2", HarnessPlStatus.Reachable(503))

        assertEquals(PlCheckpointKind.REACHABLE, reachable.variant)
        assertEquals(200, reachable.status)
        assertEquals("probe-1", reachable.httpResult?.probeId)
        assertTrue(reachable.httpResult?.completed == true)
        assertEquals(503, non200.status)
        assertFalse(non200.httpResult?.completed == true)
    }

    @Test
    fun unreachableRequiresNonblankProductionReason() {
        val named = checkpointFromProductionStatus(
            "degraded",
            "probe-1",
            HarnessPlStatus.PairedButUnreachable("network_denied"),
        )
        val unsafe = checkpointFromProductionStatus(
            "degraded",
            "probe-2",
            HarnessPlStatus.PairedButUnreachable(
                "IOException: sensitive endpoint detail",
            ),
        )

        assertEquals(PlCheckpointKind.PAIRED_UNREACHABLE, named.variant)
        assertEquals("network_denied", named.reason)
        assertEquals("production_probe_unreachable", unsafe.reason)
        assertFalse(named.httpResult?.completed == true)
    }

    @Test
    fun notPairedCannotCarryReachability() {
        val checkpoint = checkpointFromProductionStatus("not-paired", "probe-1", HarnessPlStatus.NotPaired)

        assertEquals(PlCheckpointKind.NOT_PAIRED, checkpoint.variant)
        assertNull(checkpoint.status)
        assertNull(checkpoint.httpResult)
    }
}
