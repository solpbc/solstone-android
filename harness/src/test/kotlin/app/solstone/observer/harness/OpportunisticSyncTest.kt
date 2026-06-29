// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpportunisticSyncTest {
    @Test
    fun usableNetworkEnqueuesOnlyWhenPendingAndDedupesSamePendingCount() {
        val evidence = FakeEvidenceReader(sync = HarnessSyncState(0, null, null))
        val sync = RecordingSyncEnqueue()
        val network = FakeNetworkAvailability()
        val opportunistic = OpportunisticSync(evidence, sync, network)

        opportunistic.start()
        network.triggerUsableNetwork()
        assertEquals(0, sync.calls)

        evidence.pendingCountValue = 2
        network.triggerUsableNetwork()
        assertEquals(1, sync.calls)

        network.triggerUsableNetwork()
        assertEquals(1, sync.calls)

        evidence.pendingCountValue = 3
        network.triggerUsableNetwork()
        assertEquals(2, sync.calls)
    }

    @Test
    fun zeroPendingResetsNetworkDedupe() {
        val evidence = FakeEvidenceReader(sync = HarnessSyncState(2, null, null))
        val sync = RecordingSyncEnqueue()
        val network = FakeNetworkAvailability()
        val opportunistic = OpportunisticSync(evidence, sync, network)

        opportunistic.start()
        network.triggerUsableNetwork()
        assertEquals(1, sync.calls)

        evidence.pendingCountValue = 0
        network.triggerUsableNetwork()
        assertEquals(1, sync.calls)

        evidence.pendingCountValue = 2
        network.triggerUsableNetwork()
        assertEquals(2, sync.calls)
    }

    @Test
    fun startIsIdempotentAndStopUnregistersSafely() {
        val network = FakeNetworkAvailability()
        val opportunistic = OpportunisticSync(
            evidenceReader = FakeEvidenceReader(),
            syncEnqueue = RecordingSyncEnqueue(),
            networkAvailability = network,
        )

        opportunistic.start()
        opportunistic.start()
        assertEquals(1, network.startCalls)

        opportunistic.stop()
        opportunistic.stop()
        assertEquals(1, network.stopCalls)
    }

    @Test
    fun failedRegistrationMarksDegradedAndCanRetry() {
        val network = FakeNetworkAvailability().also { it.failOnStart = true }
        val opportunistic = OpportunisticSync(
            evidenceReader = FakeEvidenceReader(),
            syncEnqueue = RecordingSyncEnqueue(),
            networkAvailability = network,
        )

        opportunistic.start()

        assertTrue(opportunistic.isDegraded())
        assertEquals("network registration failed: IllegalStateException", opportunistic.lastError)
        assertEquals(1, network.startCalls)

        network.failOnStart = false
        opportunistic.start()

        assertFalse(opportunistic.isDegraded())
        assertEquals(2, network.startCalls)
    }

    @Test
    fun pendingInspectionFailureMarksDegradedAndDoesNotEnqueue() {
        val evidence = FakeEvidenceReader(sync = HarnessSyncState(1, null, null))
        evidence.failPendingCount = true
        val sync = RecordingSyncEnqueue()
        val network = FakeNetworkAvailability()
        val opportunistic = OpportunisticSync(evidence, sync, network)

        opportunistic.start()
        network.triggerUsableNetwork()

        assertEquals(0, sync.calls)
        assertTrue(opportunistic.isDegraded())
        assertEquals("pending inspection failed: IllegalStateException", opportunistic.lastError)
    }

    @Test
    fun pairingSuccessAndStopFlushPendingWithoutChangingNetworkDedupe() {
        val evidence = FakeEvidenceReader(sync = HarnessSyncState(2, null, null))
        val sync = RecordingSyncEnqueue()
        val network = FakeNetworkAvailability()
        val opportunistic = OpportunisticSync(evidence, sync, network)

        opportunistic.start()
        opportunistic.onPairingSuccess()
        assertEquals(1, sync.calls)

        network.triggerUsableNetwork()
        assertEquals(2, sync.calls)

        network.triggerUsableNetwork()
        assertEquals(2, sync.calls)

        opportunistic.stop()
        assertEquals(3, sync.calls)
        assertEquals(1, network.stopCalls)
    }
}
