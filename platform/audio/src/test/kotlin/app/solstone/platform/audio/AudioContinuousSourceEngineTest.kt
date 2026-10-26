// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import app.solstone.core.model.SilencedFact
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.GapNoticeStore
import app.solstone.core.spool.GapRead
import app.solstone.core.spool.SealResult
import app.solstone.core.spool.SealedSegmentSink
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AudioContinuousSourceEngineTest {
    @Test
    fun repeatedStartFailureClearsOnGoodStartEvenWhenTheWindowIsEmpty() {
        val permits = java.util.concurrent.Semaphore(0)
        val sleeps = AtomicInteger()
        val starts = AtomicInteger()
        val now = java.util.concurrent.atomic.AtomicLong(OFF_BOUNDARY_EPOCH_MS)
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val engine = createEngine(
            nowProvider = now::get,
            sleeper = {
                sleeps.incrementAndGet()
                permits.acquire()
                now.addAndGet(AudioContinuousSourceEngine.WINDOW_MS)
            },
            recorderFactory = object : AudioRecorderFactory {
                override fun create(output: File): AudioRecording {
                    starts.incrementAndGet()
                    return FakeAudioRecorderFactory(
                        bytesToWrite = byteArrayOf(),
                        startError = if (fail.get()) IllegalStateException("cannot start") else null,
                    ).create(output)
                }
            },
        )
        try {
            engine.start(CapturingSink())
            waitForCondition { sleeps.get() == 1 }
            assertEquals(null, engine.reasonWhenOff())
            permits.release()
            waitForCondition { sleeps.get() == 2 }
            assertEquals(ReasonCode.AUDIO_START_FAILED, engine.reasonWhenOff())
            engine.stop()
            engine.start(CapturingSink())
            waitForCondition { sleeps.get() == 3 }
            assertEquals(ReasonCode.AUDIO_START_FAILED, engine.reasonWhenOff())
            engine.stop()
            fail.set(false)
            engine.start(CapturingSink())
            waitForCondition { sleeps.get() == 4 }
            assertEquals(null, engine.reasonWhenOff())
            permits.release()
            waitForCondition { sleeps.get() == 5 }
            assertEquals(null, engine.reasonWhenOff())
            assertEquals(5, starts.get())
        } finally {
            engine.stop()
        }
    }

    @Test
    fun retiredWorkerFailureCannotCountAgainstNewWorker() {
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldFailureRecorded = CountDownLatch(1)
        val oldWorker = ThreadLocal<Boolean>()
        val starts = AtomicInteger()
        val sleeps = AtomicInteger()
        val permits = java.util.concurrent.Semaphore(0)
        val now = java.util.concurrent.atomic.AtomicLong(OFF_BOUNDARY_EPOCH_MS)
        val root = tempDirectory()
        val gaps = GapNoticeStore(File(root, "gap").toPath(), listener = { _, _, reason, _ ->
            if (oldWorker.get() == true && reason == "recording did not start") oldFailureRecorded.countDown()
        })
        val engine = createEngine(
            outputDirectory = root,
            gaps = gaps,
            nowProvider = now::get,
            sleeper = {
                if (oldWorker.get() == true) throw InterruptedException()
                sleeps.incrementAndGet()
                permits.acquire()
                now.addAndGet(AudioContinuousSourceEngine.WINDOW_MS)
            },
            recorderFactory = object : AudioRecorderFactory {
                override fun create(output: File): AudioRecording {
                    val attempt = starts.incrementAndGet()
                    if (attempt == 1) {
                        oldWorker.set(true)
                        oldEntered.countDown()
                        awaitIgnoringInterrupts(releaseOld)
                        throw IllegalStateException("late failure")
                    }
                    return FakeAudioRecorderFactory(
                        startError = if (attempt > 2) IllegalStateException("one new failure") else null,
                    ).create(output)
                }
            },
        )
        try {
            engine.start(CapturingSink())
            assertTrue(oldEntered.await(1, TimeUnit.SECONDS))
            engine.stop()
            engine.start(CapturingSink())
            waitForCondition { sleeps.get() == 1 }
            releaseOld.countDown()
            assertTrue(oldFailureRecorded.await(1, TimeUnit.SECONDS))
            permits.release()
            waitForCondition { sleeps.get() == 2 }
            assertEquals(null, engine.reasonWhenOff())
        } finally {
            releaseOld.countDown()
            engine.stop()
        }
    }

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
        val failedGaps = gapsOf(failedOutputDirectory)
        val failedEngine = createEngine(
            outputDirectory = failedOutputDirectory,
            gaps = failedGaps,
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
        assertEquals(listOf("recording did not start"), rows(failedGaps).map { it.reason })
        assertTrue(failedOutputDirectory.listFiles { file -> file.isDirectory && file.name.startsWith("rec-") }.isNullOrEmpty())

        val emptyOutputDirectory = tempDirectory()
        val emptySink = CapturingSink()
        val emptyGaps = gapsOf(emptyOutputDirectory)
        val emptyEngine = createEngine(
            outputDirectory = emptyOutputDirectory,
            gaps = emptyGaps,
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = ByteArray(0)),
        )

        emptyEngine.start(emptySink)
        waitForEmissions(emptySink, 1)
        emptyEngine.stop()

        val emptyEmission = emptySink.emissions.single()
        assertTrue(emptyEmission.payloadRefs.isEmpty())
        assertEquals("empty_recording", emptyEmission.gaps.single().detail)
        assertEquals(listOf("empty recording"), rows(emptyGaps).map { it.reason })
        assertTrue(emptyOutputDirectory.listFiles { file -> file.isDirectory && file.name.startsWith("rec-") }.isNullOrEmpty())
    }

    @Test
    fun aFailedEmptyRecordingDiagnosticStillDeletesTheAttempt() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val gaps = GapNoticeStore(File(outputDirectory, "gap").toPath(), beforeWrite = { path ->
            if (path.fileName.toString() == "events.v1") throw java.io.IOException("events")
        })
        val engine = createEngine(
            outputDirectory = outputDirectory,
            gaps = gaps,
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = ByteArray(0)),
        )
        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()
        assertTrue(
            outputDirectory.listFiles { file -> file.isDirectory && file.name.startsWith("rec-") }.isNullOrEmpty(),
        )
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
        assertTrue(rows(gapsOf(outputDirectory)).isEmpty())
    }

    @Test
    fun discardUncommittedMarksInterruptionAndDeletesDir() {
        val outputDirectory = tempDirectory()
        val sink = CapturingSink()
        val gaps = gapsOf(outputDirectory)
        val engine = createEngine(
            outputDirectory = outputDirectory,
            gaps = gaps,
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
        assertEquals(listOf("uncommitted window"), rows(gaps).filter { it.kind == "loss" }.map { it.reason })
        assertFalse(attemptDir.exists())
        assertFalse(File(attemptDir, "gap-loss").exists())

        val unknown = SegmentPayload("audio", emission.payloadRefs.single(), 1L, 2L)
        assertTrue(engine.discardUncommitted(unknown))
        assertEquals(1, rows(gaps).size)

        var fail = true
        val held = tempDirectory()
        val heldGaps = GapNoticeStore(File(held, "gap").toPath(), syncDirectory = { if (fail) throw java.io.IOException("sync") })
        val heldEngine = createEngine(
            outputDirectory = held,
            gaps = heldGaps,
            sleeper = { throw InterruptedException() },
            recorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        )
        val heldSink = CapturingSink()
        heldEngine.start(heldSink)
        waitForEmissions(heldSink, 1)
        heldEngine.stop()
        val heldEmission = heldSink.emissions.single()
        val heldPayload = SegmentPayload(heldEmission.sourceId, heldEmission.payloadRefs.single(), heldEmission.captureStartEpochMs, heldEmission.captureEndEpochMs)
        val heldDir = held.listFiles { file -> file.isDirectory && file.name.startsWith("rec-") }!!.single()
        assertFalse(heldEngine.discardUncommitted(heldPayload))
        assertTrue(heldDir.exists())
        fail = false
        assertTrue(heldEngine.discardUncommitted(heldPayload))
        assertFalse(heldDir.exists())
    }

    @Test
    fun aFailedLossWriteLeavesTheUndeliverableWindow() {
        val outputDirectory = tempDirectory()
        var now = OFF_BOUNDARY_EPOCH_MS
        var sleepCount = 0
        val gaps = GapNoticeStore(File(outputDirectory, "gap").toPath(), beforeWrite = { path ->
            if (path.fileName.toString() == "events.v1") throw java.io.IOException("events")
        })
        val sink = CapturingSink()
        val engine = createEngine(
            outputDirectory = outputDirectory,
            gaps = gaps,
            remuxer = FakeAacRemuxer(remuxDurationMs = 0L),
            nowProvider = { now },
            sleeper = {
                if (sleepCount++ == 0) now = BASE_CAPTURE_EPOCH_MS + AudioContinuousSourceEngine.WINDOW_MS
                else throw InterruptedException()
            },
        )
        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()
        val dirs = outputDirectory.listFiles { file -> file.isDirectory && file.name.startsWith("rec-") }.orEmpty()
        assertTrue(dirs.isNotEmpty())
        assertTrue(dirs.none { File(it, "gap-loss").exists() })
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

    @Test
    fun recordingPastGraceSealsFromItsStart() {
        val start = utc(8, 17, 13)
        val stop = utc(8, 17, 26)
        val (_, sealed) = recordWindowAudio(start, stop)
        assertEquals("081713_13", sealed.key.segment)
        assertEquals(start, sealed.wireKeys.startEpochMs)
        assertEquals(stop, sealed.wireKeys.endEpochMs)
    }

    @Test
    fun separatePipelinesInOneWindowDoNotShareAName() {
        val (_, sealed1) = recordWindowAudio(utc(8, 17, 13), utc(8, 17, 26))
        val (_, sealed2) = recordWindowAudio(utc(8, 17, 40), utc(8, 17, 53))
        val (_, sealed3) = recordWindowAudio(utc(8, 18, 10), utc(8, 18, 23))

        assertEquals("081713_13", sealed1.key.segment)
        assertEquals("081740_13", sealed2.key.segment)
        assertEquals("081810_13", sealed3.key.segment)

        assertTrue(sealed1.wireKeys.endEpochMs <= sealed2.wireKeys.startEpochMs)
        assertTrue(sealed2.wireKeys.endEpochMs <= sealed3.wireKeys.startEpochMs)
    }

    @Test
    fun stopAndNewPipelineNamesEachRecording() {
        val (_, sealedA) = recordWindowAudio(utc(8, 17, 13), utc(8, 18, 0))
        val (_, sealedB) = recordWindowAudio(utc(8, 18, 1), utc(8, 18, 30))

        assertEquals("081713_47", sealedA.key.segment)
        assertEquals("081801_29", sealedB.key.segment)
        assertTrue(sealedA.wireKeys.endEpochMs <= sealedB.wireKeys.startEpochMs)
    }

    @Test
    fun liveSealAndRecoveryShareThePayloadStartName() {
        val outputDirectory = tempDirectory()
        val start = utc(8, 17, 13)
        val stop = utc(8, 17, 26)
        val (_, liveSealed) = recordWindowAudio(start, stop, outputDirectory = outputDirectory)
        assertEquals("081713_13", liveSealed.key.segment)

        val attemptDirs = outputDirectory.listFiles { f -> f.isDirectory && f.name.startsWith("rec-") }
        assertEquals(1, attemptDirs?.size)

        val recoverySink = FakeSealedSegmentSink()
        val spoolDir = tempDirectory().toPath()
        val recoveryGaps = GapNoticeStore(File(outputDirectory, "recovery-gap").toPath())
        AudioRecoveryManager(
            audioSourceDir = outputDirectory,
            cacheAudioDir = null,
            spoolDir = spoolDir,
            occupiedLeaves = { _, _ -> emptySet() },
            lookup = AudioSpoolLookup { _, _, _, _, _, _, _, _ -> AudioCommitStatus.NotFound },
            sealedSink = recoverySink,
            gaps = recoveryGaps,
            remuxer = FakeAacRemuxer(stop - start),
            isReadableM4aProbe = { true },
        ).recover()

        assertEquals(1, recoverySink.persisted.size)
        val recovered = recoverySink.persisted.single()
        assertEquals(liveSealed.key.segment, recovered.key.segment)
        assertEquals("081713_13", recovered.key.segment)
    }

    private fun utc(hour: Int, minute: Int, second: Int, millis: Int = 0): Long =
        java.time.LocalDateTime.of(2026, 3, 4, hour, minute, second)
            .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + millis

    private fun recordWindowAudio(
        startEpochMs: Long,
        stopEpochMs: Long,
        outputDirectory: File = tempDirectory(),
    ): Pair<SourceEmission, SealedSegment> {
        val sink = CapturingSink()
        var now = startEpochMs
        val durationMs = stopEpochMs - startEpochMs
        val engine = createEngine(
            outputDirectory = outputDirectory,
            remuxer = FakeAacRemuxer(remuxDurationMs = durationMs),
            nowProvider = { now },
            sleeper = {
                now = stopEpochMs
                throw InterruptedException()
            },
            zoneProvider = { ZoneId.of("UTC") },
        )
        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        assertTrue(emission.payloadRefs.isNotEmpty(), "expected non-empty payload in emission")
        assertEquals(startEpochMs, emission.captureStartEpochMs)

        val segmenter = Segmenter(ZoneId.of("UTC"))
        val seals = segmenter.feed(emission).sealed + segmenter.flush().sealed
        val sealed = seals.single()
        return emission to sealed
    }

    private class FakeSealedSegmentSink : SealedSegmentSink {
        val persisted = mutableListOf<SealedSegment>()
        override fun persistSealed(
            segment: SealedSegment,
            result: SealResult,
            sealedAtEpochMs: Long,
        ) {
            persisted += segment
        }
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
        gaps: GapNoticeStore? = null,
        nowProvider: () -> Long = { OFF_BOUNDARY_EPOCH_MS },
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
        recorderFactory: AudioRecorderFactory = FakeAudioRecorderFactory(bytesToWrite = AUDIO_BYTES),
        zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
        diag: (String) -> Unit = {},
    ): AudioContinuousSourceEngine = AudioContinuousSourceEngine(
        outputDirectory = outputDirectory,
        storageStatus = storageStatus,
        remuxer = remuxer,
        gaps = gaps ?: GapNoticeStore(File(outputDirectory, "gap").toPath()),
        nowProvider = nowProvider,
        sleeper = sleeper,
        recorderFactory = recorderFactory,
        zoneProvider = zoneProvider,
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

    private fun gapsOf(root: File) = GapNoticeStore(File(root, "gap").toPath())

    private fun rows(store: GapNoticeStore) =
        assertIs<GapRead.Ready>(store.read(java.time.ZoneId.of("UTC"))).rows

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
