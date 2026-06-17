// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.MAIN_STREAM
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessEvidenceTest {
    @Test
    fun evidenceListsPerFileProvenance() {
        val reader = FakeEvidenceReader(
            segments = listOf(evidenceSegment("main", MAIN_STREAM), evidenceSegment("loc", MAIN_STREAM)),
            sync = HarnessSyncState(pendingCount = 1, lastSuccessAt = 2, lastFailureAt = null),
        )
        val f = fixture(evidenceReader = reader)

        val evidence = f.controller.listEvidence()
        assertEquals(listOf("main", "loc"), evidence.map { it.id })
        assertEquals("audio.m4a", evidence.first().files.single().name)
        assertEquals(1, reader.pendingCount())
    }
}
