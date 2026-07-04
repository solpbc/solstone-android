// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import app.solstone.platform.power.StorageStatus
import app.solstone.platform.power.UsableSpaceProvider
import app.solstone.testing.BASE_CAPTURE_EPOCH_MS
import app.solstone.testing.FakeContinuousSource
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioContinuousSourceEngineTest {
    @Test
    fun alignsOffBoundaryCaptureToCompletedWallClockWindow() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        var now = OFF_BOUNDARY_EPOCH_MS
        var sleepCount = 0
        val engine = AudioContinuousSourceEngine(
            outputDirectory = outputDirectory,
            storageStatus = okStorage(),
            nowProvider = { now },
            sleeper = {
                // First sleep advances the off-boundary recording to the wall-clock boundary,
                // so the first emission is a completed [BASE, BASE + WINDOW_MS) window.
                if (sleepCount++ == 0) {
                    now = BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS
                } else {
                    throw InterruptedException()
                }
            },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.first()
        assertEquals(OFF_BOUNDARY_EPOCH_MS, emission.captureStartEpochMs)
        assertEquals(BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS, emission.captureEndEpochMs)
        assertEquals(AudioContinuousSourceEngine.PAYLOAD_NAME, emission.payloadRefs.single().name)
        assertTrue(File(outputDirectory, "audio-$BASE_CAPTURE_EPOCH_MS.m4a").exists())
    }

    @Test
    fun correctlyStampedAudioSurvivesSealByNextWindowEmission() {
        val outputDirectory = tempDirectory()
        val audioEmission = completedAudioEmission(outputDirectory)
        val segmenter = Segmenter(zoneId = UTC, windowMs = AudioContinuousSourceEngine.WINDOW_MS)

        segmenter.feed(coSourceEmission(BASE_CAPTURE_EPOCH_MS, BASE_CAPTURE_EPOCH_MS + 10_000L))
        segmenter.feed(audioEmission)
        val sealed = segmenter.feed(
            coSourceEmission(
                BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS + 5_000L,
                BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS + 6_000L,
            ),
        ).sealed

        val sealedWindow = sealed.single()
        assertTrue(sealedWindow.payloads.any { it.sourceId == AudioContinuousSourceEngine.SOURCE_ID && it.ref.name == AudioContinuousSourceEngine.PAYLOAD_NAME })
        assertFalse(sealedWindow.gaps.any { it.kind == "late_emission" })
    }

    @Test
    fun finalizeOnStopEmitsPartialAudioPayload() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val engine = AudioContinuousSourceEngine(
            outputDirectory = outputDirectory,
            storageStatus = okStorage(),
            nowProvider = { OFF_BOUNDARY_EPOCH_MS },
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        val ref = emission.payloadRefs.single()
        assertEquals(AudioContinuousSourceEngine.PAYLOAD_NAME, ref.name)
        assertTrue(ref.byteSize > 0L)
        assertTrue(File(outputDirectory, "audio-$BASE_CAPTURE_EPOCH_MS.m4a").exists())
        assertTrue(emission.gaps.isEmpty())
    }

    @Test
    fun startFailureAndZeroByteRecordingEmitGapsWithoutPayloads() {
        val failedOutputDirectory = tempDirectory()
        val failedSink = CapturingSink()
        val failedEngine = AudioContinuousSourceEngine(
            outputDirectory = failedOutputDirectory,
            storageStatus = okStorage(),
            nowProvider = { OFF_BOUNDARY_EPOCH_MS },
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(startError = IllegalStateException("boom")),
        )

        failedEngine.start(failedSink)
        waitForEmissions(failedSink, 1)
        failedEngine.stop()

        val failedEmission = failedSink.emissions.single()
        assertTrue(failedEmission.payloadRefs.isEmpty())
        assertEquals("capture_gap", failedEmission.gaps.single().kind)
        assertEquals("IllegalStateException", failedEmission.gaps.single().detail)

        val emptyOutputDirectory = tempDirectory()
        val emptySink = CapturingSink()
        val emptyEngine = AudioContinuousSourceEngine(
            outputDirectory = emptyOutputDirectory,
            storageStatus = okStorage(),
            nowProvider = { OFF_BOUNDARY_EPOCH_MS },
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = ByteArray(0)),
        )

        emptyEngine.start(emptySink)
        waitForEmissions(emptySink, 1)
        emptyEngine.stop()

        val emptyEmission = emptySink.emissions.single()
        assertTrue(emptyEmission.payloadRefs.isEmpty())
        assertEquals("empty_recording", emptyEmission.gaps.single().detail)
        assertFalse(File(emptyOutputDirectory, "audio-$BASE_CAPTURE_EPOCH_MS.m4a").exists())
    }

    @Test
    fun openReturnsPayloadBytesOnce() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val engine = AudioContinuousSourceEngine(
            outputDirectory = outputDirectory,
            storageStatus = okStorage(),
            nowProvider = { OFF_BOUNDARY_EPOCH_MS },
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        val ref = emission.payloadRefs.single()
        val payload = SegmentPayload(emission.sourceId, ref, emission.captureStartEpochMs, emission.captureEndEpochMs)
        assertEquals(ref.byteSize.toInt(), engine.open(payload).use { it.readBytes() }.size)
        assertFailsWith<IllegalArgumentException> { engine.open(payload) }
    }

    @Test
    fun releaseDeletesDroppedFile() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val engine = AudioContinuousSourceEngine(
            outputDirectory = outputDirectory,
            storageStatus = okStorage(),
            nowProvider = { OFF_BOUNDARY_EPOCH_MS },
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        val payload = SegmentPayload(emission.sourceId, emission.payloadRefs.single(), emission.captureStartEpochMs, emission.captureEndEpochMs)
        val path = File(outputDirectory, "audio-$BASE_CAPTURE_EPOCH_MS.m4a")
        assertTrue(path.exists())

        engine.release(payload)

        assertFalse(path.exists())
        assertFailsWith<IllegalArgumentException> { engine.open(payload) }
    }

    @Test
    fun audioAndPhotoForSameWindowSealTogether() {
        val outputDirectory = tempDirectory()
        val audioEmission = completedAudioEmission(outputDirectory)
        val photoSink = CapturingSink()
        FakeContinuousSource(
            sourceId = "photo",
            frameEveryMillis = 10_000L,
            frameSizeBytes = 16,
            frameCount = 1,
            fixedPayloadName = "photo.jpg",
            mediaType = "image/jpeg",
        ).emitAll(photoSink)
        val segmenter = Segmenter(zoneId = UTC, windowMs = AudioContinuousSourceEngine.WINDOW_MS)

        segmenter.feed(photoSink.emissions.single())
        segmenter.feed(audioEmission)
        val sealed = segmenter.feed(
            coSourceEmission(
                BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS + 5_000L,
                BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS + 6_000L,
            ),
        ).sealed

        val payloads = sealed.single().payloads
        assertTrue(payloads.any { it.sourceId == "photo" && it.ref.name == "photo.jpg" })
        assertTrue(payloads.any { it.sourceId == AudioContinuousSourceEngine.SOURCE_ID && it.ref.name == AudioContinuousSourceEngine.PAYLOAD_NAME })
    }

    @Test
    fun finishFailureEmitsGapDeletesFileAndDoesNotExposePayload() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val engine = AudioContinuousSourceEngine(
            outputDirectory = outputDirectory,
            storageStatus = okStorage(),
            nowProvider = { OFF_BOUNDARY_EPOCH_MS },
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(
                bytesToWrite = AUDIO_BYTES,
                finishError = IllegalStateException("stop failed"),
            ),
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        assertTrue(emission.payloadRefs.isEmpty())
        assertEquals("finish_failed type=IllegalStateException message=stop failed", emission.gaps.single().detail)
        assertFalse(File(outputDirectory, "audio-$BASE_CAPTURE_EPOCH_MS.m4a").exists())
    }

    @Test
    fun workerDeathEmitsTerminalGapAndDiagAndReportsNotRunning() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val diags = CopyOnWriteArrayList<String>()
        val engine = AudioContinuousSourceEngine(
            outputDirectory = outputDirectory,
            storageStatus = okStorage(),
            nowProvider = { OFF_BOUNDARY_EPOCH_MS },
            sleeper = { throw IllegalStateException("sleep failed") },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
            diag = diags::add,
        )

        engine.start(sink)
        waitForEmissions(sink, 1)

        assertFalse(engine.condition().running)
        assertEquals("engine_failed type=IllegalStateException message=sleep failed", sink.emissions.single().gaps.single().detail)
        assertTrue("capture event=engine-failed source=audio type=IllegalStateException message=sleep failed" in diags)
    }

    @Test
    fun guardedEmitCapturesRejectedExecutionAndStopsWorker() {
        val diags = CopyOnWriteArrayList<String>()
        val engine = AudioContinuousSourceEngine(
            outputDirectory = tempDirectory(),
            storageStatus = okStorage(),
            nowProvider = { OFF_BOUNDARY_EPOCH_MS },
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
            diag = diags::add,
        )

        engine.start(ThrowingSink)
        waitForDiag(diags, "capture event=emit-failed source=audio type=RejectedExecutionException message=closed")

        assertFalse(engine.condition().running)
    }

    private fun completedAudioEmission(outputDirectory: File): SourceEmission {
        val sink = CapturingSink()
        var now = OFF_BOUNDARY_EPOCH_MS
        var sleepCount = 0
        val engine = AudioContinuousSourceEngine(
            outputDirectory = outputDirectory,
            storageStatus = okStorage(),
            nowProvider = { now },
            sleeper = {
                if (sleepCount++ == 0) {
                    now = BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS
                } else {
                    throw InterruptedException()
                }
            },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        )
        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()
        return sink.emissions.first()
    }

    private class FakeAudioRecorderFactory(
        private val bytesToWrite: ByteArray = AUDIO_BYTES,
        private val startError: RuntimeException? = null,
        private val finishError: RuntimeException? = null,
    ) : AudioRecorderFactory {
        override fun create(output: File): AudioRecording =
            FakeAudioRecording(output, bytesToWrite, startError, finishError)
    }

    private class FakeAudioRecording(
        private val output: File,
        private val bytesToWrite: ByteArray,
        private val startError: RuntimeException?,
        private val finishError: RuntimeException?,
    ) : AudioRecording {
        override fun start() {
            startError?.let { throw it }
            output.writeBytes(bytesToWrite)
        }

        override fun finish(): RecordingFinishResult =
            finishError?.let { RecordingFinishResult.Failure(it) }
                ?: RecordingFinishResult.Success(output.length())

        override fun discard() {
            output.delete()
        }
    }

    private class CapturingSink : EmissionSink {
        val emissions = CopyOnWriteArrayList<SourceEmission>()

        override fun emit(emission: SourceEmission) {
            emissions += emission
        }
    }

    private object ThrowingSink : EmissionSink {
        override fun emit(emission: SourceEmission) {
            throw RejectedExecutionException("closed")
        }
    }

    private fun waitForDiag(lines: List<String>, expected: String) {
        repeat(200) {
            if (expected in lines) return
            Thread.sleep(5L)
        }
        throw AssertionError("missing diag: $expected in $lines")
    }

    private fun coSourceEmission(captureStartEpochMs: Long, captureEndEpochMs: Long): SourceEmission =
        SourceEmission(
            sourceId = "co-source",
            stream = MAIN_STREAM,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = captureStartEpochMs,
            captureEndEpochMs = captureEndEpochMs,
            payloadRefs = listOf(PayloadRef("co-source-$captureStartEpochMs.bin", "application/octet-stream", 1L, null)),
            metadata = emptyMap(),
            gaps = emptyList(),
        )

    private fun okStorage(): StorageStatus =
        StorageStatus(UsableSpaceProvider { Long.MAX_VALUE }, minimumFreeBytes = 1L)

    private fun tempDirectory(): File =
        Files.createTempDirectory("solstone-audio-test").toFile()

    private fun waitForEmissions(sink: CapturingSink, count: Int) {
        repeat(200) {
            if (sink.emissions.size >= count) return
            Thread.sleep(5L)
        }
        throw AssertionError("expected $count emissions, got ${sink.emissions.size}")
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        const val OFF_BOUNDARY_EPOCH_MS = BASE_CAPTURE_EPOCH_MS + 137_000L
        val AUDIO_BYTES = "fake-m4a-bytes".encodeToByteArray()
    }
}
