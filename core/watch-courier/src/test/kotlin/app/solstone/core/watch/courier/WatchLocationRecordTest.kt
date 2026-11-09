// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchLocationRecordTest {

    @Test
    fun formatsFullLocationRecordWithExactOrder() {
        val fix = WatchLocationFix(
            provider = "gps",
            timestampEpochMs = 1762682400000L,
            lat = 39.7392,
            lon = -104.9903,
            accuracyMeters = 5.0,
            fixAgeMs = 120L,
        )
        val expected = "{\"schema\":\"solstone.location.segment/1\",\"platform\":\"wearos\"}\n{\"provider\":\"gps\",\"timestamp\":1762682400000,\"lat\":39.7392,\"lon\":-104.9903,\"accuracy\":5.0,\"fixAge\":120}\n"
        assertEquals(expected, WatchLocationRecord.buildLocationRecord(fix))
    }

    @Test
    fun omitsAccuracyWhenNanAndFixAgeWhenNull() {
        val fix = WatchLocationFix(
            provider = "fused",
            timestampEpochMs = 1762682400000L,
            lat = 40.0,
            lon = -105.0,
            accuracyMeters = Double.NaN,
            fixAgeMs = null,
        )
        val expected = "{\"schema\":\"solstone.location.segment/1\",\"platform\":\"wearos\"}\n{\"provider\":\"fused\",\"timestamp\":1762682400000,\"lat\":40.0,\"lon\":-105.0}\n"
        assertEquals(expected, WatchLocationRecord.buildLocationRecord(fix))
    }

    @Test
    fun emptyFixListProducesEmptyString() {
        assertEquals("", WatchLocationRecord.buildLocationRecord(emptyList()))
    }
}
