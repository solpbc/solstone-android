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
import kotlin.test.assertIs

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
                    true,
                    null,
                ),
                ServerSegment("094000_60", listOf(ServerFile("audio.wav", 3, SHA_A, "present", null)), false, null),
            ),
            segments,
        )
    }

    @Test
    fun diffDoesNotNeedUploadForPresentOrProcessedFiles() {
        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), false)),
            SegmentReconciler(
                RecordingPlHttpClient(response(segmentJson("093000_60", fileJson("audio.wav", SHA_A.uppercase())))),
            ).diff(listOf(manifest("093000_60", "audio.wav" to SHA_A)), "20260616"),
        )
        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), false)),
            SegmentReconciler(
                RecordingPlHttpClient(
                    response(segmentJson("093000_60", fileJson("audio.wav", SHA_A, status = "processed"))),
                ),
            ).diff(listOf(manifest("093000_60", "audio.wav" to SHA_A)), "20260616"),
        )
    }

    @Test
    fun diffDoesNotNeedUploadForExactNameOrSubmittedName() {
        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), false)),
            SegmentReconciler(
                RecordingPlHttpClient(response(segmentJson("093000_60", fileJson("source.wav", SHA_A)))),
            ).diff(listOf(manifest("093000_60", "source.wav" to SHA_A)), "20260616"),
        )
        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), false)),
            SegmentReconciler(
                RecordingPlHttpClient(
                    response(segmentJson("093000_60", fileJson("stored.wav", SHA_A, submittedName = "source.wav"))),
                ),
            ).diff(listOf(manifest("093000_60", "source.wav" to SHA_A)), "20260616"),
        )
    }

    @Test
    fun diffNeedsUploadWhenOneHeldFactDoesNotMatch() {
        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), true)),
            SegmentReconciler(
                RecordingPlHttpClient(
                    response(segmentJson("093000_60", fileJson("audio.wav", SHA_A, submittedName = "other.wav"))),
                ),
            ).diff(listOf(manifest("093000_60", "audio.wav" to SHA_A)), "20260616"),
        )
        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), true)),
            SegmentReconciler(
                RecordingPlHttpClient(response(segmentJson("093000_60", fileJson("audio.wav", SHA_A, size = 4)))),
            ).diff(listOf(manifest("093000_60", "audio.wav" to SHA_A)), "20260616"),
        )
        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), true)),
            SegmentReconciler(
                RecordingPlHttpClient(response(segmentJson("093000_60", fileJson("audio.wav", SHA_B)))),
            ).diff(listOf(manifest("093000_60", "audio.wav" to SHA_A)), "20260616"),
        )
        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), true)),
            SegmentReconciler(
                RecordingPlHttpClient(
                    response(segmentJson("093000_60", fileJson("audio.wav", SHA_A, status = "missing"))),
                ),
            ).diff(listOf(manifest("093000_60", "audio.wav" to SHA_A)), "20260616"),
        )
    }

    @Test
    fun diffUsesOriginalKeyWhenCanonicalKeyIsAbsent() {
        val http = RecordingPlHttpClient(
            response(
                segmentJson("server-key", fileJson("audio.wav", SHA_A), originalKey = "093000_60"),
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
                segmentJson("093000_60"),
                segmentJson("other", fileJson("audio.wav", SHA_A), originalKey = "093000_60"),
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
            """{"key":"","observed":true,"files":[]}""",
            """{"key":"seg","observed":true,"files":[{"name":"a","size":1.5,"sha256":"$SHA_A","status":"present"}]}""",
            """{"key":"seg","observed":true,"files":[{"name":"a","size":1,"sha256":"short","status":"present"}]}""",
            """{"key":"seg","observed":true,"files":[{"name":"a","size":1,"sha256":"$SHA_A","status":"stored"}]}""",
            """{"key":"seg","observed":true,"files":[{"name":"a","size":-1,"sha256":"$SHA_A","status":"present"}]}""",
            """{"key":"seg","observed":true,"files":[{"name":"a","size":9007199254740992,"sha256":"$SHA_A","status":"present"}]}""",
            """{"key":"seg","observed":true,"files":[{"name":"a","size":9223372036854775808,"sha256":"$SHA_A","status":"present"}]}""",
        ).forEach { item ->
            assertFailsWith<ReconcileUnavailableException> {
                SegmentReconciler(RecordingPlHttpClient(response(item))).fetch("20260616")
            }
        }
    }

    @Test
    fun fetchRejectsDuplicateKeysAndOriginalKeys() {
        listOf(
            response(
                """{"key":"seg","observed":true,"files":[${fileJson("audio.wav", SHA_A)}]}""",
                """{"key":"seg","observed":true,"files":[${fileJson("photo.jpg", SHA_B)}]}""",
            ),
            response(
                """{"key":"server-a","original_key":"local","observed":true,"files":[${fileJson("audio.wav", SHA_A)}]}""",
                """{"key":"server-b","original_key":"local","observed":true,"files":[${fileJson("photo.jpg", SHA_B)}]}""",
            ),
        ).forEach { response ->
            assertFailsWith<ReconcileUnavailableException> {
                SegmentReconciler(RecordingPlHttpClient(response)).fetch("20260616")
            }
        }
    }

    @Test
    fun fetchRejectsMissingOrNonBooleanObserved() {
        listOf(
            """{"key":"seg","files":[]}""",
            """{"key":"seg","observed":"true","files":[]}""",
            """{"key":"seg","observed":1,"files":[]}""",
            """{"key":"seg","observed":null,"files":[]}""",
        ).forEach { item ->
            val error = assertFailsWith<ReconcileUnavailableException> {
                SegmentReconciler(RecordingPlHttpClient(response(item))).fetch("20260616")
            }
            assertEquals(200, error.status)
            assertIs<IllegalArgumentException>(error.cause)
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
        segmentJson("093000_60", fileJson("audio.wav", SHA_A), fileJson("photo.jpg", SHA_B)),
        segmentJson("094000_60", fileJson("audio.wav", SHA_A), observed = false),
    )

    private fun response(vararg items: String): HttpResponse = HttpResponse(
        200,
        emptyMap(),
        """{"items":[${items.joinToString(",")}],"total":${items.size},"protocol_version":3}""".toByteArray(),
    )

    private fun fileJson(
        name: String,
        sha256: String,
        size: Int = 3,
        status: String = "present",
        submittedName: String? = null,
    ): String {
        val submittedNameJson = submittedName?.let { ",\"submitted_name\":\"$it\"" }.orEmpty()
        return """{"name":"$name","size":$size,"sha256":"$sha256","status":"$status"$submittedNameJson}"""
    }

    private fun segmentJson(
        key: String,
        vararg files: String,
        observed: Boolean = true,
        originalKey: String? = null,
    ): String {
        val originalKeyJson = originalKey?.let { ",\"original_key\":\"$it\"" }.orEmpty()
        return """{"key":"$key"$originalKeyJson,"observed":$observed,"files":[${files.joinToString(",")}]}"""
    }

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
