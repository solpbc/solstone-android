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

class ManifestParseTest {
    @Test
    fun parsesSerializedManifestRoundTrip() {
        val segment = SealedSegment(
            stream = "cam stream",
            key = SegmentKey("20260616", "120000_300"),
            wireKeys = WireKeys(
                day = "20260616",
                segment = "120000_300",
                startEpochMs = 1_800,
                endEpochMs = 2_100,
                zoneId = "America/Denver",
                utcOffsetSeconds = -21_600,
            ),
            payloads = emptyList(),
            gaps = emptyList(),
        )
        val manifest = BundleManifest(
            key = segment.key,
            files = listOf(
                BundleFile(
                    sourceId = "source A",
                    name = "clip one + tab%09snowman\u2603.bin",
                    sha256 = "abc123",
                    byteSize = 42,
                    mediaType = "application/octet-stream",
                    captureStartEpochMs = 1_800,
                    captureEndEpochMs = 1_900,
                ),
                BundleFile(
                    sourceId = "source\tB",
                    name = "audio two.wav",
                    sha256 = "def456",
                    byteSize = 84,
                    mediaType = "audio/wav",
                    captureStartEpochMs = 1_900,
                    captureEndEpochMs = 2_100,
                ),
            ),
            gaps = listOf(
                GapEvent("permission revoked", 1_850, "detail with spaces + plus\tand tab"),
                GapEvent("empty-detail", 1_860, null),
            ),
        )

        val parsed = parseManifest(serializeManifest(segment, manifest))

        assertEquals(manifest.key, parsed.manifest.key)
        assertEquals(
            manifest.files.sortedWith(compareBy<BundleFile> { it.sourceId }.thenBy { it.name }.thenBy { it.captureStartEpochMs }.thenBy { it.captureEndEpochMs }),
            parsed.manifest.files,
        )
        assertEquals(
            manifest.gaps.sortedWith(compareBy<GapEvent> { it.atEpochMs }.thenBy { it.kind }.thenBy { it.detail ?: "" }),
            parsed.manifest.gaps,
        )
        assertEquals(segment.wireKeys.startEpochMs, parsed.startEpochMs)
        assertEquals(segment.wireKeys.endEpochMs, parsed.endEpochMs)
        assertEquals(segment.wireKeys.zoneId, parsed.zoneId)
        assertEquals(segment.wireKeys.utcOffsetSeconds, parsed.utcOffsetSeconds)
    }
}
