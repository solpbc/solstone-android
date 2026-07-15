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
    private val testHandle = "obs-test-handle"

    @Test
    fun fetchSendsProtocolHeaderAndParsesEnvelope() {
        val http = RecordingPlHttpClient(envelopeResponse())
        val segments = SegmentReconciler(http, testHandle).fetch("20260616")

        assertEquals("GET", http.lastRequest.method)
        assertEquals("$SEGMENTS_PATH/20260616", http.lastRequest.path)
        assertEquals("2", http.lastRequest.headers[PROTOCOL_VERSION_HEADER])
        assertEquals(testHandle, http.lastRequest.headers[OBSERVER_HANDLE_HEADER])
        assertEquals(
            listOf(
                ServerSegment(
                    "093000_60",
                    listOf(
                        ServerFile("audio.wav", "sha-audio", "present", null),
                        ServerFile("photo.jpg", "sha-photo", "present", null),
                    ),
                ),
                ServerSegment("094000_60", listOf(ServerFile("audio.wav", "sha-audio", "present", null))),
                ServerSegment("095000_60", listOf(ServerFile("audio.wav", "sha-audio", "present", null))),
            ),
            segments,
        )
    }

    @Test
    fun diffMarksOnlyExactNameAndShaMatchPresent() {
        val http = RecordingPlHttpClient(envelopeResponse())
        val verdicts = SegmentReconciler(http, testHandle).diff(
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

        assertFailsWith<ReconcileUnavailableException> {
            SegmentReconciler(http, testHandle).fetch("20260616")
        }
    }

    @Test
    fun diffRejectsBareV1Array() {
        val http = RecordingPlHttpClient(HttpResponse(200, emptyMap(), """[{"key":"093000_60","files":[]}]""".toByteArray()))

        assertFailsWith<ReconcileUnavailableException> {
            SegmentReconciler(http, testHandle).diff(listOf(manifest("093000_60", "audio.wav" to "sha-audio")), "20260616")
        }
    }

    @Test
    fun fetchAuthStatusThrowsAuthException() {
        val unauthorized = RecordingPlHttpClient(HttpResponse(401, emptyMap(), "unauthorized".toByteArray()))
        val forbidden = RecordingPlHttpClient(HttpResponse(403, emptyMap(), "forbidden".toByteArray()))

        assertEquals(
            401,
            assertFailsWith<ReconcileAuthException> {
                SegmentReconciler(unauthorized, testHandle).fetch("20260616")
            }.status,
        )
        assertEquals(
            403,
            assertFailsWith<ReconcileAuthException> {
                SegmentReconciler(forbidden, testHandle).fetch("20260616")
            }.status,
        )
    }

    @Test
    fun fetchUnavailableStatusThrowsUnavailableException() {
        val http = RecordingPlHttpClient(HttpResponse(500, emptyMap(), "nope".toByteArray()))

        assertEquals(
            500,
            assertFailsWith<ReconcileUnavailableException> {
                SegmentReconciler(http, testHandle).fetch("20260616")
            }.status,
        )
    }

    @Test
    fun diffRequiresEveryLocalFileToBeProvenHeld() {
        val http = RecordingPlHttpClient(
            responseForFiles(
                """{"name":"audio.wav","sha256":"sha-audio","status":"present"}""",
                """{"name":"photo.jpg","sha256":"sha-photo","status":"missing"}""",
            ),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "sha-audio", "photo.jpg" to "sha-photo")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = true)),
            verdicts,
        )
    }

    @Test
    fun diffAcceptsAllPresentLocalFiles() {
        val http = RecordingPlHttpClient(
            responseForFiles(
                """{"name":"audio.wav","sha256":"sha-audio","status":"present"}""",
                """{"name":"photo.jpg","sha256":"sha-photo","status":"present"}""",
            ),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "sha-audio", "photo.jpg" to "sha-photo")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = false)),
            verdicts,
        )
    }

    @Test
    fun diffRejectsRetiredRelocatedStatus() {
        val http = RecordingPlHttpClient(
            responseForFiles("""{"name":"audio.wav","sha256":"sha-audio","status":"relocated"}"""),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "sha-audio")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = true)),
            verdicts,
        )
    }

    @Test
    fun processedProvesHeld() {
        val http = RecordingPlHttpClient(
            responseForFiles("""{"name":"audio.wav","sha256":"sha-audio","status":"processed"}"""),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "sha-audio")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = false)),
            verdicts,
        )
    }

    @Test
    fun processedWithShaMismatchNeedsUpload() {
        val http = RecordingPlHttpClient(
            responseForFiles("""{"name":"audio.wav","sha256":"sha-audio","status":"processed"}"""),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "sha-DIFFERENT")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = true)),
            verdicts,
        )
    }

    @Test
    fun mixedPresentAndProcessedFilesProveHeld() {
        val http = RecordingPlHttpClient(
            responseForFiles(
                """{"name":"video.mp4","sha256":"sha-video","status":"present"}""",
                """{"name":"audio.wav","sha256":"sha-audio","status":"processed"}""",
            ),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "video.mp4" to "sha-video", "audio.wav" to "sha-audio")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = false)),
            verdicts,
        )
    }

    @Test
    fun processedWithEmptyShaNeedsUpload() {
        val http = RecordingPlHttpClient(
            responseForFiles("""{"name":"audio.wav","sha256":"","status":"processed"}"""),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = true)),
            verdicts,
        )
    }

    @Test
    fun diffRejectsMatchingFileWithoutStatus() {
        val http = RecordingPlHttpClient(
            responseForFiles("""{"name":"audio.wav","sha256":"sha-audio"}"""),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "sha-audio")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = true)),
            verdicts,
        )
    }

    @Test
    fun diffRejectsUnrecognizedStoredStatus() {
        val http = RecordingPlHttpClient(
            responseForFiles("""{"name":"audio.wav","sha256":"sha-audio","status":"stored"}"""),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "sha-audio")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = true)),
            verdicts,
        )
    }

    @Test
    fun diffIgnoresExtraRemoteOnlyFiles() {
        val http = RecordingPlHttpClient(
            responseForFiles(
                """{"name":"audio.wav","sha256":"sha-audio","status":"present"}""",
                """{"name":"photo.jpg","sha256":"sha-photo","status":"present"}""",
            ),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "sha-audio")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = false)),
            verdicts,
        )
    }

    @Test
    fun diffUsesSubmittedNameWhenServerNameDiffers() {
        val http = RecordingPlHttpClient(
            responseForFiles(
                """{"name":"remote-audio.wav","submitted_name":"audio.wav","sha256":"sha-audio","status":"present"}""",
            ),
        )

        val verdicts = SegmentReconciler(http, testHandle).diff(
            listOf(manifest("093000_60", "audio.wav" to "sha-audio")),
            "20260616",
        )

        assertEquals(
            listOf(ReconcileVerdict(SegmentKey("20260616", "093000_60"), needsUpload = false)),
            verdicts,
        )
    }

    private fun envelopeResponse(): HttpResponse = HttpResponse(
        200,
        emptyMap(),
        """
        {
          "items":[
            {"key":"093000_60","files":[
              {"name":"audio.wav","size":3,"sha256":"sha-audio","status":"present"},
              {"name":"photo.jpg","size":3,"sha256":"sha-photo","status":"present"}
            ]},
            {"key":"094000_60","files":[
              {"name":"audio.wav","size":3,"sha256":"sha-audio","status":"present"}
            ]},
            {"key":"095000_60","files":[
              {"name":"audio.wav","size":3,"sha256":"sha-audio","status":"present"}
            ]}
          ],
          "total":3,
          "protocol_version":2
        }
        """.trimIndent().toByteArray(),
    )

    private fun responseForFiles(vararg files: String): HttpResponse = HttpResponse(
        200,
        emptyMap(),
        """
        {
          "items":[
            {"key":"093000_60","files":[${files.joinToString(",")}]}
          ],
          "total":1,
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
