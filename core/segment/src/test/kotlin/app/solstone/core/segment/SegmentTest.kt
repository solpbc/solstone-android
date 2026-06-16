// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.segment

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SegmentTest {
    @Test
    fun wireKeysHandlesDstFallBackRepeatedHour() {
        val zone = ZoneId.of("America/New_York")
        val first = LocalDateTime.of(2026, 11, 1, 1, 15).atZone(zone).toInstant().toEpochMilli()
        val second = first + 3_600_000L
        val firstKeys = wireKeys(first, first + 300_000L, zone)
        val secondKeys = wireKeys(second, second + 300_000L, zone)
        assertEquals("011500_300", firstKeys.segment)
        assertEquals("011500_300", secondKeys.segment)
        assertNotEquals(firstKeys.utcOffsetSeconds, secondKeys.utcOffsetSeconds)
    }

    @Test
    fun wireKeysHandlesDstSpringForwardSkippedHour() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDateTime.of(2026, 3, 8, 3, 5).atZone(zone).toInstant().toEpochMilli()
        val keys = wireKeys(start, start + 300_000L, zone)
        assertEquals("20260308", keys.day)
        assertEquals("030500_300", keys.segment)
    }

    @Test
    fun wireKeysReflectsTimezoneChoiceAndClockJumpInputs() {
        val instant = 1_787_774_400_000L
        val denver = wireKeys(instant, instant + 300_000L, ZoneId.of("America/Denver"))
        val tokyo = wireKeys(instant, instant + 300_000L, ZoneId.of("Asia/Tokyo"))
        assertNotEquals(denver.segment, tokyo.segment)

        val earlier = wireKeys(instant - 120_000L, instant + 180_000L, ZoneId.of("America/Denver"))
        assertTrue(earlier.startEpochMs < denver.startEpochMs)
        assertTrue(earlier.segment.endsWith("_300"))
    }

    @Test
    fun wireKeysComputesPartialLenAndSameMinuteDistinctKeys() {
        val zone = ZoneId.of("UTC")
        val start = 1_772_582_410_000L
        assertEquals("000010_299", wireKeys(start, start + 299_900L, zone).segment)
        assertEquals("000010_20", wireKeys(start, start + 20_999L, zone).segment)
        assertNotEquals(
            wireKeys(start, start + 20_000L, zone).segment,
            wireKeys(start + 30_000L, start + 50_000L, zone).segment,
        )
    }
}
