// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.spool.SealResult
import app.solstone.core.spool.SealState
import app.solstone.core.spool.SealedSegmentSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SyncOnSealTest {
    @Test
    fun aSealIsPersistedFirstAndThenAsksForASync() {
        val order = mutableListOf<String>()
        val sink = syncingOnSeal(SealedSegmentSink { _, _, _ -> order += "persist" }) { order += "sync" }

        sink.persistSealed(sealedSegment(), sealResult(), 1L)

        assertEquals(listOf("persist", "sync"), order)
    }

    @Test
    fun aFailingSyncRequestNeverFailsTheSeal() {
        var persisted = 0
        val sink = syncingOnSeal(SealedSegmentSink { _, _, _ -> persisted += 1 }) { error("work manager unavailable") }

        sink.persistSealed(sealedSegment(), sealResult(), 1L)

        assertEquals(1, persisted)
    }

    @Test
    fun aSealThatFailedToPersistAsksForNothing() {
        var requests = 0
        val sink = syncingOnSeal(SealedSegmentSink { _, _, _ -> error("disk full") }) { requests += 1 }

        assertFailsWith<IllegalStateException> { sink.persistSealed(sealedSegment(), sealResult(), 1L) }
        assertEquals(0, requests)
    }
}

private val KEY = SegmentKey("20260924", "120000_300")

private fun sealedSegment(): SealedSegment =
    SealedSegment(
        stream = "main",
        key = KEY,
        wireKeys = WireKeys("20260924", "120000_300", 0L, 300_000L, "UTC", 0),
        payloads = emptyList(),
        gaps = emptyList(),
    )

private fun sealResult(): SealResult = SealResult(BundleManifest(KEY, emptyList(), emptyList()), null, SealState.SEALED)
