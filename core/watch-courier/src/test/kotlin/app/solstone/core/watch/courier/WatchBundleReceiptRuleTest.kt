// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchBundleReceiptRuleTest {

    private val baseBundle = StagedWatchBundle(
        bundleId = "bundle-123",
        sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        sealGeneration = 1L,
        sendSucceeded = false,
        channelClosed = false,
    )

    private val matchingReceipt = WatchBundleReceipt(
        protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
        bundleId = "bundle-123",
        sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        sealGeneration = 1L,
        segmentId = "20261109/watch/100000_300",
        committedAt = 1000L,
    )

    @Test
    fun sendSuccessDoesNotDelete() {
        val bundle = baseBundle.copy(sendSucceeded = true)
        assertFalse(WatchBundleReceiptRule.mayDelete(bundle, null))
    }

    @Test
    fun channelCloseDoesNotDelete() {
        val bundle = baseBundle.copy(channelClosed = true)
        assertFalse(WatchBundleReceiptRule.mayDelete(bundle, null))
    }

    @Test
    fun missingReceiptKeepsBundle() {
        assertFalse(WatchBundleReceiptRule.mayDelete(baseBundle, null))
    }

    @Test
    fun mismatchedBundleIdKeepsBundle() {
        val receipt = matchingReceipt.copy(bundleId = "different-bundle")
        assertFalse(WatchBundleReceiptRule.mayDelete(baseBundle, receipt))
    }

    @Test
    fun mismatchedDigestKeepsBundle() {
        val receipt = matchingReceipt.copy(sha256 = "0000000000000000000000000000000000000000000000000000000000000000")
        assertFalse(WatchBundleReceiptRule.mayDelete(baseBundle, receipt))
    }

    @Test
    fun malformedReceiptKeepsBundle() {
        val receipt = matchingReceipt.copy(protocolVersion = 999)
        assertFalse(WatchBundleReceiptRule.mayDelete(baseBundle, receipt))
    }

    @Test
    fun staleReceiptKeepsBundle() {
        val receipt = matchingReceipt.copy(sealGeneration = 0L)
        assertFalse(WatchBundleReceiptRule.mayDelete(baseBundle, receipt))
    }

    @Test
    fun matchingReceiptDeletesBundle() {
        assertTrue(WatchBundleReceiptRule.mayDelete(baseBundle, matchingReceipt))
    }

    @Test
    fun redeliversWhenAcknowledgementIsLost() {
        val bundle = baseBundle.copy(sendSucceeded = true)
        assertTrue(WatchBundleReceiptRule.shouldRetainForRedelivery(bundle, null))
        val plan = WatchTransferPlanner.plan(
            stagedBundles = listOf(bundle),
            reachableCapabilityNodes = setOf("node-1"),
            connectedNodes = setOf("node-1"),
        )
        assertEquals(listOf(bundle), plan.bundlesToSend)
    }
}
