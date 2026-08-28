// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.parseJson
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObserverIngestClientTest {
    @Test
    fun ingestPostsOneSourceBoundEnvelopeAndFilePart() {
        val http = RecordingPlHttpClient(okResponse("server-segment"))
        val client = ObserverIngestClient(http) { "fixed-boundary" }
        val manifest = BundleManifest(
            SegmentKey("20260616", "093000_60"),
            listOf(BundleFile("mic", "audio.wav", "sha-audio", 3, "audio/wav", 1, 2)),
            emptyList(),
        )
        val bytes = mapOf("audio.wav" to byteArrayOf(1, 2, 3))

        val outcome = client.ingest(
            manifest = manifest,
            fileBytes = { bytes.getValue(it.name) },
            host = "watch-one",
            platform = "rogbid",
        )

        assertIs<IngestOutcome.Accepted>(outcome)
        assertEquals("POST", http.lastRequest.method)
        assertEquals("/app/devices/ingest", http.lastRequest.path)
        assertEquals("3", http.lastRequest.headers[PROTOCOL_VERSION_HEADER])
        assertEquals(setOf("Content-Type", PROTOCOL_VERSION_HEADER), http.lastRequest.headers.keys)
        val parts = parseMultipart(requireNotNull(http.lastRequest.body), "fixed-boundary")
        assertEquals(listOf("envelope", "files"), parts.map { it.name })
        assertEquals("application/json", parts.first().contentType)
        assertNull(parts.first().filename)
        val envelope = parseJson(parts.first().body.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals(setOf("day", "segment", "source", "files", "meta"), envelope.keys)
        assertEquals("20260616", envelope["day"])
        assertEquals("093000_60", envelope["segment"])
        assertEquals("mic", envelope["source"])
        assertEquals(listOf(mapOf("submitted" to "audio.wav")), envelope["files"])
        assertEquals(mapOf("host" to "watch-one", "platform" to "rogbid"), envelope["meta"])
        assertTrue("stream" !in envelope)
        assertTrue("observer" !in envelope)
        assertEquals("audio.wav", parts[1].filename)
        assertEquals("audio/wav", parts[1].contentType)
        assertContentEquals(byteArrayOf(1, 2, 3), parts[1].body)
    }

    @Test
    fun ingestSplitsMixedSourceManifestIntoJournalSourceBoundRequests() {
        val http = RecordingPlHttpClient(okResponse("server-segment"))
        val client = ObserverIngestClient(http) { "boundary-${System.nanoTime()}" }
        val bytes = mapOf(
            "audio.wav" to byteArrayOf(1, 2, 3),
            "photo.jpg" to byteArrayOf(4, 5, 6),
        )

        val outcome = client.ingest(twoFileManifest(), { bytes.getValue(it.name) })

        assertIs<IngestOutcome.Accepted>(outcome)
        assertEquals(2, http.requests.size)
        val envelopes = http.requests.map { request ->
            val boundary = requireNotNull(request.headers["Content-Type"])
                .substringAfter("boundary=")
            val parts = parseMultipart(requireNotNull(request.body), boundary)
            parseJson(parts.first().body.toString(Charsets.UTF_8)) as Map<*, *>
        }
        assertEquals(setOf("mic", "camera"), envelopes.map { it["source"] }.toSet())
        assertEquals(
            setOf("audio.wav", "photo.jpg"),
            envelopes.flatMap { envelope ->
                (envelope["files"] as List<*>).map { (it as Map<*, *>)["submitted"] }
            }.toSet(),
        )
    }

    @Test
    fun envelopeOmitsOptionalSourceAndMeta() {
        val http = RecordingPlHttpClient(okResponse("server-segment"))
        val manifest = BundleManifest(
            SegmentKey("20260616", "093000_60"),
            listOf(BundleFile("", "audio.wav", "sha", 1, "audio/wav", 0, 0)),
            emptyList(),
        )

        ObserverIngestClient(http) { "fixed-boundary" }.ingest(manifest, { byteArrayOf(7) })

        val envelope = parseJson(
            parseMultipart(requireNotNull(http.lastRequest.body), "fixed-boundary").first().body.toString(Charsets.UTF_8),
        ) as Map<*, *>
        assertEquals(setOf("day", "segment", "files"), envelope.keys)
        assertEquals(listOf(mapOf("submitted" to "audio.wav")), envelope["files"])
    }

    @Test
    fun multipartRoundTripsQuotedAndBackslashFilenameWithExactPayloadBytes() {
        val http = RecordingPlHttpClient(okResponse("server-segment"))
        val filename = "quote\"slash\\.wav"
        val payload = byteArrayOf(0, 1, 2, -1)
        val manifest = BundleManifest(
            SegmentKey("20260616", "093000_60"),
            listOf(BundleFile("mic", filename, "sha", payload.size.toLong(), "audio/wav", 0, 0)),
            emptyList(),
        )

        ObserverIngestClient(http) { "fixed-boundary" }.ingest(manifest, { payload })

        val part = parseMultipart(requireNotNull(http.lastRequest.body), "fixed-boundary")[1]
        assertEquals(filename, part.filename)
        assertContentEquals(payload, part.body)
    }

    @Test
    fun ingestRejectsControlCharacterFilename() {
        listOf("bad\nname.wav", "bad\u0085name.wav").forEach { filename ->
            val http = RecordingPlHttpClient(okResponse("server-segment"))
            val manifest = BundleManifest(
                SegmentKey("20260616", "093000_60"),
                listOf(BundleFile("mic", filename, "sha", 1, "audio/wav", 0, 0)),
                emptyList(),
            )

            assertFailsWith<IllegalArgumentException> {
                ObserverIngestClient(http) { "fixed-boundary" }.ingest(manifest, { byteArrayOf(1) })
            }
        }
    }

    @Test
    fun ingestMapsTyped200OutcomesWithoutThrowing() {
        val http = RecordingPlHttpClient(okResponse("server-segment"))
        val client = ObserverIngestClient(http) { "fixed-boundary" }
        val manifest = twoFileManifest()
        val fileBytes: (BundleFile) -> ByteArray = { it.name.toByteArray() }

        assertEquals(IngestOutcome.Accepted("server-segment"), client.ingest(manifest, fileBytes))

        http.response = HttpResponse(200, emptyMap(), """{"status":"collision","segment":"adjusted-segment"}""".toByteArray())
        assertEquals(IngestOutcome.Collision("adjusted-segment"), client.ingest(manifest, fileBytes))

        http.response = HttpResponse(200, emptyMap(), """{"status":"duplicate","existing_segment":"existing-segment"}""".toByteArray())
        assertEquals(IngestOutcome.Duplicate("existing-segment"), client.ingest(manifest, fileBytes))

        http.response = HttpResponse(200, emptyMap(), """{"status":"failed"}""".toByteArray())
        assertEquals(IngestOutcome.Failed(null), client.ingest(manifest, fileBytes))

        http.response = HttpResponse(200, emptyMap(), """{"status":"future"}""".toByteArray())
        assertEquals(IngestOutcome.UnknownStatus("future"), client.ingest(manifest, fileBytes))

        http.response = HttpResponse(200, emptyMap(), "not json".toByteArray())
        assertEquals(IngestOutcome.MalformedResponse("invalid_json"), client.ingest(manifest, fileBytes))

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok"}""".toByteArray())
        assertEquals(IngestOutcome.MalformedResponse("missing_segment"), client.ingest(manifest, fileBytes))

        http.response = HttpResponse(401, emptyMap(), "unauthorized".toByteArray())
        val rejected = assertIs<IngestOutcome.Rejected>(client.ingest(manifest, fileBytes))
        assertEquals(401, rejected.status)
        assertEquals("unauthorized", rejected.body)
    }

    private fun okResponse(segment: String): HttpResponse =
        HttpResponse(200, emptyMap(), """{"status":"ok","segment":"$segment"}""".toByteArray())

    private fun twoFileManifest(): BundleManifest = BundleManifest(
        key = SegmentKey(day = "20260616", segment = "093000_60"),
        files = listOf(
            BundleFile("mic", "audio.wav", "sha-audio", 3, "audio/wav", 1, 2),
            BundleFile("camera", "photo.jpg", "sha-photo", 3, "image/jpeg", 3, 4),
        ),
        gaps = emptyList(),
    )
}
