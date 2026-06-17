// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.MAIN_STREAM
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessLocationHonestyTest {
    @Test
    fun locationIsOrdinaryObserverStreamEvidence() {
        val reader = FakeEvidenceReader(
            segments = listOf(evidenceSegment("loc", MAIN_STREAM)),
            sync = HarnessSyncState(pendingCount = 0, lastSuccessAt = null, lastFailureAt = null),
        )
        assertEquals(MAIN_STREAM, reader.listEvidence().single().stream)
        // Orphan stream="location" rows are excluded from pending count by SegmentDao.pendingCount(MAIN_STREAM) scoping.
        assertEquals(0, reader.pendingCount())
    }
}
