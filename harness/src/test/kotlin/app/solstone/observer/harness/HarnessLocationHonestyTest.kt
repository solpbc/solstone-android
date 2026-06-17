// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.LOCATION_STREAM
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessLocationHonestyTest {
    @Test
    fun locationRowsAreEvidenceButNotSyncedCount() {
        val reader = FakeEvidenceReader(
            segments = listOf(evidenceSegment("loc", LOCATION_STREAM)),
            sync = HarnessSyncState(pendingCount = 0, lastSuccessAt = null, lastFailureAt = null),
        )
        assertEquals(LOCATION_STREAM, reader.listEvidence().single().stream)
        assertEquals(0, reader.pendingCount())
    }
}
