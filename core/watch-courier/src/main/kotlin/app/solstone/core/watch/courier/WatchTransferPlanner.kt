// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

data class DiscoveredCourierPeer(
    val nodeId: String,
    val isNearby: Boolean = true,
)

data class WatchTransferPlan(
    val destinationNodeId: String?,
    val bundlesToSend: List<StagedWatchBundle>,
    val reschedule: Boolean,
    val reason: TransferPlanReason,
)

enum class TransferPlanReason {
    READY,
    UNREACHABLE,
    NO_WORK,
}

object WatchTransferPlanner {
    fun plan(
        stagedBundles: List<StagedWatchBundle>,
        reachableCapabilityNodes: Set<String>,
        connectedNodes: Set<String>,
        cachedNodeId: String? = null,
        batchCap: Int = WatchCourierProtocol.DEFAULT_BUNDLE_BATCH_CAP,
    ): WatchTransferPlan {
        val eligibleBundles = stagedBundles.filter { WatchBundleReceiptRule.shouldRetainForRedelivery(it, null) }
        if (eligibleBundles.isEmpty()) {
            return WatchTransferPlan(null, emptyList(), reschedule = false, reason = TransferPlanReason.NO_WORK)
        }

        // Peer set is the union of reachable capability nodes and connected nodes
        val activePeers = reachableCapabilityNodes union connectedNodes
        if (activePeers.isEmpty()) {
            return WatchTransferPlan(null, emptyList(), reschedule = false, reason = TransferPlanReason.UNREACHABLE)
        }

        // A cached node id absent from the peer set is not a destination; choose a reachable node
        val destination = if (cachedNodeId != null && cachedNodeId in activePeers) {
            cachedNodeId
        } else {
            activePeers.first()
        }

        val takeCount = minOf(eligibleBundles.size, batchCap)
        val selected = eligibleBundles.take(takeCount)
        val remaining = eligibleBundles.size > takeCount

        return WatchTransferPlan(
            destinationNodeId = destination,
            bundlesToSend = selected,
            reschedule = remaining,
            reason = TransferPlanReason.READY,
        )
    }
}
