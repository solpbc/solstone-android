// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.pl.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SegmentReconcilerTest {
    @Test
    fun fetchSendsProtocolHeaderAndParsesEnvelope() {
        val http = RecordingPlHttpClient(envelopeResponse())
        val segments = SegmentReconciler(http).fetch("20260616")

        assertEquals("GET", http.lastRequest.method)
        assertEquals("$SEGMENTS_PATH/20260616", http.lastRequest.path)
        assertEquals("2", http.lastRequest.headers[PROTOCOL_VERSION_HEADER])
        assertEquals(
            listOf(
                ServerSegment("093000_60", mapOf("audio.wav" to "sha-audio", "photo.jpg" to "sha-photo")),
                ServerSegment("094000_60", mapOf("audio.wav" to "sha-audio")),
                ServerSegment("095000_60", mapOf("audio.wav" to "sha-audio")),
            ),
            segments,
        )
    }

    @Test
    fun diffMarksOnlyExactNameAndShaMatchPresent() {
        val http = RecordingPlHttpClient(envelopeResponse())
        val verdicts = SegmentReconciler(http).diff(
            localManifests = listOf(
                manifest("093000_60", "audio.wav" to "sha-audio", "photo.jpg" to "sha-photo"),
                manifest("094000_60", "audio.wav" to "sha-different"),
                manifest("095000_60", "renamed.wav" to "sha-audio"),
                manifest("100000_60", "audio.wav" to "sha-audio"),
            ),
            day = "20260616",
        )

        assertEquals(
            listOf(
                ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = false),
                ReconcileVerdict(SegmentKey("20260616", "094000_60"), needsUpload = true),
                ReconcileVerdict(SegmentKey("20260616", "095000_60"), needsUpload = true),
                ReconcileVerdict(SegmentKey("20260616", "100000_60"), needsUpload = true),
            ),
            verdicts,
        )
    }

    @Test
    fun fetchRejectsBareV1Array() {
        val http = RecordingPlHttpClient(HttpResponse(200, emptyMap(), "[]".toByteArray()))

        assertFailsWith<IllegalArgumentException> {
            SegmentReconciler(http).fetch("20260616")
        }
    }

    @Test
    fun diffRejectsBareV1Array() {
        val http = RecordingPlHttpClient(HttpResponse(200, emptyMap(), """[{"key":"093000_60","files":[]}]""".toByteArray()))

        assertFailsWith<IllegalArgumentException> {
            SegmentReconciler(http).diff(listOf(manifest("093000_60", "audio.wav" to "sha-audio")), "20260616")
        }
    }

    private fun envelopeResponse(): HttpResponse = HttpResponse(
        200,
        emptyMap(),
        """
        {
          "items":[
            {"key":"093000_60","files":[
              {"name":"audio.wav","size":3,"sha256":"sha-audio","status":"stored"},
              {"name":"photo.jpg","size":3,"sha256":"sha-photo","status":"stored"}
            ]},
            {"key":"094000_60","files":[
              {"name":"audio.wav","size":3,"sha256":"sha-audio","status":"stored"}
            ]},
            {"key":"095000_60","files":[
              {"name":"audio.wav","size":3,"sha256":"sha-audio","status":"stored"}
            ]}
          ],
          "total":3,
          "protocol_version":2
        }
        """.trimIndent().toByteArray(),
    )

    private fun manifest(segment: String, vararg files: Pair<String, String>): BundleManifest = BundleManifest(
        key = SegmentKey(day = "20260616", segment = segment),
        files = files.mapIndexed { index, (name, sha256) ->
            BundleFile(
                sourceId = "source-$index",
                name = name,
                sha256 = sha256,
                byteSize = 3,
                mediaType = "application/octet-stream",
                captureStartEpochMs = 1,
                captureEndEpochMs = 2,
            )
        },
        gaps = emptyList(),
    )
}
