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
import kotlin.test.assertTrue

class SegmentReconcilerTest {
    @Test
    fun fetchSendsV3HeaderAndParsesEnvelope() {
        val http = RecordingPlHttpClient(envelopeResponse())

        val segments = SegmentReconciler(http).fetch("20260616")

        assertEquals("GET", http.lastRequest.method)
        assertEquals("/app/devices/ingest/segments/20260616", http.lastRequest.path)
        assertEquals("3", http.lastRequest.headers[PROTOCOL_VERSION_HEADER])
        assertEquals(setOf(PROTOCOL_VERSION_HEADER), http.lastRequest.headers.keys)
        assertEquals(
            listOf(
                ServerSegment(
                    "093000_60",
                    listOf(
                        ServerFile("audio.wav", 3, SHA_A, "present", null),
                        ServerFile("photo.jpg", 3, SHA_B, "present", null),
                    ),
                    null,
                ),
                ServerSegment("094000_60", listOf(ServerFile("audio.wav", 3, SHA_A, "present", null)), null),
            ),
            segments,
        )
    }

    @Test
    fun diffRequiresExactSubmittedNameSizeShaAndHeldStatus() {
        val http = RecordingPlHttpClient(
            response(
                """{"key":"093000_60","files":[
                {"name":"renamed.wav","submitted_name":"audio.wav","size":3,"sha256":"${SHA_A.uppercase()}","status":"present"},
                {"name":"photo.jpg","size":4,"sha256":"$SHA_B","status":"missing"}
                ]}""",
            ),
        )

        val verdict = SegmentReconciler(http).diff(
            listOf(manifest("093000_60", "audio.wav" to SHA_A, "photo.jpg" to SHA_B)),
            "20260616",
        )

        assertEquals(listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), true)), verdict)
    }

    @Test
    fun diffUsesOriginalKeyWhenCanonicalKeyIsAbsent() {
        val http = RecordingPlHttpClient(
            response(
                """{"key":"server-key","original_key":"093000_60","files":[${fileJson("audio.wav", SHA_A)}]}""",
            ),
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), false)),
            SegmentReconciler(http).diff(listOf(manifest("093000_60", "audio.wav" to SHA_A)), "20260616"),
        )
    }

    @Test
    fun diffPrefersCanonicalKeyOverOriginalKey() {
        val http = RecordingPlHttpClient(
            response(
                """{"key":"093000_60","files":[]}""",
                """{"key":"other","original_key":"093000_60","files":[${fileJson("audio.wav", SHA_A)}]}""",
            ),
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), true)),
            SegmentReconciler(http).diff(listOf(manifest("093000_60", "audio.wav" to SHA_A)), "20260616"),
        )
    }

    @Test
    fun fetchRejectsMissingOrWrongProtocolVersionAndMismatchedTotal() {
        listOf(
            """{"items":[],"total":0}""",
            """{"items":[],"total":0,"protocol_version":2}""",
            """{"items":[],"total":1,"protocol_version":3}""",
        ).forEach { body ->
            assertFailsWith<ReconcileUnavailableException> {
                SegmentReconciler(RecordingPlHttpClient(HttpResponse(200, emptyMap(), body.toByteArray()))).fetch("20260616")
            }
        }
    }

    @Test
    fun fetchRejectsInvalidItemAndFileFields() {
        listOf(
            """{"key":"","files":[]}""",
            """{"key":"seg","files":[{"name":"a","size":1.5,"sha256":"$SHA_A","status":"present"}]}""",
            """{"key":"seg","files":[{"name":"a","size":1,"sha256":"short","status":"present"}]}""",
            """{"key":"seg","files":[{"name":"a","size":1,"sha256":"$SHA_A","status":"stored"}]}""",
            """{"key":"seg","files":[{"name":"a","size":-1,"sha256":"$SHA_A","status":"present"}]}""",
        ).forEach { item ->
            assertFailsWith<ReconcileUnavailableException> {
                SegmentReconciler(RecordingPlHttpClient(response(item))).fetch("20260616")
            }
        }
    }

    @Test
    fun fetchAuthAndUnavailableStatusesRemainTyped() {
        assertEquals(
            401,
            assertFailsWith<ReconcileAuthException> {
                SegmentReconciler(RecordingPlHttpClient(HttpResponse(401, emptyMap(), ByteArray(0)))).fetch("20260616")
            }.status,
        )
        assertEquals(
            503,
            assertFailsWith<ReconcileUnavailableException> {
                SegmentReconciler(RecordingPlHttpClient(HttpResponse(503, emptyMap(), ByteArray(0)))).fetch("20260616")
            }.status,
        )
    }

    private fun envelopeResponse(): HttpResponse = response(
        """{"key":"093000_60","files":[${fileJson("audio.wav", SHA_A)},${fileJson("photo.jpg", SHA_B)}]}""",
        """{"key":"094000_60","files":[${fileJson("audio.wav", SHA_A)}]}""",
    )

    private fun response(vararg items: String): HttpResponse = HttpResponse(
        200,
        emptyMap(),
        """{"items":[${items.joinToString(",")}],"total":${items.size},"protocol_version":3}""".toByteArray(),
    )

    private fun fileJson(name: String, sha256: String): String =
        """{"name":"$name","size":3,"sha256":"$sha256","status":"present"}"""

    private fun manifest(segment: String, vararg files: Pair<String, String>): BundleManifest = BundleManifest(
        key = SegmentKey(day = "20260616", segment = segment),
        files = files.mapIndexed { index, (name, sha256) ->
            BundleFile("source-$index", name, sha256, 3, "application/octet-stream", 1, 2)
        },
        gaps = emptyList(),
    )

    private companion object {
        val SHA_A = "a".repeat(64)
        val SHA_B = "b".repeat(64)
    }
}
