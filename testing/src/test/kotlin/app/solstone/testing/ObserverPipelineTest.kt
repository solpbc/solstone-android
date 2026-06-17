// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.testing

import app.solstone.core.model.GapEvent
import app.solstone.core.model.SourceKind
import app.solstone.core.segment.Segmenter
import app.solstone.core.segment.SegmenterAnchor
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.sha256
import app.solstone.core.spool.CountingSpoolWriter
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObserverPipelineTest {
    @Test
    fun fileSpoolWriterProducesDeterministicSealedBundlesWithManifestLast() {
        val first = Files.createTempDirectory("solstone-spool-first")
        val second = Files.createTempDirectory("solstone-spool-second")
        try {
            val firstRun = runFilePipeline(first)
            val secondRun = runFilePipeline(second)

            assertEquals(firstRun.relativeManifestBytes, secondRun.relativeManifestBytes)
            assertEquals(firstRun.relativePayloadHashes, secondRun.relativePayloadHashes)
            assertTrue(firstRun.relativeManifestBytes.keys.any { it.contains("/source-a/") })
            firstRun.finalDirs.forEach { sealed ->
                val dir = sealed.directory
                assertEquals(first.resolve(sealed.day).resolve(sealed.stream).resolve(sealed.segment), dir)
                val childNames = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
                assertEquals((sealed.payloadNames + "manifest").sorted(), childNames)
            }
            assertFalse(Files.exists(first.resolve(".draft")))
            assertFalse(Files.exists(second.resolve(".draft")))
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test
    fun countingSpoolWriterCountsExactlyTwoHundredEightyEightFullWindows() {
        val clock = VirtualMonotonicClock(0)
        val segmenter = Segmenter(clock, SegmenterAnchor(BASE_CAPTURE_EPOCH_MS, 0, ZoneId.of("UTC")))
        val source = FakeContinuousSource("source-a", clock, frameEveryMillis = 300_000, frameSizeBytes = 8, frameCount = 289)
        val writer = CountingSpoolWriter()
        val provider = PayloadBytesProvider { payload ->
            ByteArrayInputStream(bytesForPayload(payload))
        }

        source.emitAll { emission ->
            segmenter.feed(emission).forEach { writer.seal(it, provider) }
        }

        assertEquals(288, writer.sealedCount)
        assertEquals(288, writer.manifests.size)
        assertTrue(writer.manifests.all { it.key.segment.endsWith("_300") })
    }

    @Test
    fun fullAudioWindowSealsWithSingleM4aPayload() {
        val clock = VirtualMonotonicClock(0)
        val segmenter = Segmenter(clock, SegmenterAnchor(BASE_CAPTURE_EPOCH_MS, 0, ZoneId.of("UTC")))

        segmenter.feed(audioEmission(BASE_CAPTURE_EPOCH_MS, BASE_CAPTURE_EPOCH_MS + 300_000))
        clock.advanceByMillis(300_000)
        val sealed = segmenter.feed(audioEmission(BASE_CAPTURE_EPOCH_MS + 300_000, BASE_CAPTURE_EPOCH_MS + 600_000)).single()

        assertEquals("000000_300", sealed.key.segment)
        assertEquals(1, sealed.payloads.size)
        assertEquals("audio.m4a", sealed.payloads.single().ref.name)
        assertEquals("audio/mp4", sealed.payloads.single().ref.mediaType)
        assertTrue(sealed.gaps.isEmpty())
    }

    @Test
    fun failedAudioWindowSealsWithGapAndNoPayloads() {
        val clock = VirtualMonotonicClock(0)
        val segmenter = Segmenter(clock, SegmenterAnchor(BASE_CAPTURE_EPOCH_MS, 0, ZoneId.of("UTC")))
        val gap = GapEvent("capture_gap", BASE_CAPTURE_EPOCH_MS + 1_000, "storage")

        segmenter.feed(audioGapEmission(BASE_CAPTURE_EPOCH_MS, BASE_CAPTURE_EPOCH_MS + 1_000, gap))
        clock.advanceByMillis(300_000)
        val sealed = segmenter.feed(audioEmission(BASE_CAPTURE_EPOCH_MS + 300_000, BASE_CAPTURE_EPOCH_MS + 600_000)).single()

        assertEquals("000000_300", sealed.key.segment)
        assertTrue(sealed.payloads.isEmpty())
        assertEquals(listOf(gap), sealed.gaps)
    }

    @Test
    fun importSourcePreservesOriginalEventTimeThroughSegmentAndSpool() {
        val receiptEpochMs = BASE_CAPTURE_EPOCH_MS + 1_000_000L
        val captureStart = receiptEpochMs - 600_000L
        val captureEnd = captureStart + 10_000L
        val import = FakeImportSource(listOf(fakeImportEmission("import-a", "calendar-item.bin", captureStart, captureEnd)))
        val clock = VirtualMonotonicClock(0)
        val segmenter = Segmenter(clock, SegmenterAnchor(receiptEpochMs, 0, ZoneId.of("UTC")))
        val writer = CountingSpoolWriter()
        val provider = PayloadBytesProvider { payload -> ByteArrayInputStream(bytesForPayload(payload)) }

        val segment = import.importNow().flatMap { segmenter.feed(it) } + segmenter.flush()
        assertEquals(1, segment.size)
        writer.seal(segment.single(), provider)

        val file = writer.manifests.single().files.single()
        assertEquals(captureStart, file.captureStartEpochMs)
        assertEquals(captureEnd, file.captureEndEpochMs)
        assertTrue(file.captureStartEpochMs < receiptEpochMs)
    }

    private fun runFilePipeline(base: Path): PipelineSnapshot {
        val clock = VirtualMonotonicClock(0)
        val segmenter = Segmenter(
            clock = clock,
            anchor = SegmenterAnchor(BASE_CAPTURE_EPOCH_MS, 0, ZoneId.of("UTC")),
        )
        val source = FakeContinuousSource(
            sourceId = "source-a",
            clock = clock,
            frameEveryMillis = 300_000,
            frameSizeBytes = 12,
            frameCount = 3,
            gaps = listOf(ScriptedGap(1, GapEvent("scripted", BASE_CAPTURE_EPOCH_MS + 300_000, "gap"))),
        )
        val writer = FileSpoolWriter(base)
        val sealedDirs = mutableListOf<SealedDir>()
        val provider = PayloadBytesProvider { payload ->
            assertFalse(Files.exists(base.resolve(".draft").resolve(payload.sourceId).resolve("manifest")))
            if (Files.exists(base.resolve(".draft"))) {
                val manifests = Files.walk(base.resolve(".draft")).use { paths ->
                    paths.filter { it.fileName.toString() == "manifest" }.toList()
                }
                assertTrue(manifests.isEmpty(), "manifest must not exist while payload bytes are being opened")
            }
            ByteArrayInputStream(bytesForPayload(payload))
        }

        source.emitAll(EmissionSink { emission ->
            segmenter.feed(emission).forEach { segment ->
                sealedDirs.add(segment.toSealedDir(writer.seal(segment, provider).directory ?: error("missing final dir")))
            }
        })
        segmenter.flush().forEach { segment ->
            sealedDirs.add(segment.toSealedDir(writer.seal(segment, provider).directory ?: error("missing final dir")))
        }

        assertFalse(Files.exists(base.resolve(".draft")))
        return PipelineSnapshot(
            finalDirs = sealedDirs,
            relativeManifestBytes = manifestBytes(base),
            relativePayloadHashes = payloadHashes(base),
        )
    }

    private fun audioEmission(startEpochMs: Long, endEpochMs: Long): SourceEmission =
        SourceEmission(
            sourceId = "audio",
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = startEpochMs,
            captureEndEpochMs = endEpochMs,
            payloadRefs = listOf(PayloadRef("audio.m4a", "audio/mp4", 16, null)),
            metadata = emptyMap(),
            gaps = emptyList(),
        )

    private fun audioGapEmission(startEpochMs: Long, endEpochMs: Long, gap: GapEvent): SourceEmission =
        SourceEmission(
            sourceId = "audio",
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = startEpochMs,
            captureEndEpochMs = endEpochMs,
            payloadRefs = emptyList(),
            metadata = emptyMap(),
            gaps = listOf(gap),
        )

    private fun manifestBytes(base: Path): Map<String, String> =
        Files.walk(base).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "manifest" }
                .sorted()
                .toList()
                .associate { base.relativize(it).toString() to String(Files.readAllBytes(it), StandardCharsets.UTF_8) }
        }

    private fun payloadHashes(base: Path): Map<String, String> =
        Files.walk(base).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() != "manifest" }
                .sorted()
                .toList()
                .associate { base.relativize(it).toString() to sha256(it) }
        }

    private fun bytesForPayload(payload: SegmentPayload): ByteArray {
        val index = payload.ref.name.substringAfterLast("-").substringBefore(".").toIntOrNull() ?: 0
        return fakePayloadBytes(payload.sourceId, payload.ref.name, index, payload.ref.byteSize.toInt())
    }

    private data class PipelineSnapshot(
        val finalDirs: List<SealedDir>,
        val relativeManifestBytes: Map<String, String>,
        val relativePayloadHashes: Map<String, String>,
    )

    private data class SealedDir(
        val directory: Path,
        val day: String,
        val stream: String,
        val segment: String,
        val payloadNames: List<String>,
    )

    private fun app.solstone.core.segment.SealedSegment.toSealedDir(directory: Path): SealedDir =
        SealedDir(
            directory = directory,
            day = key.day,
            stream = stream,
            segment = key.segment,
            payloadNames = payloads.map { it.ref.name },
        )
}

private fun Path.deleteRecursively() {
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}
