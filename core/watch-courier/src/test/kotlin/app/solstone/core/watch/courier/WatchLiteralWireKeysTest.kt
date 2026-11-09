// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import app.solstone.core.segment.wireKeys
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchLiteralWireKeysTest {

    @Test
    fun midnightCrossingWindowRetainsStartDayAndStartClock() {
        val zone = ZoneId.of("America/Denver") // UTC-7 (offset -25200)
        // First frame accepted at 2026-11-09 23:58:37 MST (non-zero seconds, not bucket floor)
        val startZdt = ZonedDateTime.of(2026, 11, 9, 23, 58, 37, 0, zone)
        val startEpochMs = startZdt.toInstant().toEpochMilli()
        // 5-minute window ends at 2026-11-10 00:03:37 MST (crossing local midnight)
        val endEpochMs = startEpochMs + 300_000L

        val keys = wireKeys(startEpochMs, endEpochMs, zone)

        assertEquals("20261109", keys.day)
        assertEquals("235837_300", keys.segment)
        assertEquals("America/Denver", keys.zoneId)
        assertEquals(-25200, keys.utcOffsetSeconds)
    }

    @Test
    fun partialWindowUsesRealLengthClampedBetweenOneAndThreeHundred() {
        val zone = ZoneId.of("UTC")
        // First frame accepted at 2026-11-09 10:15:23 UTC
        val startZdt = ZonedDateTime.of(2026, 11, 9, 10, 15, 23, 0, zone)
        val startEpochMs = startZdt.toInstant().toEpochMilli()

        // 45-second partial window (stopped early by owner), segment is not _300
        val partial45 = wireKeys(startEpochMs, startEpochMs + 45_000L, zone)
        assertEquals("20261109", partial45.day)
        assertEquals("101523_45", partial45.segment)

        // Sub-second partial window clamps to minimum 1 second
        val partialSub = wireKeys(startEpochMs, startEpochMs + 500L, zone)
        assertEquals("101523_1", partialSub.segment)

        // 12-second partial window
        val partial12 = wireKeys(startEpochMs, startEpochMs + 12_000L, zone)
        assertEquals("101523_12", partial12.segment)

        // Window longer than 300 clamps to maximum 300
        val partialLong = wireKeys(startEpochMs, startEpochMs + 350_000L, zone)
        assertEquals("101523_300", partialLong.segment)
    }
}
