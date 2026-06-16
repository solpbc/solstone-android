// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.queue

import app.solstone.core.model.QueueState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvictionPolicyTest {
    @Test
    fun underBudgetReturnsNoEvictionsOrEvents() {
        val result = evictionPolicy(
            EvictionInput(
                segments = listOf(segment("a", QueueState.UPLOADED, byteSize = 10)),
                budget = EvictionBudget(maxBytes = 20),
                emergency = false,
                decidedAtEpochMs = 7,
            ),
        )

        assertTrue(result.evictions.isEmpty())
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun evictsUploadedOldestFirstWithIdTieAndLeavesUnsynced() {
        val result = evictionPolicy(
            EvictionInput(
                segments = listOf(
                    segment("unsynced", QueueState.SEALED, byteSize = 100, sealedAt = 1),
                    segment("uploaded-c", QueueState.UPLOADED, byteSize = 25, sealedAt = 3),
                    segment("uploaded-b", QueueState.UPLOADED, byteSize = 25, sealedAt = 2),
                    segment("uploaded-a", QueueState.UPLOADED, byteSize = 25, sealedAt = 2),
                ),
                budget = EvictionBudget(maxBytes = 125),
                emergency = false,
                decidedAtEpochMs = 99,
            ),
        )

        assertEquals(listOf("uploaded-a", "uploaded-b"), result.evictions.map { it.segmentId })
        assertEquals(listOf("uploaded-a", "uploaded-b"), result.events.map { it.segmentId })
        assertEquals(listOf("eviction", "eviction"), result.events.map { it.kind })
        assertEquals(listOf(99L, 99L), result.events.map { it.atEpochMs })
        assertTrue(result.events.all { it.detail?.contains("previousState=UPLOADED") == true })
    }

    @Test
    fun nonEmergencyWithOnlyUnsyncedReturnsNoEvictions() {
        val result = evictionPolicy(
            EvictionInput(
                segments = listOf(
                    segment("sealed", QueueState.SEALED, byteSize = 75),
                    segment("failed", QueueState.FAILED, byteSize = 75),
                ),
                budget = EvictionBudget(maxBytes = 10),
                emergency = false,
                decidedAtEpochMs = 12,
            ),
        )

        assertTrue(result.evictions.isEmpty())
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun emergencyMayEvictUnsyncedAfterUploaded() {
        val result = evictionPolicy(
            EvictionInput(
                segments = listOf(
                    segment("sealed", QueueState.SEALED, byteSize = 30, sealedAt = 1),
                    segment("failed", QueueState.FAILED, byteSize = 30, sealedAt = 2),
                    segment("uploaded", QueueState.UPLOADED, byteSize = 30, sealedAt = 3),
                    segment("recording", QueueState.RECORDING, byteSize = 30, sealedAt = 4),
                    segment("uploading", QueueState.UPLOADING, byteSize = 30, sealedAt = 5),
                ),
                budget = EvictionBudget(maxBytes = 80),
                emergency = true,
                decidedAtEpochMs = 50,
            ),
        )

        assertEquals(listOf("uploaded", "sealed", "failed"), result.evictions.map { it.segmentId })
        assertEquals(listOf("uploaded", "sealed", "failed"), result.events.map { it.segmentId })
        assertFalse(result.evictions.any { it.previousState == QueueState.RECORDING || it.previousState == QueueState.UPLOADING })
        assertTrue(result.evictions.any { it.previousState == QueueState.SEALED || it.previousState == QueueState.FAILED })
        assertTrue(result.evictions.all { canTransition(it.previousState, QueueEvent.EVICT) })
        assertTrue(result.events.all { it.kind == "eviction" })
    }

    private fun segment(
        id: String,
        state: QueueState,
        byteSize: Long,
        sealedAt: Long = 1,
    ): QueueSegmentDescriptor =
        QueueSegmentDescriptor(
            id = id,
            state = state,
            byteSize = byteSize,
            sealedAtEpochMs = sealedAt,
        )
}
