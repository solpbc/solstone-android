// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.testing

import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.model.GapEvent
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.SourceEmission
import app.solstone.platform.camera.still.StillCamera
import app.solstone.platform.camera.still.StillCaptureEngine
import app.solstone.platform.camera.still.StillCaptureResult
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraStillPipelineTest {
    @Test
    fun stillCapturesSealWithAudioInOneMainStreamSegment() {
        val base = Files.createTempDirectory("solstone-camera-still-pipeline")
        val stillBytes = listOf(
            "jpeg-a".encodeToByteArray(),
            "jpeg-b".encodeToByteArray(),
            "jpeg-c".encodeToByteArray(),
        )
        val audioBytes = "audio-window".encodeToByteArray()
        val sink = CapturingSink()
        val sleeps = mutableListOf<Long>()
        val camera = StillCaptureEngine(
            stillCamera = FakeStillCamera(stillBytes),
            nowProvider = { BASE_CAPTURE_EPOCH_MS },
            sleeper = { millis ->
                sleeps += millis
                if (sleeps.size == stillBytes.size) throw InterruptedException()
            },
        )

        try {
            camera.start(sink)
            waitForEmissions(sink, stillBytes.size)
            camera.stop()

            val segmenter = Segmenter(ZoneId.of("UTC"))
            val audio = FakeContinuousSource(
                sourceId = "audio",
                stream = MAIN_STREAM,
                frameEveryMillis = 305_000L,
                frameSizeBytes = audioBytes.size,
                frameCount = 2,
                fixedPayloadName = "audio.m4a",
                mediaType = "audio/mp4",
            )
            val audioEmissions = CapturingSink().also { audio.emitAll(it) }.emissions
            val sealed = mutableListOf<app.solstone.core.segment.SealedSegment>()
            sealed += segmenter.feed(audioEmissions[0]).sealed
            sink.emissions.forEach { emission -> sealed += segmenter.feed(emission).sealed }
            sealed += segmenter.feed(audioEmissions[1]).sealed

            assertEquals(1, sealed.size)
            val provider = cameraPayloadProvider(camera, audioBytes)
            val result = FileSpoolWriter(base).seal(sealed.single(), provider)
            val manifest = result.manifest
            val finalDir = result.directory ?: error("missing final directory")

            val files = manifest.files
            val cameraFiles = files.filter { it.sourceId == StillCaptureEngine.SOURCE_ID }
            assertEquals(listOf("audio.m4a"), files.filter { it.sourceId == "audio" }.map { it.name })
            assertEquals(stillBytes.size, cameraFiles.size)
            assertTrue(cameraFiles.all { it.name.startsWith("camera-$BASE_CAPTURE_EPOCH_MS-") && it.name.endsWith(".jpg") })
            assertEquals(stillBytes.size, cameraFiles.map { it.name }.distinct().size)
            assertEquals(stillBytes.size, cameraFiles.map { it.sha256 }.distinct().size)
            assertTrue(cameraFiles.all { it.mediaType == StillCaptureEngine.MEDIA_TYPE })
            assertEquals(
                setOf("audio.m4a") + cameraFiles.map { it.name }.toSet(),
                files.map { it.name }.toSet(),
            )

            val diskCameraFiles = Files.list(finalDir).use { paths ->
                paths.filter { it.fileName.toString().startsWith("camera-") }
                    .sorted()
                    .toList()
            }
            assertEquals(stillBytes.size, diskCameraFiles.size)
            assertEquals(cameraFiles.map { it.name }.toSet(), diskCameraFiles.map { it.fileName.toString() }.toSet())
            assertEquals(cameraFiles.map { it.sha256 }.toSet(), diskCameraFiles.map { sha256(it) }.toSet())
        } finally {
            camera.stop()
            base.deleteRecursively()
        }
    }

    @Test
    fun partialSourceGapStillSealsSegmentWithSurvivingPayload() {
        val base = Files.createTempDirectory("solstone-camera-still-partial-gap")
        val cameraBytes = "jpeg-survives".encodeToByteArray()
        val audioBytes = "audio-window".encodeToByteArray()
        val sink = CapturingSink()
        val sleeps = mutableListOf<Long>()
        val camera = StillCaptureEngine(
            stillCamera = FakeStillCamera(listOf(cameraBytes)),
            nowProvider = { BASE_CAPTURE_EPOCH_MS },
            sleeper = { millis ->
                sleeps += millis
                throw InterruptedException()
            },
        )

        try {
            camera.start(sink)
            waitForEmissions(sink, 1)
            camera.stop()

            val gap = GapEvent("capture_gap", BASE_CAPTURE_EPOCH_MS + 1_000L, "storage")
            val audio = FakeContinuousSource(
                sourceId = "audio",
                stream = MAIN_STREAM,
                frameEveryMillis = 300_000L,
                frameSizeBytes = audioBytes.size,
                frameCount = 1,
                gaps = listOf(ScriptedGap(afterEmissionIndex = 0, gap = gap)),
                fixedPayloadName = "audio.m4a",
                mediaType = "audio/mp4",
            )
            val audioEmissions = CapturingSink().also { audio.emitAll(it) }.emissions
            val segmenter = Segmenter(ZoneId.of("UTC"))
            segmenter.feed(audioEmissions.single())
            sink.emissions.forEach { emission -> segmenter.feed(emission) }
            val sealed = segmenter.flush().sealed.single()

            assertEquals(listOf(gap), sealed.gaps)
            assertTrue(sealed.payloads.any { it.sourceId == StillCaptureEngine.SOURCE_ID })

            val provider = cameraPayloadProvider(camera, audioBytes)
            val result = FileSpoolWriter(base).seal(sealed, provider)
            val files = result.manifest.files

            assertTrue(files.any { it.sourceId == StillCaptureEngine.SOURCE_ID && it.mediaType == StillCaptureEngine.MEDIA_TYPE })
            assertTrue(files.any { it.sourceId == "audio" && it.name == "audio.m4a" })
        } finally {
            camera.stop()
            base.deleteRecursively()
        }
    }

    @Test
    fun failedCameraCaptureStillSealsSegmentWithAudioAndGapOnly() {
        val base = Files.createTempDirectory("solstone-camera-still-failed-capture")
        val audioBytes = "audio-window".encodeToByteArray()
        val sink = CapturingSink()
        val camera = StillCaptureEngine(
            stillCamera = FailingStillCamera(),
            nowProvider = { BASE_CAPTURE_EPOCH_MS },
            sleeper = { throw InterruptedException() },
        )

        try {
            camera.start(sink)
            waitForEmissions(sink, 1)
            camera.stop()

            val audio = FakeContinuousSource(
                sourceId = "audio",
                stream = MAIN_STREAM,
                frameEveryMillis = 300_000L,
                frameSizeBytes = audioBytes.size,
                frameCount = 1,
                fixedPayloadName = "audio.m4a",
                mediaType = "audio/mp4",
            )
            val audioEmissions = CapturingSink().also { audio.emitAll(it) }.emissions
            val segmenter = Segmenter(ZoneId.of("UTC"))
            segmenter.feed(audioEmissions.single())
            sink.emissions.forEach { emission -> segmenter.feed(emission) }
            val sealed = segmenter.flush().sealed.single()

            assertTrue(sealed.payloads.any { it.sourceId == "audio" && it.ref.name == "audio.m4a" })
            assertTrue(sealed.payloads.none { it.sourceId == StillCaptureEngine.SOURCE_ID })
            val cameraGap = sealed.gaps.single { it.kind == "capture_gap" }
            assertEquals(BASE_CAPTURE_EPOCH_MS, cameraGap.atEpochMs)
            assertEquals("capture_failed type=IllegalStateException message=capture failed", cameraGap.detail)

            val result = FileSpoolWriter(base).seal(
                sealed,
                object : PayloadBytesProvider {
                    override fun open(payload: SegmentPayload) =
                        when (payload.sourceId) {
                            "audio" -> ByteArrayInputStream(audioBytes)
                            else -> error("unexpected payload source: ${payload.sourceId}")
                        }
                },
            )
            val files = result.manifest.files

            assertEquals(listOf("audio.m4a"), files.map { it.name })
            assertTrue(files.none { it.sourceId == StillCaptureEngine.SOURCE_ID || it.name.startsWith("camera-") })
        } finally {
            camera.stop()
            base.deleteRecursively()
        }
    }

    private class FakeStillCamera(private val stills: List<ByteArray>) : StillCamera {
        private var index = 0

        override fun takeStill(): StillCaptureResult =
            stills.getOrNull(index++)
                ?.let(StillCaptureResult::Image)
                ?: StillCaptureResult.Failure(IllegalStateException("no still"))
    }

    private class FailingStillCamera : StillCamera {
        override fun takeStill(): StillCaptureResult =
            StillCaptureResult.Failure(IllegalStateException("capture failed"))
    }

    private class CapturingSink : EmissionSink {
        val emissions = CopyOnWriteArrayList<SourceEmission>()

        override fun emit(emission: SourceEmission) {
            emissions += emission
        }
    }

    private fun waitForEmissions(sink: CapturingSink, count: Int) {
        repeat(200) {
            if (sink.emissions.size >= count) return
            Thread.sleep(5L)
        }
        throw AssertionError("expected $count emissions, got ${sink.emissions.size}")
    }

    private fun cameraPayloadProvider(camera: StillCaptureEngine, audioBytes: ByteArray): PayloadBytesProvider =
        object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) =
                when (payload.sourceId) {
                    "audio" -> ByteArrayInputStream(audioBytes)
                    StillCaptureEngine.SOURCE_ID -> camera.open(payload)
                    else -> error("unknown payload source: ${payload.sourceId}")
                }
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Path.deleteRecursively() {
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
