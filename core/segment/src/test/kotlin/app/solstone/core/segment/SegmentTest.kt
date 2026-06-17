// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.segment

import app.solstone.core.model.GapEvent
import app.solstone.core.model.SourceKind
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
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

    @Test
    fun segmenterUsesCaptureTimeGridAndLenForFullAndPartialWindows() {
        val segmenter = Segmenter(ZoneId.of("UTC"))

        segmenter.feed(audioEmission(BASE_EPOCH_MS + 300_000L, BASE_EPOCH_MS + 600_000L))
        segmenter.feed(audioEmission(BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L))
        val sealed = segmenter.feed(audioEmission(BASE_EPOCH_MS + 600_000L, BASE_EPOCH_MS + 900_000L))

        val first = sealed.single { it.wireKeys.startEpochMs == BASE_EPOCH_MS }
        assertEquals("000000_300", first.key.segment)
        assertEquals(BASE_EPOCH_MS, first.wireKeys.startEpochMs)
        assertEquals(BASE_EPOCH_MS + 300_000L, first.wireKeys.endEpochMs)

        val partialSegmenter = Segmenter(ZoneId.of("UTC"))
        partialSegmenter.feed(audioEmission(BASE_EPOCH_MS + 10_000L, BASE_EPOCH_MS + 70_000L))
        val partial = partialSegmenter.flush().single()
        assertEquals("000000_70", partial.key.segment)
        assertEquals(BASE_EPOCH_MS + 70_000L, partial.wireKeys.endEpochMs)
    }

    @Test
    fun segmenterSealDueSealsQuietWindow() {
        val segmenter = Segmenter(ZoneId.of("UTC"))
        segmenter.feed(audioEmission(BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L))

        val sealed = segmenter.sealDue(BASE_EPOCH_MS + 305_000L).single()

        assertEquals("000000_300", sealed.key.segment)
        assertEquals(BASE_EPOCH_MS, sealed.wireKeys.startEpochMs)
    }

    @Test
    fun segmenterRecordsLateEmissionInDurableLaterWindow() {
        val segmenter = Segmenter(ZoneId.of("UTC"))
        val original = audioEmission(BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L)
        segmenter.feed(original)
        val sealedOriginal = segmenter.feed(audioEmission(BASE_EPOCH_MS + 300_000L, BASE_EPOCH_MS + 600_000L)).single()
        assertTrue(sealedOriginal.gaps.isEmpty())

        val lateResult = segmenter.feed(audioEmission(BASE_EPOCH_MS + 10_000L, BASE_EPOCH_MS + 20_000L))
        assertTrue(lateResult.isEmpty())

        val later = segmenter.flush().single()
        assertEquals("000500_300", later.key.segment)
        assertEquals(
            listOf(GapEvent("late_emission", BASE_EPOCH_MS + 10_000L, BASE_EPOCH_MS.toString())),
            later.gaps,
        )
    }

    private fun audioEmission(startEpochMs: Long, endEpochMs: Long): SourceEmission =
        SourceEmission(
            sourceId = "audio",
            stream = MAIN_STREAM,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = startEpochMs,
            captureEndEpochMs = endEpochMs,
            payloadRefs = listOf(PayloadRef("audio.m4a", "audio/mp4", 16, null)),
            metadata = emptyMap(),
            gaps = emptyList(),
        )

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
    }
}
