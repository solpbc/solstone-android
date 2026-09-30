// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.segment

import app.solstone.core.model.GapEvent
import app.solstone.core.model.SourceKind
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SegmentTest {
    @Test
    fun wireKeysUseAsciiGregorianTimeUnderDeviceLocales() {
        val originalLocale = Locale.getDefault()
        try {
            for (languageTag in listOf("ar-SA", "fa-IR", "th-TH-u-ca-buddhist", "en-US-u-hc-h12")) {
                Locale.setDefault(Locale.forLanguageTag(languageTag))
                for ((instant, zoneName, expectedDay, expectedSegment, offset) in listOf(
                    listOf("2026-09-29T23:58:00Z", "Asia/Tokyo", "20260930", "085800_300", "32400"),
                    listOf("2026-11-01T07:15:00Z", "America/Denver", "20261101", "011500_300", "-21600"),
                    listOf("2026-11-01T08:15:00Z", "America/Denver", "20261101", "011500_300", "-25200"),
                    listOf("2026-03-08T09:05:00Z", "America/Denver", "20260308", "030500_300", "-21600"),
                    listOf("2026-01-15T06:30:00Z", "Asia/Kolkata", "20260115", "120000_300", "19800"),
                )) {
                    val start = Instant.parse(instant).toEpochMilli()
                    val keys = wireKeys(start, start + 300_000L, ZoneId.of(zoneName))
                    assertEquals(expectedDay, keys.day, languageTag)
                    assertEquals(expectedSegment, keys.segment, languageTag)
                    assertEquals(zoneName, keys.zoneId)
                    assertEquals(offset.toInt(), keys.utcOffsetSeconds)
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

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
        val sealed = segmenter.feed(audioEmission(BASE_EPOCH_MS + 600_000L, BASE_EPOCH_MS + 900_000L)).sealed

        val first = sealed.single { it.wireKeys.startEpochMs == BASE_EPOCH_MS }
        assertEquals("000000_300", first.key.segment)
        assertEquals(BASE_EPOCH_MS, first.wireKeys.startEpochMs)
        assertEquals(BASE_EPOCH_MS + 300_000L, first.wireKeys.endEpochMs)

        val partialSegmenter = Segmenter(ZoneId.of("UTC"))
        partialSegmenter.feed(audioEmission(BASE_EPOCH_MS + 10_000L, BASE_EPOCH_MS + 70_000L))
        val partial = partialSegmenter.flush().sealed.single()
        assertEquals("000000_70", partial.key.segment)
        assertEquals(BASE_EPOCH_MS + 70_000L, partial.wireKeys.endEpochMs)
    }

    @Test
    fun segmenterSealDueSealsQuietWindow() {
        val segmenter = Segmenter(ZoneId.of("UTC"))
        segmenter.feed(audioEmission(BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L))

        val sealed = segmenter.sealDue(BASE_EPOCH_MS + 305_000L).sealed.single()

        assertEquals("000000_300", sealed.key.segment)
        assertEquals(BASE_EPOCH_MS, sealed.wireKeys.startEpochMs)
    }

    @Test
    fun feedSealsOnlyAfterWindowEndPlusGrace() {
        val normalSegmenter = Segmenter(ZoneId.of("UTC"))
        normalSegmenter.feed(audioEmission(BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L))

        val beforeGrace = normalSegmenter.feed(
            audioEmission(BASE_EPOCH_MS + 304_999L, BASE_EPOCH_MS + 305_000L),
        )
        assertTrue(beforeGrace.sealed.isEmpty())

        val atGrace = normalSegmenter.feed(
            audioEmission(BASE_EPOCH_MS + 305_000L, BASE_EPOCH_MS + 306_000L),
        )
        assertEquals(BASE_EPOCH_MS, atGrace.sealed.single().wireKeys.startEpochMs)

        val lateSegmenter = Segmenter(ZoneId.of("UTC"))
        lateSegmenter.feed(audioEmission(BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L))
        lateSegmenter.feed(audioEmission(BASE_EPOCH_MS + 305_000L, BASE_EPOCH_MS + 306_000L))

        val lateBeforeGrace = lateSegmenter.feed(
            locationEmission(BASE_EPOCH_MS + 10_000L, BASE_EPOCH_MS + 20_000L),
        )
        assertTrue(lateBeforeGrace.sealed.isEmpty())
    }

    @Test
    fun segmenterRecordsLateEmissionInDurableLaterWindow() {
        val segmenter = Segmenter(ZoneId.of("UTC"))
        val original = audioEmission(BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L)
        segmenter.feed(original)
        val sealedOriginal = segmenter.feed(audioEmission(BASE_EPOCH_MS + 305_000L, BASE_EPOCH_MS + 600_000L)).sealed.single()
        assertTrue(sealedOriginal.gaps.isEmpty())

        val lateResult = segmenter.feed(audioEmission(BASE_EPOCH_MS + 10_000L, BASE_EPOCH_MS + 20_000L))
        assertTrue(lateResult.sealed.isEmpty())

        val later = segmenter.flush().sealed.single()
        assertEquals("000500_300", later.key.segment)
        assertEquals(
            listOf(GapEvent("late_emission", BASE_EPOCH_MS + 10_000L, BASE_EPOCH_MS.toString())),
            later.gaps,
        )
    }

    @Test
    fun lateEmissionPreservesOriginalGapsAndLateMarker() {
        val segmenter = Segmenter(ZoneId.of("UTC"))
        val sourceGap = GapEvent("location_gap", BASE_EPOCH_MS + 20_000L, "no_fix")

        segmenter.feed(audioEmission(BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L))
        segmenter.feed(audioEmission(BASE_EPOCH_MS + 305_000L, BASE_EPOCH_MS + 306_000L))
        val late = segmenter.feed(
            locationEmission(
                BASE_EPOCH_MS + 10_000L,
                BASE_EPOCH_MS + 20_000L,
                gaps = listOf(sourceGap),
            ),
        )

        assertTrue(late.sealed.isEmpty())
        val later = segmenter.flush().sealed.single()
        assertEquals(
            listOf(
                GapEvent("late_emission", BASE_EPOCH_MS + 10_000L, BASE_EPOCH_MS.toString()),
                sourceGap,
            ).sortedWith(compareBy<GapEvent> { it.atEpochMs }.thenBy { it.kind }.thenBy { it.detail ?: "" }),
            later.gaps,
        )
    }

    @Test
    fun audioAndLocationOffsetStartsJoinSameObserverWindow() {
        val segmenter = Segmenter(ZoneId.of("UTC"))

        // cell0=[BASE,BASE+300k), cell1=[BASE+300k,BASE+600k)
        segmenter.feed(audioEmission(BASE_EPOCH_MS + 12_000L, BASE_EPOCH_MS + 300_000L))
        segmenter.feed(locationEmission(BASE_EPOCH_MS + 47_000L, BASE_EPOCH_MS + 107_000L))
        val sealed = segmenter.flush().sealed.single()

        assertEquals(MAIN_STREAM, sealed.stream)
        assertEquals(2, sealed.payloads.size)
        assertEquals(setOf("audio", "location"), sealed.payloads.map { it.sourceId }.toSet())
        assertEquals(setOf("audio.m4a", "location.jsonl"), sealed.payloads.map { it.ref.name }.toSet())
    }

    @Test
    fun adjacentCellsSealWithOnlyTheirOwnSourcesNoCrossTruncation() {
        val segmenter = Segmenter(ZoneId.of("UTC"))

        // cell0=[BASE,BASE+300k), cell1=[BASE+300k,BASE+600k)
        segmenter.feed(audioEmission(BASE_EPOCH_MS + 12_000L, BASE_EPOCH_MS + 300_000L))
        val sealedCell0 = segmenter.feed(locationEmission(BASE_EPOCH_MS + 347_000L, BASE_EPOCH_MS + 360_000L)).sealed.single()

        assertEquals(listOf("audio"), sealedCell0.payloads.map { it.sourceId })
        assertEquals(BASE_EPOCH_MS + 300_000L, sealedCell0.wireKeys.endEpochMs)

        val sealedCell1 = segmenter.flush().sealed.single()
        assertEquals(listOf("location"), sealedCell1.payloads.map { it.sourceId })
    }

    @Test
    fun lateLocationAfterSharedWatermarkAdvancedIsVisibleGapNotSilentDrop() {
        val segmenter = Segmenter(ZoneId.of("UTC"))

        // cell0=[BASE,BASE+300k), cell1=[BASE+300k,BASE+600k)
        segmenter.feed(audioEmission(BASE_EPOCH_MS + 12_000L, BASE_EPOCH_MS + 300_000L))
        val cell0 = segmenter.feed(audioEmission(BASE_EPOCH_MS + 312_000L, BASE_EPOCH_MS + 600_000L)).sealed.single()
        segmenter.feed(locationEmission(BASE_EPOCH_MS + 47_000L, BASE_EPOCH_MS + 107_000L))

        val cell1 = segmenter.flush().sealed.single()
        val lateGap = cell1.gaps.single { it.kind == "late_emission" }
        assertEquals(BASE_EPOCH_MS.toString(), lateGap.detail)
        assertFalse(listOf(cell0, cell1).flatMap { it.payloads }.any { it.ref.name == "location.jsonl" })
        assertTrue(cell1.payloads.any { it.sourceId == "audio" })
    }

    @Test
    fun gapOnlyLocationDoesNotPrematurelySealOrTruncateAudioWindow() {
        val segmenter = Segmenter(ZoneId.of("UTC"))
        val locationGap = GapEvent("location_gap", BASE_EPOCH_MS + 107_000L, "no_fix")

        // cell0=[BASE,BASE+300k), cell1=[BASE+300k,BASE+600k)
        segmenter.feed(audioEmission(BASE_EPOCH_MS + 12_000L, BASE_EPOCH_MS + 300_000L))
        val sealed = segmenter.feed(locationEmission(BASE_EPOCH_MS + 47_000L, BASE_EPOCH_MS + 107_000L, gaps = listOf(locationGap)))
        assertTrue(sealed.sealed.isEmpty())

        val flushed = segmenter.flush().sealed.single()
        assertEquals(listOf("audio"), flushed.payloads.map { it.sourceId })
        assertEquals(BASE_EPOCH_MS + 300_000L, flushed.wireKeys.endEpochMs)
        assertTrue(locationGap in flushed.gaps)
    }

    @Test
    fun liveZoneSourceEvaluatesOnBucketCreationAcrossTravelTransitions() {
        var callCount = 0
        val segmenter = Segmenter(
            zoneSource = {
                callCount++
                if (callCount == 1) ZoneId.of("Asia/Kolkata") else ZoneId.of("Pacific/Auckland")
            },
        )
        val w1Start = 1_790_706_300_000L
        val w2Start = 1_790_706_600_000L

        val sealed = mutableListOf<SealedSegment>()
        sealed += segmenter.feed(audioEmission(w1Start, w1Start + 10_000L)).sealed
        sealed += segmenter.feed(audioEmission(w1Start + 20_000L, w1Start + 300_000L)).sealed

        sealed += segmenter.feed(audioEmission(w2Start, w2Start + 10_000L)).sealed
        sealed += segmenter.feed(audioEmission(w2Start + 20_000L, w2Start + 300_000L)).sealed

        sealed += segmenter.flush().sealed
        assertEquals(2, sealed.size)
        assertEquals(2, callCount)

        val first = sealed[0]
        assertEquals("20260929", first.wireKeys.day)
        assertEquals("235500_300", first.wireKeys.segment)
        assertEquals("Asia/Kolkata", first.wireKeys.zoneId)
        assertEquals(19800, first.wireKeys.utcOffsetSeconds)

        val second = sealed[1]
        assertEquals("20260930", second.wireKeys.day)
        assertEquals("073000_300", second.wireKeys.segment)
        assertEquals("Pacific/Auckland", second.wireKeys.zoneId)
        assertEquals(46800, second.wireKeys.utcOffsetSeconds)
    }

    @Test
    fun subsequentEmissionsInOpenBucketRetainCapturedZone() {
        var currentZone = ZoneId.of("Europe/Paris")
        var callCount = 0
        val segmenter = Segmenter(
            zoneSource = {
                callCount++
                currentZone
            },
        )
        val windowStart = 1_790_706_300_000L

        segmenter.feed(audioEmission(windowStart, windowStart + 10_000L))
        currentZone = ZoneId.of("Pacific/Auckland")
        segmenter.feed(audioEmission(windowStart + 20_000L, windowStart + 300_000L))

        val flushed = segmenter.flush().sealed.single()
        assertEquals("Europe/Paris", flushed.wireKeys.zoneId)
        assertEquals(1, callCount)
    }

    @Test
    fun throwingZoneSourceFallsBackToLastSuccessOrSystemOffsetWithoutCachingFallback() {
        // Case 1: After successful bucket, throwing source uses last successful zone
        var shouldThrow = false
        val segmenter = Segmenter(
            zoneSource = {
                if (shouldThrow) throw IllegalStateException("zone source failure")
                ZoneId.of("Asia/Tokyo")
            },
        )
        val w1Start = 1_790_706_300_000L
        val w2Start = 1_790_706_600_000L

        val sealed = mutableListOf<SealedSegment>()
        val r1 = segmenter.feed(audioEmission(w1Start, w1Start + 300_000L))
        assertTrue(r1.droppedPayloads.isEmpty())
        sealed += r1.sealed

        shouldThrow = true
        val r2 = segmenter.feed(audioEmission(w2Start, w2Start + 300_000L))
        assertTrue(r2.droppedPayloads.isEmpty())
        sealed += r2.sealed

        val flushed = segmenter.flush()
        assertTrue(flushed.droppedPayloads.isEmpty())
        sealed += flushed.sealed
        assertEquals(2, sealed.size)
        assertEquals("Asia/Tokyo", sealed[0].wireKeys.zoneId)
        assertEquals("Asia/Tokyo", sealed[1].wireKeys.zoneId)

        // Case 2: Throwing source on first bucket and keeps throwing; uses TimeZone offset without caching
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Paris"))
            val throwingSegmenter = Segmenter(
                zoneSource = { throw IllegalStateException("persistent failure") },
            )
            val t1 = Instant.parse("2026-10-25T00:30:00Z").toEpochMilli() // CEST (+02:00)
            val t2 = Instant.parse("2026-10-25T02:00:00Z").toEpochMilli() // CET (+01:00)

            val fallbackSealed = mutableListOf<SealedSegment>()
            val feed1 = throwingSegmenter.feed(audioEmission(t1, t1 + 300_000L))
            assertTrue(feed1.droppedPayloads.isEmpty())
            fallbackSealed += feed1.sealed

            val feed2 = throwingSegmenter.feed(audioEmission(t2, t2 + 300_000L))
            assertTrue(feed2.droppedPayloads.isEmpty())
            fallbackSealed += feed2.sealed

            val fallbackFlushed = throwingSegmenter.flush()
            assertTrue(fallbackFlushed.droppedPayloads.isEmpty())
            fallbackSealed += fallbackFlushed.sealed
            assertEquals(2, fallbackSealed.size)

            val expectedId1 = java.time.ZoneOffset.ofTotalSeconds(Math.toIntExact(Math.floorDiv(java.util.TimeZone.getDefault().getOffset(t1).toLong(), 1000L))).id
            val expectedId2 = java.time.ZoneOffset.ofTotalSeconds(Math.toIntExact(Math.floorDiv(java.util.TimeZone.getDefault().getOffset(t2).toLong(), 1000L))).id

            assertEquals("+02:00", expectedId1)
            assertEquals("+01:00", expectedId2)
            assertEquals(expectedId1, fallbackSealed[0].wireKeys.zoneId)
            assertEquals(expectedId2, fallbackSealed[1].wireKeys.zoneId)
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun systemZoneIdTracksDefaultTimeZoneChanges() {
        val originalTz = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals(ZoneId.of("Asia/Kolkata"), systemZoneId())

            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Auckland"))
            assertEquals(ZoneId.of("Pacific/Auckland"), systemZoneId())
        } finally {
            java.util.TimeZone.setDefault(originalTz)
        }
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

    private fun locationEmission(
        startEpochMs: Long,
        endEpochMs: Long,
        gaps: List<GapEvent> = emptyList(),
    ): SourceEmission =
        SourceEmission(
            sourceId = "location",
            stream = MAIN_STREAM,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = startEpochMs,
            captureEndEpochMs = endEpochMs,
            payloadRefs = if (gaps.isEmpty()) {
                listOf(PayloadRef("location.jsonl", "application/x-ndjson", 24, null))
            } else {
                emptyList()
            },
            metadata = emptyMap(),
            gaps = gaps,
        )

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
    }
}
