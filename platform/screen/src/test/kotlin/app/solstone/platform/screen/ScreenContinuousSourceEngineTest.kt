// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import android.content.Intent
import android.util.DisplayMetrics
import app.solstone.core.model.ReasonCode
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.GapNoticeStore
import app.solstone.platform.fgs.ScreenConsentToken
import app.solstone.platform.power.StorageStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenContinuousSourceEngineTest {

    private class FakeEncoder(private val firstFrame: Long = 1_700_000_000_050L, private val full: Boolean = false) : ScreenEncoder {
        var started = false
        var stopped = false
        var released = false

        override fun inputSurface(): Any = "fake-surface"
        override fun sizeLimitReached(): Boolean = full

        override fun start(outputH264: File, outputIndex: File, onFirstFrame: (firstFrameEpochMs: Long) -> Unit) {
            started = true
            outputH264.writeBytes(ByteArray(100))
            outputIndex.writeBytes(ByteArray(20))
            onFirstFrame(firstFrame)
        }

        override fun stop(): Long {
            stopped = true
            return 10L
        }

        override fun release() {
            released = true
        }
    }

    private class FakeRemuxer(
        var result: ScreenRemuxResult = ScreenRemuxResult.Success(sampleDurationMs = 5_000L, outputBytes = 500L),
    ) : ScreenRemuxer {
        override fun remux(dir: File, h264File: File, indexFile: File, recordFile: File, mp4OutputFile: File): ScreenRemuxResult {
            if (result is ScreenRemuxResult.Success) {
                mp4OutputFile.writeBytes(ByteArray(500))
            }
            return result
        }
    }

    private fun gapsOf(root: File) = GapNoticeStore(File(root, "gap").toPath())

    private class SimpleVirtualDisplay : ScreenVirtualDisplay {
        override fun setSurface(surface: Any?) {}
        override fun resize(width: Int, height: Int, dpi: Int) {}
        override fun release() {}
    }

    private class SimpleProjection : ScreenProjection {
        override fun registerCallback(callback: ScreenProjectionCallback) {}
        override fun createVirtualDisplay(
            name: String,
            width: Int,
            height: Int,
            dpi: Int,
            surface: Any?,
            flags: Int,
        ): ScreenVirtualDisplay = SimpleVirtualDisplay()
        override fun stop() {}
    }

    private class SimpleProjectionManager : ScreenProjectionManager {
        override val getMediaProjectionCount: Int = 1
        override val createVirtualDisplayCount: Int = 1
        override fun acquireProjection(token: ScreenConsentToken): ScreenProjection = SimpleProjection()
    }

    private enum class FakeWish { Off, On }

    @Test
    fun stoppedCutoffSurvivesBlockedDrainLateFirstFrameAndRecovery() {
        val root = Files.createTempDirectory("screen-cutoff").toFile()
        val source = File(root, "live").apply { mkdirs() }
        val sleeping = java.util.concurrent.CountDownLatch(1)
        val draining = java.util.concurrent.CountDownLatch(1)
        val finishDrain = java.util.concurrent.CountDownLatch(1)
        val emitted = java.util.concurrent.CountDownLatch(1)
        val time = java.util.concurrent.atomic.AtomicLong(1_700_000_000_100L)
        lateinit var firstFrame: (Long) -> Unit
        val holder = ScreenSessionHolder(SimpleProjectionManager(), {
            DisplayMetrics().apply { widthPixels = 720; heightPixels = 1280; densityDpi = 320 }
        }, {})
        assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 1L)))
        val encoder = object : ScreenEncoder {
            override fun inputSurface(): Any = "surface"
            override fun start(raw: File, index: File, callback: (Long) -> Unit) {
                raw.writeBytes(ByteArray(100)); index.writeBytes(ByteArray(20)); firstFrame = callback
            }
            override fun stop(): Long {
                draining.countDown()
                while (true) {
                    try { finishDrain.await(); break } catch (_: InterruptedException) {}
                }
                return 1L
            }
            override fun release() {}
        }
        val emissions = java.util.concurrent.CopyOnWriteArrayList<SourceEmission>()
        val engine = ScreenContinuousSourceEngine(source, StorageStatus({ 100_000L }, 1L),
            FakeRemuxer(), gapsOf(root), holder, encoderFactory = { _, _, _, _ -> encoder },
            nowProvider = time::get, sleeper = { sleeping.countDown(); Thread.sleep(60_000L) },
            stopJoinTimeoutMs = 20L)
        try {
            engine.start(EmissionSink { emissions += it; emitted.countDown() })
            assertTrue(sleeping.await(3, java.util.concurrent.TimeUnit.SECONDS))
            time.set(1_700_000_000_500L)
            engine.stop()
            assertTrue(draining.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val attempt = source.listFiles()!!.single()
            val record = File(attempt, "record.txt")
            assertEquals(1_700_000_000_500L, ScreenRecordInfo.parse(record.readText())!!.openEndEpochMs)
            time.set(1_700_000_012_000L)
            firstFrame(1_700_000_000_050L)
            assertEquals(1_700_000_000_500L, ScreenRecordInfo.parse(record.readText())!!.openEndEpochMs)

            // This independent copy is all a process killed during drain would leave.
            val crashed = File(root, "crashed").apply { mkdirs() }
            attempt.copyRecursively(File(crashed, attempt.name))
            val adopted = mutableListOf<app.solstone.core.segment.SealedSegment>()
            val recovery = ScreenRecoveryManager(crashed, File(root, "spool").toPath(), { _, _ -> emptySet() },
                app.solstone.platform.audio.AudioSpoolLookup { _, _, _, _, _, _, _, _ ->
                    app.solstone.platform.audio.AudioCommitStatus.NotFound },
                { segment, _, _ -> adopted += segment }, gapsOf(root), FakeRemuxer())
            recovery.recover(); recovery.recover()
            assertEquals(1, adopted.size)
            assertEquals(1_700_000_000_050L, adopted.single().wireKeys.startEpochMs)
            assertEquals(1_700_000_000_500L, adopted.single().wireKeys.endEpochMs)

            finishDrain.countDown()
            engine.stop()
            assertTrue(emitted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1_700_000_000_500L, emissions.single().captureEndEpochMs)
        } finally {
            finishDrain.countDown(); engine.stop(); holder.stopSession(); root.deleteRecursively()
        }
    }

    @Test
    fun sizeBoundarySealsAPortionAndContinuesInTheSameConsentAndGridWindow() {
        val root = Files.createTempDirectory("screen-size-rollover").toFile()
        val secondStarted = java.util.concurrent.CountDownLatch(1)
        val firstEmitted = java.util.concurrent.CountDownLatch(1)
        val emitted = java.util.concurrent.CopyOnWriteArrayList<SourceEmission>()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val time = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        val holder = ScreenSessionHolder(SimpleProjectionManager(), {
            DisplayMetrics().apply { widthPixels = 720; heightPixels = 1280; densityDpi = 320 }
        }, {})
        assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 1L)))
        val engine = ScreenContinuousSourceEngine(root, StorageStatus({ 100_000L }, 1L),
            FakeRemuxer(), gapsOf(root), holder,
            encoderFactory = { _, _, _, _ ->
                val first = time.getAndAdd(1_000L) + 50L
                FakeEncoder(first, full = count.incrementAndGet() == 1)
            }, nowProvider = time::get, sleeper = {
                secondStarted.countDown(); Thread.sleep(60_000L)
            })
        try {
            engine.start(EmissionSink { emitted += it; firstEmitted.countDown() })
            assertTrue(secondStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(firstEmitted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, emitted.size)
            assertTrue(holder.isSessionLive())
            assertEquals(null, engine.reasonWhenOff())
            engine.stop()
            assertEquals(2, emitted.size)
            assertEquals(1_700_000_000_050L, emitted[0].captureStartEpochMs)
            assertEquals(1_700_000_001_050L, emitted[1].captureStartEpochMs)
            assertEquals(ScreenContinuousSourceEngine.windowStart(emitted[0].captureStartEpochMs),
                ScreenContinuousSourceEngine.windowStart(emitted[1].captureStartEpochMs))
        } finally { engine.stop(); holder.stopSession(); root.deleteRecursively() }
    }

    @Test
    fun freshSessionQueuesBehindSlowOldFinalizationAndStartsAfterItCloses() {
        val root = Files.createTempDirectory("screen-slow-stop").toFile()
        val sleeping = java.util.concurrent.LinkedBlockingQueue<Int>()
        val remuxEntered = java.util.concurrent.CountDownLatch(1)
        val releaseRemux = java.util.concurrent.CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger()
        val time = java.util.concurrent.atomic.AtomicLong(1_700_000_000_050L)
        val holder = ScreenSessionHolder(SimpleProjectionManager(), {
            DisplayMetrics().apply { widthPixels = 720; heightPixels = 1280; densityDpi = 320 }
        }, {})
        val remuxer = ScreenRemuxer { _, _, _, _, output ->
            if (count.get() == 1) { remuxEntered.countDown(); releaseRemux.await() }
            output.writeBytes(ByteArray(500))
            ScreenRemuxResult.Success(200L, 500L)
        }
        val engine = ScreenContinuousSourceEngine(root, StorageStatus({ 100_000L }, 1L), remuxer,
            gapsOf(root), holder, encoderFactory = { _, _, _, _ ->
                count.incrementAndGet(); FakeEncoder(time.get())
            }, nowProvider = time::get, sleeper = {
                sleeping.put(count.get()); Thread.sleep(60_000L)
            }, stopJoinTimeoutMs = 20L)
        try {
            assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 1L)))
            engine.start(EmissionSink {})
            assertEquals(1, sleeping.poll(3, java.util.concurrent.TimeUnit.SECONDS))
            time.addAndGet(500L)
            engine.stop()
            assertTrue(remuxEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            holder.stopSession()
            assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 2L)))
            time.addAndGet(1_000L)
            engine.start(EmissionSink {})
            releaseRemux.countDown()
            assertEquals(2, sleeping.poll(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(holder.isSessionLive())
        } finally {
            releaseRemux.countDown(); time.addAndGet(500L); engine.stop(); holder.stopSession()
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptDuringRecordingClosesEncoderAndEmitsThePlayablePrefix() {
        val root = Files.createTempDirectory("screen-stop").toFile()
        val enteredSleep = java.util.concurrent.CountDownLatch(1)
        val emitted = java.util.concurrent.CopyOnWriteArrayList<SourceEmission>()
        val encoder = FakeEncoder()
        val holder = ScreenSessionHolder(SimpleProjectionManager(), {
            DisplayMetrics().apply { widthPixels = 720; heightPixels = 1280; densityDpi = 320 }
        }, {})
        assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 1L)))
        val engine = ScreenContinuousSourceEngine(root, StorageStatus({ 100_000L }, 1L),
            FakeRemuxer(), gapsOf(root), holder,
            encoderFactory = { _, _, _, _ -> encoder },
            nowProvider = { 1_700_000_000_500L },
            sleeper = { enteredSleep.countDown(); Thread.sleep(60_000L) })
        try {
            engine.start(EmissionSink { emitted += it })
            assertTrue(enteredSleep.await(3, java.util.concurrent.TimeUnit.SECONDS))
            engine.stop()
            assertTrue(encoder.stopped)
            assertTrue(encoder.released)
            assertEquals(1, emitted.size)
            assertEquals(1_700_000_000_050L, emitted.single().captureStartEpochMs)
            assertEquals(1_700_000_000_500L, emitted.single().captureEndEpochMs)
            assertTrue(emitted.single().independentTimeline)
            assertEquals(1, engine.inFlightRecordingIds().size)
        } finally { engine.stop(); holder.stopSession(); root.deleteRecursively() }
    }

    @Test
    fun storageFloorDuringWindowStopsSessionRemuxesPlayablePrefixAndLeavesOtherWishUntouched() {
        val tempDir = Files.createTempDirectory(Path.of("/var/tmp"), "screen-engine-test-").toFile()
        try {
            var freeBytes = 2_000L
            val storage = StorageStatus({ freeBytes }, minimumFreeBytes = 1_000L)
            val remuxer = FakeRemuxer()
            val gaps = gapsOf(tempDir)
            val holder = ScreenSessionHolder(
                projectionManager = SimpleProjectionManager(),
                getDisplayMetrics = {
                    DisplayMetrics().apply {
                        widthPixels = 720
                        heightPixels = 1280
                        densityDpi = 320
                    }
                },
                onSessionEnded = {},
            )
            val token = ScreenConsentToken(resultCode = -1, data = Intent(), generation = 1L)
            assertTrue(holder.startSession(service = null, token = token))
            assertTrue(holder.isSessionLive())

            var floorTrippedCalled = false
            var simulatedTime = 1_700_000_000_000L // Window start
            var anotherSourceWish = FakeWish.On

            val emissions = mutableListOf<SourceEmission>()

            val engine = ScreenContinuousSourceEngine(
                outputDirectory = tempDir,
                storageStatus = storage,
                remuxer = remuxer,
                gaps = gaps,
                sessionHolder = holder,
                encoderFactory = { _, _, _, _ -> FakeEncoder() },
                onFloorTripped = { floorTrippedCalled = true },
                onFailed = {},
                nowProvider = { simulatedTime },
                sleeper = { delta ->
                    // During recording sleep, trip storage floor
                    freeBytes = 500L
                    simulatedTime += delta
                },
                zoneProvider = { ZoneId.of("UTC") },
            )

            engine.start(EmissionSink { emissions += it })

            // Wait for worker to stop after floor trip
            var tries = 0
            while (holder.isSessionLive() && tries++ < 50) {
                Thread.sleep(20)
            }

            engine.stop()

            // Verification:
            // 1. Session is stopped
            assertFalse(holder.isSessionLive())
            // 2. Playable prefix was remuxed and emitted
            assertEquals(1, emissions.size)
            assertEquals(ScreenConstants.SOURCE_ID, emissions.single().sourceId)
            assertEquals(MAIN_STREAM, emissions.single().stream)
            // 3. Storage floor tripped hook called and reasonWhenOff is STORAGE_FULL
            assertTrue(floorTrippedCalled)
            assertEquals(ReasonCode.STORAGE_FULL, engine.reasonWhenOff())
            assertFalse(engine.condition().available)
            // 4. Other source's wish untouched
            assertEquals(FakeWish.On, anotherSourceWish)
            freeBytes = 2_000L
            assertEquals(null, engine.reasonWhenOff())
            assertTrue(engine.condition().available)
            assertFalse(engine.condition().needsAttention)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun failedReasonStaysAfterStopClearsOnTheNextStartAndAMarkerDoesNotShow() {
        val root = Files.createTempDirectory("screen-failed-reason").toFile()
        File(root, "screen-interruption").writeText("legacy")
        val time = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        val holder = ScreenSessionHolder(SimpleProjectionManager(), {
            DisplayMetrics().apply { widthPixels = 720; heightPixels = 1280; densityDpi = 320 }
        }, {})
        assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 1L)))
        var fail = true
        val engine = ScreenContinuousSourceEngine(
            root,
            StorageStatus({ 100_000L }, 1L),
            FakeRemuxer(),
            gapsOf(root),
            holder,
            encoderFactory = { _, _, _, _ ->
                object : ScreenEncoder {
                    override fun inputSurface(): Any = "surface"
                    override fun start(raw: File, index: File, callback: (Long) -> Unit) {
                        if (fail) error("encoder failed")
                        raw.writeBytes(ByteArray(16))
                        index.writeBytes(ByteArray(8))
                        callback(time.get())
                    }
                    override fun stop(): Long = 1L
                    override fun release() {}
                }
            },
            nowProvider = time::get,
            sleeper = { delta -> time.addAndGet(delta.coerceAtLeast(1L)) },
            stopJoinTimeoutMs = 2_000L,
        )
        try {
            assertEquals(null, engine.reasonWhenOff())
            engine.start(EmissionSink { })
            val deadline = System.currentTimeMillis() + 3_000L
            while (engine.reasonWhenOff() == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(ReasonCode.SCREEN_CAPTURE_FAILED, engine.reasonWhenOff())
            engine.stop()
            assertEquals(ReasonCode.SCREEN_CAPTURE_FAILED, engine.reasonWhenOff())
            fail = false
            assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 2L)))
            engine.start(EmissionSink { })
            assertEquals(null, engine.reasonWhenOff())
            engine.stop()
            val fresh = ScreenContinuousSourceEngine(
                root,
                StorageStatus({ 100_000L }, 1L),
                FakeRemuxer(),
                gapsOf(root),
                holder,
            )
            assertEquals(null, fresh.reasonWhenOff())
        } finally {
            engine.stop()
            holder.stopSession()
            root.deleteRecursively()
        }
    }

    @Test
    fun aStopperThatAlreadyEndedTheSessionKeepsCaptureFailed() {
        val root = Files.createTempDirectory("screen-stopper-failed").toFile()
        val time = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        val holder = ScreenSessionHolder(SimpleProjectionManager(), {
            DisplayMetrics().apply { widthPixels = 720; heightPixels = 1280; densityDpi = 320 }
        }, {})
        assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 1L)))
        val engine = ScreenContinuousSourceEngine(
            root,
            StorageStatus({ 100_000L }, 1L),
            FakeRemuxer(),
            gapsOf(root),
            holder,
            encoderFactory = { _, _, _, _ ->
                object : ScreenEncoder {
                    override fun inputSurface(): Any = "surface"
                    override fun start(raw: File, index: File, callback: (Long) -> Unit) {
                        holder.stopSession()
                        error("encoder failed")
                    }
                    override fun stop(): Long = 1L
                    override fun release() {}
                }
            },
            nowProvider = time::get,
            sleeper = { delta -> time.addAndGet(delta.coerceAtLeast(1L)) },
            stopJoinTimeoutMs = 2_000L,
        )
        try {
            engine.start(EmissionSink { })
            val deadline = System.currentTimeMillis() + 3_000L
            while (engine.reasonWhenOff() == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(ReasonCode.SCREEN_CAPTURE_FAILED, engine.reasonWhenOff())
        } finally {
            engine.stop()
            holder.stopSession()
            root.deleteRecursively()
        }
    }

    @Test
    fun aHealthyRunStoppedExternallyDoesNotBecomeAFailure() {
        val root = Files.createTempDirectory("screen-clean-stop").toFile()
        val time = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        val holder = ScreenSessionHolder(SimpleProjectionManager(), {
            DisplayMetrics().apply { widthPixels = 720; heightPixels = 1280; densityDpi = 320 }
        }, {})
        assertTrue(holder.startSession(token = ScreenConsentToken(-1, Intent(), 1L)))
        val engine = ScreenContinuousSourceEngine(
            root,
            StorageStatus({ 100_000L }, 1L),
            FakeRemuxer(),
            gapsOf(root),
            holder,
            encoderFactory = { _, _, _, _ ->
                object : ScreenEncoder {
                    override fun inputSurface(): Any = "surface"
                    override fun start(raw: File, index: File, callback: (Long) -> Unit) {
                        holder.stopSession()
                        callback(time.get())
                    }
                    override fun stop(): Long = 1L
                    override fun release() {}
                }
            },
            nowProvider = time::get,
            sleeper = { delta -> time.addAndGet(delta.coerceAtLeast(1L)) },
            stopJoinTimeoutMs = 2_000L,
        )
        try {
            engine.start(EmissionSink { })
            val deadline = System.currentTimeMillis() + 3_000L
            while (engine.condition().running && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(null, engine.reasonWhenOff())
            assertFalse(engine.condition().running)
        } finally {
            engine.stop()
            holder.stopSession()
            root.deleteRecursively()
        }
    }
}
