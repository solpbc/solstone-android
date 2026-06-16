// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.pl.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ObserverIngestClientTest {
    @Test
    fun ingestPostsMultipartWithProtocolHeadersAndCleanFileNames() {
        val http = RecordingPlHttpClient(okResponse("server-segment"))
        val client = ObserverIngestClient(http) { "fixed-boundary" }
        val manifest = twoFileManifest()
        val bytes = mapOf(
            "audio.wav" to byteArrayOf(1, 2, 3),
            "photo.jpg" to byteArrayOf(4, 5, 6),
        )

        val outcome = client.ingest(
            manifest = manifest,
            handle = "observer-handle",
            fileBytes = { bytes.getValue(it.name) },
            host = "watch-one",
            platform = "rogbid",
        )

        assertIs<IngestOutcome.Accepted>(outcome)
        assertEquals("POST", http.lastRequest.method)
        assertEquals(INGEST_PATH, http.lastRequest.path)
        assertEquals("observer-handle", http.lastRequest.headers[OBSERVER_HANDLE_HEADER])
        assertEquals("2", http.lastRequest.headers[PROTOCOL_VERSION_HEADER])
        assertTrue(http.lastRequest.headers.getValue("Content-Type").startsWith("multipart/form-data; boundary="))
        assertContentEquals(
            expectedMultipartBody(),
            http.lastRequest.body,
        )

        val body = http.lastRequest.body!!.toString(Charsets.UTF_8)
        assertTrue(body.contains("Content-Disposition: form-data; name=\"files\"; filename=\"audio.wav\""))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"files\"; filename=\"photo.jpg\""))
        assertTrue(!body.contains("filename=\"093000_60_audio.wav\""))
        assertTrue(!body.contains("filename=\"093000_60_photo.jpg\""))
        assertEquals(1, body.occurrences("name=\"segment\""))
        assertEquals(1, body.occurrences("name=\"day\""))
        assertTrue(!body.contains("source_id"))
    }

    @Test
    fun ingestMapsOkCollisionDuplicateAndRejectedResponses() {
        val http = RecordingPlHttpClient(okResponse("server-segment"))
        val client = ObserverIngestClient(http) { "fixed-boundary" }
        val manifest = twoFileManifest()
        val fileBytes: (BundleFile) -> ByteArray = { it.name.toByteArray() }

        assertEquals(IngestOutcome.Accepted("server-segment"), client.ingest(manifest, "handle", fileBytes))

        http.response = HttpResponse(200, emptyMap(), """{"status":"collision","segment":"adjusted-segment"}""".toByteArray())
        assertEquals(IngestOutcome.Collision("adjusted-segment"), client.ingest(manifest, "handle", fileBytes))

        http.response = HttpResponse(200, emptyMap(), """{"status":"duplicate","existing_segment":"existing-segment"}""".toByteArray())
        assertEquals(IngestOutcome.Duplicate("existing-segment"), client.ingest(manifest, "handle", fileBytes))

        http.response = HttpResponse(401, emptyMap(), "unauthorized".toByteArray())
        val rejected = assertIs<IngestOutcome.Rejected>(client.ingest(manifest, "handle", fileBytes))
        assertEquals(401, rejected.status)
        assertEquals("unauthorized", rejected.body)
    }

    private fun okResponse(segment: String): HttpResponse =
        HttpResponse(200, emptyMap(), """{"status":"ok","segment":"$segment"}""".toByteArray())

    private fun expectedMultipartBody(): ByteArray =
        ascii(
            "--fixed-boundary\r\n" +
                "Content-Disposition: form-data; name=\"segment\"\r\n" +
                "\r\n" +
                "093000_60\r\n" +
                "--fixed-boundary\r\n" +
                "Content-Disposition: form-data; name=\"day\"\r\n" +
                "\r\n" +
                "20260616\r\n" +
                "--fixed-boundary\r\n" +
                "Content-Disposition: form-data; name=\"files\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n" +
                "\r\n",
        ) +
            byteArrayOf(1, 2, 3) +
            ascii(
                "\r\n" +
                    "--fixed-boundary\r\n" +
                    "Content-Disposition: form-data; name=\"files\"; filename=\"photo.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "\r\n",
            ) +
            byteArrayOf(4, 5, 6) +
            ascii(
                "\r\n" +
                    "--fixed-boundary\r\n" +
                    "Content-Disposition: form-data; name=\"host\"\r\n" +
                    "\r\n" +
                    "watch-one\r\n" +
                    "--fixed-boundary\r\n" +
                    "Content-Disposition: form-data; name=\"platform\"\r\n" +
                    "\r\n" +
                    "rogbid\r\n" +
                    "--fixed-boundary--\r\n",
            )

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun twoFileManifest(): BundleManifest = BundleManifest(
        key = SegmentKey(day = "20260616", segment = "093000_60"),
        files = listOf(
            BundleFile(
                sourceId = "mic",
                name = "audio.wav",
                sha256 = "sha-audio",
                byteSize = 3,
                mediaType = "audio/wav",
                captureStartEpochMs = 1,
                captureEndEpochMs = 2,
            ),
            BundleFile(
                sourceId = "camera",
                name = "photo.jpg",
                sha256 = "sha-photo",
                byteSize = 3,
                mediaType = "image/jpeg",
                captureStartEpochMs = 3,
                captureEndEpochMs = 4,
            ),
        ),
        gaps = emptyList(),
    )

    private fun String.occurrences(needle: String): Int =
        split(needle).size - 1
}
