// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTransferPlannerTest {

    private val sampleBundle = StagedWatchBundle(
        bundleId = "b1",
        sha256 = "sha1",
        sealGeneration = 1L,
    )

    @Test
    fun noWorkWhenNoBundlesStaged() {
        val plan = WatchTransferPlanner.plan(
            stagedBundles = emptyList(),
            reachableCapabilityNodes = setOf("nodeA"),
            connectedNodes = setOf("nodeA"),
        )
        assertEquals(TransferPlanReason.NO_WORK, plan.reason)
        assertFalse(plan.reschedule)
        assertTrue(plan.bundlesToSend.isEmpty())
    }

    @Test
    fun unreachableWhenNoPeersAvailable() {
        val plan = WatchTransferPlanner.plan(
            stagedBundles = listOf(sampleBundle),
            reachableCapabilityNodes = emptySet(),
            connectedNodes = emptySet(),
        )
        assertEquals(TransferPlanReason.UNREACHABLE, plan.reason)
        assertFalse(plan.reschedule)
        assertTrue(plan.bundlesToSend.isEmpty())
    }

    @Test
    fun peerUnionEnablesDelivery() {
        // Only reachable capability node
        val plan1 = WatchTransferPlanner.plan(
            stagedBundles = listOf(sampleBundle),
            reachableCapabilityNodes = setOf("nodeCapOnly"),
            connectedNodes = emptySet(),
        )
        assertEquals("nodeCapOnly", plan1.destinationNodeId)
        assertEquals(TransferPlanReason.READY, plan1.reason)

        // Only connected node
        val plan2 = WatchTransferPlanner.plan(
            stagedBundles = listOf(sampleBundle),
            reachableCapabilityNodes = emptySet(),
            connectedNodes = setOf("nodeConnOnly"),
        )
        assertEquals("nodeConnOnly", plan2.destinationNodeId)
        assertEquals(TransferPlanReason.READY, plan2.reason)
    }

    @Test
    fun batchCapLimitsTransferAndSetsReschedule() {
        val bundles = (1..10).map {
            StagedWatchBundle(bundleId = "b$it", sha256 = "sha$it", sealGeneration = 1L)
        }
        val plan = WatchTransferPlanner.plan(
            stagedBundles = bundles,
            reachableCapabilityNodes = setOf("node1"),
            connectedNodes = setOf("node1"),
            batchCap = 4,
        )
        assertEquals(4, plan.bundlesToSend.size)
        assertTrue(plan.reschedule)
        assertEquals(TransferPlanReason.READY, plan.reason)
    }

    @Test
    fun currentNearbyPhoneWinsOverAReachableOlderPeer() {
        val plan = WatchTransferPlanner.plan(
            stagedBundles = listOf(sampleBundle),
            reachableCapabilityNodes = setOf("oldPhone", "newPhone"),
            connectedNodes = setOf("oldPhone", "newPhone"),
            cachedNodeId = "oldPhone",
            nearbyNodeIds = setOf("newPhone"),
        )
        assertEquals("newPhone", plan.destinationNodeId)
    }

    @Test
    fun invalidCachedNodeFallsBackToActivePeer() {
        val plan = WatchTransferPlanner.plan(
            stagedBundles = listOf(sampleBundle),
            reachableCapabilityNodes = setOf("nodeReal"),
            connectedNodes = emptySet(),
            cachedNodeId = "nodeStaleGone",
        )
        assertEquals("nodeReal", plan.destinationNodeId)
    }
}
