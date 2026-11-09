// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear

import app.solstone.core.watch.courier.StagedWatchBundle
import app.solstone.core.watch.courier.TransferPlanReason
import app.solstone.core.watch.courier.WatchTransferPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTransferWorkerTest {
    @Test
    fun plannerUsesFreshUnionAndNeverCachedNodeOnly() {
        val bundle1 = StagedWatchBundle("b1", "sha1", 1L)
        val bundle2 = StagedWatchBundle("b2", "sha2", 1L)
        val bundles = listOf(bundle1, bundle2)

        // Cached node is dead / unreachable, active peer is peerA
        val reachableCapNodes = setOf("peerA")
        val connectedNodes = setOf("peerB")
        val deadCachedNode = "deadNode"

        val plan = WatchTransferPlanner.plan(
            stagedBundles = bundles,
            reachableCapabilityNodes = reachableCapNodes,
            connectedNodes = connectedNodes,
            cachedNodeId = deadCachedNode,
            batchCap = 1,
        )

        assertEquals(TransferPlanReason.READY, plan.reason)
        // Must select from active union (peerA or peerB), NOT deadCachedNode
        assertTrue(plan.destinationNodeId in setOf("peerA", "peerB"))
        assertEquals(1, plan.bundlesToSend.size)
        assertEquals("b1", plan.bundlesToSend.first().bundleId)
        assertTrue(plan.reschedule, "Must reschedule because 1 bundle remains")
    }

    @Test
    fun plannerReportsUnreachableWhenPeerSetEmpty() {
        val bundle1 = StagedWatchBundle("b1", "sha1", 1L)
        val plan = WatchTransferPlanner.plan(
            stagedBundles = listOf(bundle1),
            reachableCapabilityNodes = emptySet(),
            connectedNodes = emptySet(),
            cachedNodeId = "cachedOnly",
        )

        assertEquals(TransferPlanReason.UNREACHABLE, plan.reason)
        assertEquals(null, plan.destinationNodeId)
        assertFalse(plan.reschedule)
    }
}
