// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.GapEvent
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestSerializationTest {
    @Test
    fun serializesManifestWithPinnedEscapes() {
        val segment = SealedSegment(
            stream = "audio",
            key = SegmentKey("20260616", "120000_300"),
            wireKeys = WireKeys(
                day = "20260616",
                segment = "120000_300",
                startEpochMs = 1_800,
                endEpochMs = 2_100,
                zoneId = "America/Denver café+\tΩ",
                utcOffsetSeconds = -21_600,
            ),
            payloads = emptyList(),
            gaps = emptyList(),
        )
        val manifest = BundleManifest(
            key = segment.key,
            files = listOf(
                BundleFile(
                    sourceId = "source +\tΩ",
                    name = "clip café + one.bin",
                    sha256 = "abc123",
                    byteSize = 42,
                    mediaType = "audio/naïve+\ttype",
                    captureStartEpochMs = 1_800,
                    captureEndEpochMs = 1_900,
                ),
            ),
            gaps = listOf(
                GapEvent(
                    kind = "gap +\tΩ",
                    atEpochMs = 1_850,
                    detail = "detail space + tab\tcafé",
                ),
            ),
        )

        val serialized = serializeManifest(segment, manifest)

        val expected = "solstone-bundle-manifest-v1\n" +
            "day=20260616\n" +
            "segment=120000_300\n" +
            "startEpochMs=1800\n" +
            "endEpochMs=2100\n" +
            "zoneId=America%2FDenver%20caf%C3%A9%2B%09%CE%A9\n" +
            "utcOffsetSeconds=-21600\n" +
            "[files]\n" +
            "source%20%2B%09%CE%A9\tclip%20caf%C3%A9%20%2B%20one.bin\tabc123\t42\taudio%2Fna%C3%AFve%2B%09type\t1800\t1900\n" +
            "[gaps]\n" +
            "gap%20%2B%09%CE%A9\t1850\tdetail%20space%20%2B%20tab%09caf%C3%A9\n"
        assertEquals(expected, serialized)
        assertTrue(serialized.contains("%20"))
        assertTrue(serialized.contains("%2B"))
    }
}
