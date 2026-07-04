// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.camera.still

import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.SourceEmission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StillCaptureEngineTest {
    @Test
    fun successCapturesPayloadAndReleasesLock() {
        val bytes = "jpeg-a".encodeToByteArray()
        val lock = RecordingCameraLock()
        val sink = CapturingSink()
        val sleeps = mutableListOf<Long>()
        val engine = StillCaptureEngine(
            stillCamera = FakeStillCamera(listOf(StillCaptureResult.Image(bytes))),
            cameraLock = lock,
            nowProvider = { BASE_EPOCH_MS },
            stillEveryMs = STILL_EVERY_MS,
            sleeper = { millis ->
                sleeps += millis
                throw InterruptedException()
            },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        assertEquals(MAIN_STREAM, emission.stream)
        assertEquals(StillCaptureEngine.SOURCE_ID, emission.sourceId)
        assertEquals(SourceKind.OBSERVER, emission.sourceKind)
        assertTrue(emission.gaps.isEmpty())
        val ref = emission.payloadRefs.single()
        assertEquals("image/jpeg", ref.mediaType)
        assertEquals(bytes.size.toLong(), ref.byteSize)
        assertEquals("camera-$BASE_EPOCH_MS-0.jpg", ref.name)
        assertEquals(listOf(STILL_EVERY_MS), sleeps)
        assertEquals(listOf("acquire", "release"), lock.events)

        val payload = SegmentPayload(emission.sourceId, ref, emission.captureStartEpochMs, emission.captureEndEpochMs)
        assertContentEquals(bytes, engine.open(payload).use { it.readBytes() })
        assertFailsWith<IllegalArgumentException> { engine.open(payload) }
    }

    @Test
    fun releaseRemovesDroppedBytes() {
        val bytes = "jpeg-a".encodeToByteArray()
        val sink = CapturingSink()
        val engine = StillCaptureEngine(
            stillCamera = FakeStillCamera(listOf(StillCaptureResult.Image(bytes))),
            nowProvider = { BASE_EPOCH_MS },
            sleeper = { throw InterruptedException() },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        val payload = SegmentPayload(emission.sourceId, emission.payloadRefs.single(), emission.captureStartEpochMs, emission.captureEndEpochMs)
        engine.release(payload)

        assertFailsWith<IllegalArgumentException> { engine.open(payload) }
    }

    @Test
    fun heldLockEmitsBusyGapAndDoesNotReleaseUnacquiredLock() {
        val lock = RecordingCameraLock(acquireResult = false)
        val sink = CapturingSink()
        val engine = StillCaptureEngine(
            stillCamera = FakeStillCamera(listOf(StillCaptureResult.Image("unused".encodeToByteArray()))),
            cameraLock = lock,
            nowProvider = { BASE_EPOCH_MS },
            sleeper = { throw InterruptedException() },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        assertTrue(emission.payloadRefs.isEmpty())
        assertEquals("capture_gap", emission.gaps.single().kind)
        assertEquals("camera_busy", emission.gaps.single().detail)
        assertEquals(listOf("acquire"), lock.events)
    }

    @Test
    fun failedCaptureEmitsFailureGapAndReleasesLock() {
        val lock = RecordingCameraLock()
        val sink = CapturingSink()
        val engine = StillCaptureEngine(
            stillCamera = FakeStillCamera(listOf(StillCaptureResult.Failure(IllegalStateException("boom")))),
            cameraLock = lock,
            nowProvider = { BASE_EPOCH_MS },
            sleeper = { throw InterruptedException() },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        assertTrue(emission.payloadRefs.isEmpty())
        assertEquals("capture_gap", emission.gaps.single().kind)
        assertEquals("capture_failed type=IllegalStateException message=boom", emission.gaps.single().detail)
        assertEquals(listOf("acquire", "release"), lock.events)
    }

    @Test
    fun emptyCaptureEmitsFailureGap() {
        val sink = CapturingSink()
        val engine = StillCaptureEngine(
            stillCamera = FakeStillCamera(listOf(StillCaptureResult.Image(ByteArray(0)))),
            nowProvider = { BASE_EPOCH_MS },
            sleeper = { throw InterruptedException() },
        )

        engine.start(sink)
        waitForEmissions(sink, 1)
        engine.stop()

        val emission = sink.emissions.single()
        assertTrue(emission.payloadRefs.isEmpty())
        assertEquals("empty_image", emission.gaps.single().detail)
    }

    @Test
    fun sameMillisecondCapturesHaveDistinctNames() {
        val sink = CapturingSink()
        val sleeps = mutableListOf<Long>()
        val engine = StillCaptureEngine(
            stillCamera = FakeStillCamera(
                    listOf(
                        StillCaptureResult.Image("jpeg-a".encodeToByteArray()),
                        StillCaptureResult.Image("jpeg-b".encodeToByteArray()),
                    ),
                ),
            nowProvider = { BASE_EPOCH_MS },
            sleeper = { millis ->
                sleeps += millis
                if (sleeps.size == 2) throw InterruptedException()
            },
        )

        engine.start(sink)
        waitForEmissions(sink, 2)
        engine.stop()

        val names = sink.emissions.map { it.payloadRefs.single().name }
        assertEquals(listOf("camera-$BASE_EPOCH_MS-0.jpg", "camera-$BASE_EPOCH_MS-1.jpg"), names)
        assertEquals(2, names.distinct().size)
        assertEquals(60_000L, StillCaptureEngine.STILL_EVERY_MS)
        assertEquals(listOf(StillCaptureEngine.STILL_EVERY_MS, StillCaptureEngine.STILL_EVERY_MS), sleeps)
    }

    @Test
    fun singleHolderLockIsReacquirableAfterRelease() {
        val lock = SingleHolderCameraLock()
        assertTrue(lock.tryAcquire())
        assertFalse(lock.tryAcquire())
        lock.release()
        assertTrue(lock.tryAcquire())
        lock.release()
    }

    @Test
    fun workerDeathEmitsTerminalGapAndDiagAndReportsNotRunning() {
        val sink = CapturingSink()
        val diags = CopyOnWriteArrayList<String>()
        val engine = StillCaptureEngine(
            stillCamera = ThrowingStillCamera,
            nowProvider = { BASE_EPOCH_MS },
            diag = diags::add,
        )

        engine.start(sink)
        waitForEmissions(sink, 1)

        assertFalse(engine.condition().running)
        assertEquals("engine_failed type=IllegalStateException message=camera died", sink.emissions.single().gaps.single().detail)
        assertTrue("capture event=engine-failed source=camera type=IllegalStateException message=camera died" in diags)
    }

    @Test
    fun guardedEmitCapturesRejectedExecutionAndStopsWorker() {
        val diags = CopyOnWriteArrayList<String>()
        val engine = StillCaptureEngine(
            stillCamera = FakeStillCamera(listOf(StillCaptureResult.Image("jpeg".encodeToByteArray()))),
            nowProvider = { BASE_EPOCH_MS },
            sleeper = { throw InterruptedException() },
            diag = diags::add,
        )

        engine.start(ThrowingSink)
        waitForDiag(diags, "capture event=emit-failed source=camera type=RejectedExecutionException message=closed")

        assertFalse(engine.condition().running)
    }

    @Test
    fun stopTimeoutZombieCannotEmitAfterRestart() {
        val camera = InterruptSwallowingStillCamera()
        val sink = CapturingSink()
        val engine = StillCaptureEngine(
            stillCamera = camera,
            cameraLock = NonExclusiveCameraLock,
            nowProvider = { BASE_EPOCH_MS },
            stillEveryMs = STILL_EVERY_MS,
        )

        engine.start(sink)
        assertTrue(camera.firstEntered.await(5, TimeUnit.SECONDS))
        val started = System.nanoTime()
        engine.stop()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsedMs < 6_500L, "stop took ${elapsedMs}ms")

        engine.start(sink)
        waitForEmissions(sink, 1)
        camera.releaseZombie()
        Thread.sleep(100L)
        engine.stop()

        assertEquals(1, sink.emissions.size)
        val payload = SegmentPayload(
            sink.emissions.single().sourceId,
            sink.emissions.single().payloadRefs.single(),
            sink.emissions.single().captureStartEpochMs,
            sink.emissions.single().captureEndEpochMs,
        )
        assertContentEquals("fresh".encodeToByteArray(), engine.open(payload).use { it.readBytes() })
    }

    private class FakeStillCamera(private val results: List<StillCaptureResult>) : StillCamera {
        private var index = 0

        override fun takeStill(): StillCaptureResult =
            results.getOrElse(index++) { results.lastOrNull() }
                ?: StillCaptureResult.Failure(IllegalStateException("no still result"))
    }

    private class RecordingCameraLock(private val acquireResult: Boolean = true) : CameraLock {
        val events = CopyOnWriteArrayList<String>()
        private val held = AtomicBoolean(false)

        override fun tryAcquire(): Boolean {
            events += "acquire"
            if (!acquireResult) return false
            held.set(true)
            return true
        }

        override fun release() {
            events += "release"
            held.set(false)
        }
    }

    private object ThrowingStillCamera : StillCamera {
        override fun takeStill(): StillCaptureResult {
            throw IllegalStateException("camera died")
        }
    }

    private class InterruptSwallowingStillCamera : StillCamera {
        val firstEntered = CountDownLatch(1)
        private val releaseZombie = CountDownLatch(1)
        private val calls = AtomicInteger(0)

        override fun takeStill(): StillCaptureResult {
            return if (calls.getAndIncrement() == 0) {
                firstEntered.countDown()
                try {
                    Thread.sleep(60_000L)
                } catch (_: InterruptedException) {
                    // This fake deliberately swallows interruption to model a stuck backend.
                }
                releaseZombie.await()
                StillCaptureResult.Image("zombie".encodeToByteArray())
            } else {
                StillCaptureResult.Image("fresh".encodeToByteArray())
            }
        }

        fun releaseZombie() {
            releaseZombie.countDown()
        }
    }

    private object NonExclusiveCameraLock : CameraLock {
        override fun tryAcquire(): Boolean = true

        override fun release() = Unit
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

    private fun waitForEmissions(sink: CapturingSink, count: Int) {
        repeat(200) {
            if (sink.emissions.size >= count) return
            Thread.sleep(5L)
        }
        throw AssertionError("expected $count emissions, got ${sink.emissions.size}")
    }

    private fun waitForDiag(lines: List<String>, expected: String) {
        repeat(200) {
            if (expected in lines) return
            Thread.sleep(5L)
        }
        throw AssertionError("missing diag: $expected in $lines")
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
        const val STILL_EVERY_MS = 60_000L
    }
}
