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

        val outcomes = client.ingest(
            manifest = manifest,
            fileBytes = { bytes.getValue(it.name) },
            host = "watch-one",
            platform = "rogbid",
        )

        assertEquals(1, outcomes.size)
        val outcome = assertIs<IngestOutcome.Accepted>(outcomes.single())
        assertEquals(IngestDescriptors.Absent, outcome.descriptors)
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

        val outcomes = client.ingest(twoFileManifest(), { bytes.getValue(it.name) })

        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it is IngestOutcome.Accepted })
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
    fun nonSuccessOnFirstSourceContinuesToNextSourceUnlessAuthRejected() {
        val http = RecordingPlHttpClient(HttpResponse(500, emptyMap(), "server error".toByteArray()))
        val client = ObserverIngestClient(http) { "fixed-boundary" }
        val manifest = twoFileManifest()
        val fileBytes: (BundleFile) -> ByteArray = { it.name.toByteArray() }

        val outcomes = client.ingest(manifest, fileBytes)
        assertEquals(2, outcomes.size)
        assertEquals(2, http.requests.size)
        assertTrue(outcomes.all { it is IngestOutcome.Rejected && it.status == 500 })

        http.requests.clear()
        http.response = HttpResponse(401, emptyMap(), "unauthorized".toByteArray())
        val authOutcomes = client.ingest(manifest, fileBytes)
        assertEquals(1, authOutcomes.size)
        assertEquals(1, http.requests.size)
        assertEquals(401, assertIs<IngestOutcome.Rejected>(authOutcomes.single()).status)
    }

    @Test
    fun fileDescriptorsParsingCoversAbsentNotAListAndListed() {
        val http = RecordingPlHttpClient(okResponse("server-segment"))
        val client = ObserverIngestClient(http) { "fixed-boundary" }
        val manifest = BundleManifest(
            SegmentKey("20260616", "093000_60"),
            listOf(BundleFile("mic", "audio.wav", "sha-audio", 3, "audio/wav", 1, 2)),
            emptyList(),
        )
        val fileBytes: (BundleFile) -> ByteArray = { byteArrayOf(1, 2, 3) }

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok","segment":"seg","file_descriptors":null}""".toByteArray())
        assertEquals(IngestDescriptors.Absent, (client.ingest(manifest, fileBytes).single() as IngestOutcome.Accepted).descriptors)

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok","segment":"seg","file_descriptors":"not_a_list"}""".toByteArray())
        assertEquals(IngestDescriptors.NotAList, (client.ingest(manifest, fileBytes).single() as IngestOutcome.Accepted).descriptors)

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok","segment":"seg","file_descriptors":{}}""".toByteArray())
        assertEquals(IngestDescriptors.NotAList, (client.ingest(manifest, fileBytes).single() as IngestOutcome.Accepted).descriptors)

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok","segment":"seg","file_descriptors":[{"submitted":"a.bin","written":"a.bin","size":10,"sha256":"sha","disposition":"written"}]}""".toByteArray())
        val listed = (client.ingest(manifest, fileBytes).single() as IngestOutcome.Accepted).descriptors as IngestDescriptors.Listed
        assertEquals(1, listed.items.size)
        assertEquals(IngestFileDescriptor("a.bin", "a.bin", 10L, "sha", "written"), listed.items.first())

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok","segment":"seg","file_descriptors":["not_an_object"]}""".toByteArray())
        val nonObjListed = (client.ingest(manifest, fileBytes).single() as IngestOutcome.Accepted).descriptors as IngestDescriptors.Listed
        assertEquals(IngestFileDescriptor(null, null, null, null, null), nonObjListed.items.first())

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok","segment":"seg","file_descriptors":[{"submitted":"a.bin","size":1.0}]}""".toByteArray())
        val floatIntListed = (client.ingest(manifest, fileBytes).single() as IngestOutcome.Accepted).descriptors as IngestDescriptors.Listed
        assertEquals(1L, floatIntListed.items.first().size)

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok","segment":"seg","file_descriptors":[{"submitted":"a.bin","size":1.5}]}""".toByteArray())
        val fractionListed = (client.ingest(manifest, fileBytes).single() as IngestOutcome.Accepted).descriptors as IngestDescriptors.Listed
        assertNull(fractionListed.items.first().size)

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok","segment":"seg","file_descriptors":[{"submitted":"a.bin","size":"10"}]}""".toByteArray())
        val stringSizeListed = (client.ingest(manifest, fileBytes).single() as IngestOutcome.Accepted).descriptors as IngestDescriptors.Listed
        assertNull(stringSizeListed.items.first().size)
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

        assertEquals(
            listOf(IngestOutcome.Accepted("server-segment", IngestDescriptors.Absent), IngestOutcome.Accepted("server-segment", IngestDescriptors.Absent)),
            client.ingest(manifest, fileBytes),
        )

        http.response = HttpResponse(200, emptyMap(), """{"status":"collision","segment":"adjusted-segment"}""".toByteArray())
        assertEquals(
            listOf(IngestOutcome.Collision("adjusted-segment", IngestDescriptors.Absent), IngestOutcome.Collision("adjusted-segment", IngestDescriptors.Absent)),
            client.ingest(manifest, fileBytes),
        )

        http.response = HttpResponse(200, emptyMap(), """{"status":"duplicate","existing_segment":"existing-segment"}""".toByteArray())
        assertEquals(
            listOf(IngestOutcome.Duplicate("existing-segment", IngestDescriptors.Absent), IngestOutcome.Duplicate("existing-segment", IngestDescriptors.Absent)),
            client.ingest(manifest, fileBytes),
        )

        http.response = HttpResponse(200, emptyMap(), """{"status":"failed"}""".toByteArray())
        assertEquals(
            listOf(IngestOutcome.Failed(null), IngestOutcome.Failed(null)),
            client.ingest(manifest, fileBytes),
        )

        http.response = HttpResponse(200, emptyMap(), """{"status":"future"}""".toByteArray())
        assertEquals(
            listOf(IngestOutcome.UnknownStatus("future"), IngestOutcome.UnknownStatus("future")),
            client.ingest(manifest, fileBytes),
        )

        http.response = HttpResponse(200, emptyMap(), "not json".toByteArray())
        assertEquals(
            listOf(IngestOutcome.MalformedResponse("invalid_json"), IngestOutcome.MalformedResponse("invalid_json")),
            client.ingest(manifest, fileBytes),
        )

        http.response = HttpResponse(200, emptyMap(), """{"status":"ok"}""".toByteArray())
        assertEquals(
            listOf(IngestOutcome.MalformedResponse("missing_segment"), IngestOutcome.MalformedResponse("missing_segment")),
            client.ingest(manifest, fileBytes),
        )

        http.response = HttpResponse(401, emptyMap(), "unauthorized".toByteArray())
        val rejectedList = client.ingest(manifest, fileBytes)
        assertEquals(1, rejectedList.size)
        val rejected = assertIs<IngestOutcome.Rejected>(rejectedList.single())
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
