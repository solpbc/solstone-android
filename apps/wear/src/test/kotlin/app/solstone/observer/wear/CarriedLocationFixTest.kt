// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import app.solstone.core.watch.courier.WatchLocationFix
import app.solstone.observer.wear.capture.carriedLocationFix
import kotlin.test.*

class CarriedLocationFixTest {
    private val fix = WatchLocationFix("fused", timestampEpochMs = 1_000_000L, lat = 39.7, lon = -105.0, accuracyMeters = 80.0, fixAgeMs = 2_000L)

    @Test fun aStillOwnersSegmentCarriesTheLastFixUnchanged() {
        val carried = carriedLocationFix(fix, windowEndEpochMs = 1_000_000L + 15 * 60_000L)
        assertSame(fix, carried, "true time and age are kept, never restamped")
    }

    @Test fun aFixFromAfterTheSegmentIsNotCarriedBack() {
        assertNull(carriedLocationFix(fix, windowEndEpochMs = 999_999L))
    }

    @Test fun noFixMeansNoLocationFile() {
        assertNull(carriedLocationFix(null, windowEndEpochMs = 2_000_000L))
    }
}
