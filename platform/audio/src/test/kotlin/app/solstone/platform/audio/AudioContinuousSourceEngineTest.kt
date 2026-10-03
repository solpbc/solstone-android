// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.UnresolvedInterruption
import app.solstone.platform.power.StorageStatus
import app.solstone.platform.power.UsableSpaceProvider
import app.solstone.testing.BASE_CAPTURE_EPOCH_MS
import app.solstone.testing.FakeContinuousSource
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioContinuousSourceEngineTest {
    @Test
    fun conditionExposesOnlyInFlightRecordingSilencedFact() {
        val sink = CapturingSink()
        val releaseSleep = CountDownLatch(1)
        val engine = createEngine(
            sleeper = {
                releaseSleep.await(1, TimeUnit.SECONDS)
                throw InterruptedException()
            },
            recorderFactory = FakeAudioRecorderFactory(
                bytesToWrite = AUDIO_BYTES,
                silenced = SilencedFact.SILENCED,
            ),
        )

        assertEquals(SilencedFact.UNKNOWN, engine.condition().silenced)

        engine.start(sink)
        waitForCondition { engine.condition().silenced == SilencedFact.SILENCED }

        releaseSleep.countDown()
        waitForEmissions(sink, 1)
        assertEquals(SilencedFact.UNKNOWN, engine.condition().silenced)
        engine.stop()
    }

    @Test
    fun conditionIsUnknownForStartFailureAndPostFinishDeadTime() {
        val failedSink = CapturingSink()
        val failed = createEngine(
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(startError = IllegalStateException("boom")),
        )

        failed.start(failedSink)
        waitForEmissions(failedSink, 1)
        assertEquals(SilencedFact.UNKNOWN, failed.condition().silenced)
        failed.stop()

        val finishEntered = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        var now = OFF_BOUNDARY_EPOCH_MS
        var sleeps = 0
        val deadTime = createEngine(
            nowProvider = { now },
            sleeper = {
                if (sleeps++ == 0) {
                    now = BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS
                } else {
                    throw InterruptedException()
                }
            },
            recorderFactory = BlockingFinishAudioRecorderFactory(finishEntered, releaseFinish),
        )

        deadTime.start(CapturingSink())
        assertTrue(finishEntered.await(1, TimeUnit.SECONDS))
        assertTrue(deadTime.condition().running)
        assertEquals(SilencedFact.UNKNOWN, deadTime.condition().silenced)

        releaseFinish.countDown()
        deadTime.stop()
    }

    @Test
    fun delayedStoppedWorkerCannotHideNewGenerationSilencedFact() {
        val firstStartEntered = CountDownLatch(1)
        val allowFirstStart = CountDownLatch(1)
        val oldPublicationReached = CountDownLatch(1)
        val allowOldWorkerToContinue = CountDownLatch(1)
        val holdWindow = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val oldStartReturned = ThreadLocal<Boolean>()
        val engine = createEngine(
            nowProvider = {
                if (oldStartReturned.get() == true) {
                    oldStartReturned.remove()
                    oldPublicationReached.countDown()
                    awaitIgnoringInterrupts(allowOldWorkerToContinue)
                }
                OFF_BOUNDARY_EPOCH_MS
            },
            sleeper = {
                awaitIgnoringInterrupts(holdWindow)
                throw InterruptedException()
            },
            recorderFactory = StopStartOverlapRecorderFactory(
                firstStartEntered = firstStartEntered,
                allowFirstStart = allowFirstStart,
                oldStartReturned = oldStartReturned,
            ),
        )

        engine.start(CapturingSink())
        assertTrue(firstStartEntered.await(1, TimeUnit.SECONDS))
        val stopper = Thread {
            engine.stop()
            stopFinished.countDown()
        }
        stopper.start()
        try {
            waitForCondition { !engine.condition().running }

            engine.start(CapturingSink())
            waitForCondition { engine.condition().silenced == SilencedFact.SILENCED }

            allowFirstStart.countDown()
            assertTrue(oldPublicationReached.await(1, TimeUnit.SECONDS))
            assertEquals(SilencedFact.SILENCED, engine.condition().silenced)
        } finally {
            allowFirstStart.countDown()
            allowOldWorkerToContinue.countDown()
            holdWindow.countDown()
            assertTrue(stopFinished.await(1, TimeUnit.SECONDS))
            stopper.join(1_000L)
        }
    }

    @Test
    fun stoppedConditionIsNeverSilenced() {
        val holdWindow = CountDownLatch(1)
        val engine = createEngine(
            sleeper = {
                awaitIgnoringInterrupts(holdWindow)
                throw InterruptedException()
            },
            recorderFactory = FakeAudioRecorderFactory(silenced = SilencedFact.SILENCED),
        )

        engine.start(CapturingSink())
        waitForCondition { engine.condition().silenced == SilencedFact.SILENCED }
        holdWindow.countDown()
        engine.stop()

        val condition = engine.condition()
        assertFalse(condition.running)
        assertEquals(SilencedFact.UNKNOWN, condition.silenced)
    }

    @Test
    fun alignsOffBoundaryCaptureToCompletedWallClockWindow() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        var now = OFF_BOUNDARY_EPOCH_MS
        var sleepCount = 0
        val engine = createEngine(
            outputDirectory = outputDirectory,
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

        val emission = sink.emissions.first()
        assertEquals(OFF_BOUNDARY_EPOCH_MS, emission.captureStartEpochMs)
        assertEquals(BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS, emission.captureEndEpochMs)
        assertEquals(AudioContinuousSourceEngine.PAYLOAD_NAME, emission.payloadRefs.single().name)
        val attemptDirs = outputDirectory.listFiles { f -> f.isDirectory && f.name.startsWith("rec-") }
        assertTrue(attemptDirs != null && attemptDirs.isNotEmpty())
        assertTrue(attemptDirs!!.any { File(it, AudioContinuousSourceEngine.PAYLOAD_NAME).exists() })
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
        val recordingStarted = CountDownLatch(1)
        val engine = createEngine(
            outputDirectory = outputDirectory,
            sleeper = {
                recordingStarted.countDown()
                Thread.sleep(Long.MAX_VALUE)
            },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        )

        engine.start(sink)
        assertTrue(recordingStarted.await(1, TimeUnit.SECONDS))
        engine.stop()
        waitForEmissions(sink, 1)

        val emission = sink.emissions.single()
        val ref = emission.payloadRefs.single()
        assertEquals(AudioContinuousSourceEngine.PAYLOAD_NAME, ref.name)
        assertTrue(ref.byteSize > 0L)
        val attemptDirs = outputDirectory.listFiles { f -> f.isDirectory && f.name.startsWith("rec-") }
        assertEquals(1, attemptDirs?.size)
        assertTrue(File(attemptDirs!!.single(), AudioContinuousSourceEngine.PAYLOAD_NAME).exists())
        assertTrue(emission.gaps.isEmpty())
    }

    @Test
    fun startFailureAndZeroByteRecordingEmitGapsWithoutPayloads() {
        val failedOutputDirectory = tempDirectory()
        val failedSink = CapturingSink()
        val failedInterruption = FakeUnresolvedInterruption()
        val failedEngine = createEngine(
            outputDirectory = failedOutputDirectory,
            interruption = failedInterruption,
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
        assertTrue(failedInterruption.marked)

        val emptyOutputDirectory = tempDirectory()
        val emptySink = CapturingSink()
        val emptyInterruption = FakeUnresolvedInterruption()
        val emptyEngine = createEngine(
            outputDirectory = emptyOutputDirectory,
            interruption = emptyInterruption,
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = ByteArray(0)),
        )

        emptyEngine.start(emptySink)
        waitForEmissions(emptySink, 1)
        emptyEngine.stop()

        val emptyEmission = emptySink.emissions.single()
        assertTrue(emptyEmission.payloadRefs.isEmpty())
        assertEquals("empty_recording", emptyEmission.gaps.single().detail)
        assertTrue(emptyInterruption.marked)
    }

    @Test
    fun openReturnsPayloadBytesOnceAndReleaseDeletesDir() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val engine = createEngine(
            outputDirectory = outputDirectory,
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

        val attemptDirs = outputDirectory.listFiles { f -> f.isDirectory && f.name.startsWith("rec-") }
        assertEquals(1, attemptDirs?.size)
        val attemptDir = attemptDirs!!.single()
        assertTrue(attemptDir.exists())

        engine.release(payload)
        assertFalse(attemptDir.exists())
        assertFailsWith<IllegalArgumentException> { engine.open(payload) }
    }

    @Test
    fun discardUncommittedMarksInterruptionAndDeletesDir() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val interruption = FakeUnresolvedInterruption()
        val engine = createEngine(
            outputDirectory = outputDirectory,
            interruption = interruption,
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        val payload = SegmentPayload(emission.sourceId, emission.payloadRefs.single(), emission.captureStartEpochMs, emission.captureEndEpochMs)
        val attemptDirs = outputDirectory.listFiles { f -> f.isDirectory && f.name.startsWith("rec-") }
        assertEquals(1, attemptDirs?.size)
        val attemptDir = attemptDirs!!.single()
        assertTrue(attemptDir.exists())

        assertTrue(engine.discardUncommitted(payload))
        assertTrue(interruption.marked)
        assertFalse(attemptDir.exists())
    }

    @Test
    fun inFlightRecordingIdsTracksActiveAndRegisteredAttempts() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val recordingStarted = CountDownLatch(1)
        val engine = createEngine(
            outputDirectory = outputDirectory,
            sleeper = {
                recordingStarted.countDown()
                Thread.sleep(Long.MAX_VALUE)
            },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        )

        engine.start(sink)
        assertTrue(recordingStarted.await(1, TimeUnit.SECONDS))
        val inFlightActive = engine.inFlightRecordingIds()
        assertEquals(1, inFlightActive.size)
        assertTrue(inFlightActive.single().startsWith("rec-"))

        engine.stop()
        waitForEmissions(sink, 1)
        val inFlightRegistered = engine.inFlightRecordingIds()
        assertEquals(1, inFlightRegistered.size)
        assertEquals(inFlightActive.single(), inFlightRegistered.single())

        val emission = sink.emissions.single()
        val payload = SegmentPayload(emission.sourceId, emission.payloadRefs.single(), emission.captureStartEpochMs, emission.captureEndEpochMs)
        engine.release(payload)
        assertTrue(engine.inFlightRecordingIds().isEmpty())
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
    fun finishFailureSalvagesAudioWhenRemuxSucceeds() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val engine = createEngine(
            outputDirectory = outputDirectory,
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
        assertEquals(1, emission.payloadRefs.size)
        assertEquals(AudioContinuousSourceEngine.PAYLOAD_NAME, emission.payloadRefs.single().name)
    }

    @Test
    fun workerDeathEmitsTerminalGapAndDiagAndReportsNotRunning() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val diags = CopyOnWriteArrayList<String>()
        val engine = createEngine(
            outputDirectory = outputDirectory,
            sleeper = { throw IllegalStateException("sleep failed") },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
            diag = diags::add,
        )

        engine.start(sink)
        waitForEmissions(sink, 1)

        waitForCondition { !engine.condition().running }
        assertEquals("engine_failed type=IllegalStateException message=sleep failed", sink.emissions.single().gaps.single().detail)
        assertTrue("capture event=engine-failed source=audio type=IllegalStateException message=sleep failed" in diags)
    }

    @Test
    fun guardedEmitCapturesRejectedExecutionAndStopsWorker() {
        val diags = CopyOnWriteArrayList<String>()
        val engine = createEngine(
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
        val engine = createEngine(
            outputDirectory = outputDirectory,
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

    private fun createEngine(
        outputDirectory: File = tempDirectory(),
        storageStatus: StorageStatus = okStorage(),
        remuxer: AacAdtsRemuxer = FakeAacRemuxer(),
        interruption: UnresolvedInterruption = FakeUnresolvedInterruption(),
        nowProvider: () -> Long = { OFF_BOUNDARY_EPOCH_MS },
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
        recorderFactory: AudioRecorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        diag: (String) -> Unit = {},
    ): AudioContinuousSourceEngine = AudioContinuousSourceEngine(
        outputDirectory = outputDirectory,
        storageStatus = storageStatus,
        remuxer = remuxer,
        interruption = interruption,
        nowProvider = nowProvider,
        sleeper = sleeper,
        recorderFactory = recorderFactory,
        diag = diag,
    )

    private class FakeAacRemuxer(
        private val remuxDurationMs: Long = 300_000L,
        private val remuxFailure: Throwable? = null,
    ) : AacAdtsRemuxer {
        override fun remux(adts: File, m4a: File): AacRemux {
            remuxFailure?.let { throw it }
            if (!adts.exists() || adts.length() <= 0L) {
                return AacRemux(0L, 0L)
            }
            m4a.writeBytes(adts.readBytes())
            return AacRemux(sampleDurationMs = remuxDurationMs, outputBytes = m4a.length())
        }
    }

    private class FakeUnresolvedInterruption : UnresolvedInterruption {
        var marked = false
        override fun markUnresolved(): Boolean {
            marked = true
            return true
        }

        override fun isUnresolved(): Boolean = marked
    }

    private class FakeAudioRecorderFactory(
        private val bytesToWrite: ByteArray = AUDIO_BYTES,
        private val startError: RuntimeException? = null,
        private val finishError: RuntimeException? = null,
        private val silenced: SilencedFact = SilencedFact.NOT_SILENCED,
    ) : AudioRecorderFactory {
        override fun create(output: File): AudioRecording =
            FakeAudioRecording(output, bytesToWrite, startError, finishError, silenced)
    }

    private class FakeAudioRecording(
        private val output: File,
        private val bytesToWrite: ByteArray,
        private val startError: RuntimeException?,
        private val finishError: RuntimeException?,
        private val silenced: SilencedFact,
    ) : AudioRecording {
        override fun start() {
            startError?.let { throw it }
            output.parentFile?.mkdirs()
            output.writeBytes(bytesToWrite)
        }

        override fun finish(): RecordingFinishResult =
            finishError?.let { RecordingFinishResult.Failure(it) }
                ?: RecordingFinishResult.Success(output.length())

        override fun discard() {
            output.delete()
        }

        override fun silenced(): SilencedFact = silenced
    }

    private class BlockingFinishAudioRecorderFactory(
        private val finishEntered: CountDownLatch,
        private val releaseFinish: CountDownLatch,
    ) : AudioRecorderFactory {
        override fun create(output: File): AudioRecording =
            object : AudioRecording {
                override fun start() {
                    output.parentFile?.mkdirs()
                    output.writeBytes(AUDIO_BYTES)
                }

                override fun finish(): RecordingFinishResult {
                    finishEntered.countDown()
                    releaseFinish.await(1, TimeUnit.SECONDS)
                    return RecordingFinishResult.Success(output.length())
                }

                override fun discard() {
                    output.delete()
                }

                override fun silenced(): SilencedFact = SilencedFact.SILENCED
            }
    }

    private class StopStartOverlapRecorderFactory(
        private val firstStartEntered: CountDownLatch,
        private val allowFirstStart: CountDownLatch,
        private val oldStartReturned: ThreadLocal<Boolean>,
    ) : AudioRecorderFactory {
        private val creations = AtomicInteger()

        override fun create(output: File): AudioRecording =
            if (creations.getAndIncrement() == 0) {
                object : AudioRecording {
                    override fun start() {
                        firstStartEntered.countDown()
                        awaitIgnoringInterrupts(allowFirstStart)
                        output.parentFile?.mkdirs()
                        output.writeBytes(AUDIO_BYTES)
                        oldStartReturned.set(true)
                    }

                    override fun finish(): RecordingFinishResult = RecordingFinishResult.Success(output.length())

                    override fun discard() {
                        output.delete()
                    }

                    override fun silenced(): SilencedFact = SilencedFact.NOT_SILENCED
                }
            } else {
                FakeAudioRecording(
                    output = output,
                    bytesToWrite = AUDIO_BYTES,
                    startError = null,
                    finishError = null,
                    silenced = SilencedFact.SILENCED,
                )
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

    private fun waitForCondition(predicate: () -> Boolean) {
        repeat(200) {
            if (predicate()) return
            Thread.sleep(5L)
        }
        throw AssertionError("condition did not become true")
    }

    private companion object {
        fun awaitIgnoringInterrupts(latch: CountDownLatch) {
            while (true) {
                try {
                    latch.await()
                    return
                } catch (_: InterruptedException) {
                }
            }
        }

        val UTC: ZoneId = ZoneId.of("UTC")
        const val OFF_BOUNDARY_EPOCH_MS = BASE_CAPTURE_EPOCH_MS + 137_000L
        val AUDIO_BYTES = "fake-adts-bytes".encodeToByteArray()
    }
}
