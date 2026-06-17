// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.location

import app.solstone.core.model.SourceKind
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.LOCATION_STREAM
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.CountingSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import java.io.ByteArrayInputStream
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationRecordsTest {
    @Test
    fun buildLocationRecordEmitsDeterministicJsonlKeys() {
        val record = buildLocationRecord(
            LocationFix(
                provider = "network",
                timestampEpochMs = 1_000L,
                lat = 39.7392,
                lon = -104.9903,
                accuracyMeters = 12.5,
                fixAgeMs = 60_000L,
            ),
        )

        assertEquals(
            "{\"provider\":\"network\",\"timestamp\":1000,\"lat\":39.7392,\"lon\":-104.9903,\"accuracy\":12.5,\"fixAge\":60000}\n",
            record,
        )
    }

    @Test
    fun decideGapUsesLocationGapWithoutPosition() {
        NoFixReason.entries.forEach { reason ->
            val gap = decideGap(reason, atEpochMs = 2_000L)

            assertEquals("location_gap", gap.kind)
            assertEquals(reason.detail, gap.detail)
            assertFalse(gap.toString().contains("lat"))
            assertFalse(gap.toString().contains("lon"))
        }
    }

    @Test
    fun allGapLocationWindowSealsAsZeroFileLocationSegment() {
        val segmenter = Segmenter(ZoneId.of("UTC"))
        val gap = decideGap(NoFixReason.NO_FIX, BASE_EPOCH_MS + 300_000L)

        segmenter.feed(
            SourceEmission(
                sourceId = LocationContinuousSourceEngine.SOURCE_ID,
                stream = LOCATION_STREAM,
                sourceKind = SourceKind.OBSERVER,
                captureStartEpochMs = BASE_EPOCH_MS,
                captureEndEpochMs = BASE_EPOCH_MS + 300_000L,
                payloadRefs = emptyList(),
                metadata = emptyMap(),
                gaps = listOf(gap),
            ),
        )
        val sealed = segmenter.flush().single()
        val writer = CountingSpoolWriter()
        writer.seal(sealed, PayloadBytesProvider { ByteArrayInputStream(ByteArray(it.ref.byteSize.toInt())) })

        assertEquals(LOCATION_STREAM, sealed.stream)
        assertTrue(sealed.payloads.isEmpty())
        assertEquals(listOf(gap), sealed.gaps)
        assertEquals(0L, writer.bytesWritten)
        assertTrue(writer.manifests.single().files.isEmpty())
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
    }
}
