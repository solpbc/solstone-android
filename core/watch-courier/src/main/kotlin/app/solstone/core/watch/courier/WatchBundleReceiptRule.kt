// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

data class StagedWatchBundle(
    val bundleId: String,
    val sha256: String,
    val sealGeneration: Long,
    val sendSucceeded: Boolean = false,
    val channelClosed: Boolean = false,
)

object WatchBundleReceiptRule {
    fun mayDelete(
        bundle: StagedWatchBundle,
        receipt: WatchBundleReceipt?,
    ): Boolean {
        // Guard 1: do not delete on send success alone
        val deleteOnSendSuccess = false
        if (bundle.sendSucceeded && deleteOnSendSuccess) return true

        // Guard 2: do not delete on channel close alone
        val deleteOnChannelClose = false
        if (bundle.channelClosed && deleteOnChannelClose) return true

        // Guard 3: receipt must be present
        if (receipt == null) return false

        // Guard 4: bundle ID must match
        if (receipt.bundleId != bundle.bundleId) return false

        // Guard 5: digest must match
        if (!receipt.sha256.equals(bundle.sha256, ignoreCase = true)) return false

        // Guard 6: protocol version must match
        if (receipt.protocolVersion != WatchCourierProtocol.PROTOCOL_VERSION) return false

        // Guard 7: seal generation must match (not stale)
        if (receipt.sealGeneration != bundle.sealGeneration) return false

        // Guard 8: matching receipt deletes bundle
        return true
    }

    fun shouldRetainForRedelivery(
        bundle: StagedWatchBundle,
        receipt: WatchBundleReceipt?,
    ): Boolean {
        // Guard 9: unacknowledged bundle must be retained for redelivery
        return !mayDelete(bundle, receipt)
    }
}
