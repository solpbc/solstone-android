// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealResult
import app.solstone.core.spool.SealState
import app.solstone.core.spool.SealedSegmentSink
import app.solstone.core.spool.SpoolWriter
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapturePipelineTest {
    @Test
    fun startedEpochMsUsesClockOnlyForFirstSuccessfulStart() {
        val now = AtomicLong(BASE_EPOCH_MS)
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = ThreadCapturingSpoolWriter(AtomicBoolean(true)),
            sealedSink = ThreadCapturingSink(),
            payloadBytes = ByteArrayPayloadProvider(),
            engines = emptyList(),
            nowProvider = now::get,
            tickIntervalMs = 10_000L,
        )

        assertEquals(null, pipeline.startedEpochMs())
        pipeline.start()
        assertEquals(BASE_EPOCH_MS, pipeline.startedEpochMs())
        now.incrementAndGet()
        pipeline.start()
        assertEquals(BASE_EPOCH_MS, pipeline.startedEpochMs())
        pipeline.stop()
    }

    @Test
    fun lastEmissionEpochMsStartsNullAndUpdatesWhenEmissionArrives() {
        val engine = JoiningEngine()
        val writer = ThreadCapturingSpoolWriter(engine.joined)
        val sink = ThreadCapturingSink()
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = writer,
            sealedSink = sink,
            payloadBytes = ByteArrayPayloadProvider(),
            engines = listOf(engine),
            nowProvider = { EMISSION_EPOCH_MS },
            tickIntervalMs = 10_000L,
        )

        assertEquals(null, pipeline.lastEmissionEpochMs())
        pipeline.start()
        assertTrue(engine.emitted.await(5, TimeUnit.SECONDS))

        assertEquals(EMISSION_EPOCH_MS, pipeline.lastEmissionEpochMs())
        pipeline.stop()
    }

    @Test
    fun stopJoinsEnginesBeforeFlushAndSealsOnOneThread() {
        val engine = JoiningEngine()
        val writer = ThreadCapturingSpoolWriter(engine.joined)
        val sink = ThreadCapturingSink()
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = writer,
            sealedSink = sink,
            payloadBytes = ByteArrayPayloadProvider(),
            engines = listOf(engine),
            nowProvider = { BASE_EPOCH_MS + 1_000L },
            tickIntervalMs = 10_000L,
        )

        pipeline.start()
        assertTrue(engine.emitted.await(5, TimeUnit.SECONDS))

        pipeline.stop()

        assertTrue(engine.joined.get())
        assertEquals(1, writer.sealedCount)
        assertEquals(1, sink.persistedCount)
        assertTrue(writer.flushSawJoinedEngine.get())
        assertEquals(1, (writer.threadIds + sink.threadIds).distinct().size)
    }

    @Test
    fun releasesPayloadsDroppedByLateEmission() {
        val latePayload = PayloadRef("late-location.jsonl", "application/x-ndjson", 4, null)
        val engine = ScriptedEngine(
            listOf(
                emission("audio", BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L, PayloadRef("audio-0.m4a", "audio/mp4", 4, null)),
                emission("audio", BASE_EPOCH_MS + 305_000L, BASE_EPOCH_MS + 306_000L, PayloadRef("audio-1.m4a", "audio/mp4", 4, null)),
                emission("location", BASE_EPOCH_MS + 10_000L, BASE_EPOCH_MS + 20_000L, latePayload),
            ),
        )
        val provider = RecordingPayloadProvider()
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = ThreadCapturingSpoolWriter(AtomicBoolean(true)),
            sealedSink = ThreadCapturingSink(),
            payloadBytes = provider,
            engines = listOf(engine),
            nowProvider = { BASE_EPOCH_MS + 306_000L },
            tickIntervalMs = 10_000L,
        )

        pipeline.start()
        assertTrue(engine.emitted.await(5, TimeUnit.SECONDS))
        pipeline.stop()

        assertTrue(provider.released.any { it.ref.name == latePayload.name })
        assertFalse(provider.opened.any { it.ref.name == latePayload.name })
    }

    @Test
    fun drainSealFailureReleasesPayloadsAndContinuesBatch() {
        val provider = RecordingPayloadProvider()
        val writer = FailingFirstSpoolWriter()
        val sink = ThreadCapturingSink()
        val diags = mutableListOf<String>()
        val engine = ScriptedEngine(
            listOf(
                emission("audio", BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L, PayloadRef("audio-0.m4a", "audio/mp4", 4, null)),
                emission("audio", BASE_EPOCH_MS + 305_000L, BASE_EPOCH_MS + 306_000L, PayloadRef("audio-1.m4a", "audio/mp4", 4, null)),
            ),
        )
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = writer,
            sealedSink = sink,
            payloadBytes = provider,
            engines = listOf(engine),
            nowProvider = { BASE_EPOCH_MS + 610_000L },
            tickIntervalMs = 10L,
            diag = diags::add,
        )

        pipeline.start()
        assertTrue(engine.emitted.await(5, TimeUnit.SECONDS))
        pipeline.stop()

        assertTrue(diags.any { it.startsWith("capture event=segment-seal-failed day=") })
        assertTrue(provider.released.any { it.ref.name == "audio-0.m4a" })
        assertTrue(diags.any { it.startsWith("capture event=segment-sealed count=") })
    }

    @Test
    fun persistFailureEmitsSpoolRoomReconcilerOrphanDiagAndContinues() {
        val diags = mutableListOf<String>()
        val engine = ScriptedEngine(
            listOf(
                emission("audio", BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L, PayloadRef("audio-0.m4a", "audio/mp4", 4, null)),
                emission("audio", BASE_EPOCH_MS + 305_000L, BASE_EPOCH_MS + 306_000L, PayloadRef("audio-1.m4a", "audio/mp4", 4, null)),
            ),
        )
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = ThreadCapturingSpoolWriter(AtomicBoolean(true)),
            sealedSink = FailingFirstSink(),
            payloadBytes = ByteArrayPayloadProvider(),
            engines = listOf(engine),
            nowProvider = { BASE_EPOCH_MS + 610_000L },
            tickIntervalMs = 10L,
            diag = diags::add,
        )

        pipeline.start()
        assertTrue(engine.emitted.await(5, TimeUnit.SECONDS))
        pipeline.stop()

        assertTrue(
            diags.any {
                it.startsWith("capture event=segment-orphaned recovery=SpoolRoomReconciler") &&
                    "type=IllegalStateException message=room down" in it
            },
        )
    }

    @Test
    fun fastTickSurvivesSealFailureDropsPayloadAndSealsLaterWindow() {
        val now = AtomicLong(BASE_EPOCH_MS)
        val provider = RecordingPayloadProvider()
        val writer = FailingFirstSpoolWriter()
        val sink = ThreadCapturingSink()
        val diags = CopyOnWriteArrayList<String>()
        val engine = ManualEngine()
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = writer,
            sealedSink = sink,
            payloadBytes = provider,
            engines = listOf(engine),
            nowProvider = now::get,
            tickIntervalMs = 10L,
            diag = diags::add,
        )

        pipeline.start()
        engine.emit(emission("audio", BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L, PayloadRef("tick-0.m4a", "audio/mp4", 4, null)))
        now.set(BASE_EPOCH_MS + 305_000L)
        waitUntil("first tick failure") {
            diags.any {
                it.startsWith("capture event=segment-seal-failed day=") &&
                    "type=IllegalStateException message=seal down" in it
            }
        }
        waitUntil("failed tick payload release") { provider.released.any { it.ref.name == "tick-0.m4a" } }

        engine.emit(
            emission(
                "audio",
                BASE_EPOCH_MS + 300_000L,
                BASE_EPOCH_MS + 600_000L,
                PayloadRef("tick-1.m4a", "audio/mp4", 4, null),
            ),
        )
        now.set(BASE_EPOCH_MS + 605_000L)
        waitUntil("later tick sealed") { sink.persistedSegments.any { it.payloads.any { payload -> payload.ref.name == "tick-1.m4a" } } }

        pipeline.stop()

        assertEquals(listOf("tick-1.m4a"), sink.persistedSegments.flatMap { it.payloads }.map { it.ref.name })
        assertFalse(provider.opened.any { it.ref.name == "tick-0.m4a" })
    }

    @Test
    fun fastTickDrainIsolationContinuesAfterMiddleSealFailure() {
        val now = AtomicLong(BASE_EPOCH_MS)
        val provider = RecordingPayloadProvider()
        val writer = FailingCallSpoolWriter(failCall = 2)
        val sink = ThreadCapturingSink()
        val diags = CopyOnWriteArrayList<String>()
        val engine = ManualEngine()
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = writer,
            sealedSink = sink,
            payloadBytes = provider,
            engines = listOf(engine),
            nowProvider = now::get,
            tickIntervalMs = 10L,
            diag = diags::add,
        )

        pipeline.start()
        engine.emit(
            emission(
                "audio",
                BASE_EPOCH_MS + 600_000L,
                BASE_EPOCH_MS + 900_000L,
                PayloadRef("tick-2.m4a", "audio/mp4", 4, null),
            ),
        )
        engine.emit(
            emission(
                "audio",
                BASE_EPOCH_MS + 300_000L,
                BASE_EPOCH_MS + 600_000L,
                PayloadRef("tick-1.m4a", "audio/mp4", 4, null),
            ),
        )
        engine.emit(emission("audio", BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L, PayloadRef("tick-0.m4a", "audio/mp4", 4, null)))
        now.set(BASE_EPOCH_MS + 905_000L)

        waitUntil("tick sealed windows around failed middle") { sink.persistedSegments.size >= 2 }

        pipeline.stop()

        assertEquals(listOf("tick-0.m4a", "tick-2.m4a"), sink.persistedSegments.flatMap { it.payloads }.map { it.ref.name })
        assertTrue(provider.released.any { it.ref.name == "tick-1.m4a" })
        assertTrue(
            diags.any {
                it.startsWith("capture event=segment-seal-failed day=") &&
                    "stream=observer" in it &&
                    "segment=000500_300" in it &&
                    "type=IllegalStateException message=seal down" in it
            },
        )
        assertTrue(diags.any { it == "capture event=segment-sealed count=2" })
    }

    @Test
    fun stopAttemptsEveryEngineAndShutsDownAfterStopFailure() {
        val diags = mutableListOf<String>()
        val first = StopFailingEngine()
        val second = StoppableEngine()
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = ThreadCapturingSpoolWriter(AtomicBoolean(true)),
            sealedSink = ThreadCapturingSink(),
            payloadBytes = ByteArrayPayloadProvider(),
            engines = listOf(first, second),
            nowProvider = { BASE_EPOCH_MS },
            tickIntervalMs = 10L,
            diag = diags::add,
        )

        pipeline.start()
        pipeline.stop()

        assertTrue(first.stopAttempted.get())
        assertTrue(second.stopAttempted.get())
        assertTrue("capture event=engine-stop-failed type=IllegalStateException message=stop failed" in diags)
    }

    @Test
    fun startFailureRollsBackStartedEnginesAndRethrowsOriginal() {
        val diags = mutableListOf<String>()
        val first = StoppableEngine()
        val third = StoppableEngine()
        val failure = IllegalStateException("start failed")
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = ThreadCapturingSpoolWriter(AtomicBoolean(true)),
            sealedSink = ThreadCapturingSink(),
            payloadBytes = ByteArrayPayloadProvider(),
            engines = listOf(first, StartFailingEngine(failure), third),
            nowProvider = { BASE_EPOCH_MS },
            tickIntervalMs = 10L,
            diag = diags::add,
        )

        assertEquals(failure, assertFailsWith<IllegalStateException> { pipeline.start() })
        assertTrue(first.startAttempted.get())
        assertTrue(first.stopAttempted.get())
        assertFalse(third.startAttempted.get())
        assertFalse(third.stopAttempted.get())
        assertTrue("capture event=start-failed source=engine type=IllegalStateException message=start failed" in diags)
    }

    @Test
    fun stopFlushTimeoutStillReturnsAndEmitsDiag() {
        val diags = CopyOnWriteArrayList<String>()
        val writer = BlockingSpoolWriter()
        val engine = ScriptedEngine(
            listOf(
                emission("audio", BASE_EPOCH_MS, BASE_EPOCH_MS + 300_000L, PayloadRef("audio-timeout.m4a", "audio/mp4", 4, null)),
            ),
        )
        val pipeline = CapturePipeline(
            segmenter = Segmenter(java.time.ZoneId.of("UTC")),
            spoolWriter = writer,
            sealedSink = ThreadCapturingSink(),
            payloadBytes = ByteArrayPayloadProvider(),
            engines = listOf(engine),
            nowProvider = { BASE_EPOCH_MS },
            tickIntervalMs = 10_000L,
            diag = diags::add,
        )

        pipeline.start()
        assertTrue(engine.emitted.await(5, TimeUnit.SECONDS))
        Thread {
            Thread.sleep(5_200L)
            writer.release()
        }.start()
        val started = System.nanoTime()
        pipeline.stop()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(elapsedMs < 6_500L, "stop took ${elapsedMs}ms")
        assertTrue(diags.any { it.startsWith("capture event=flush-timeout type=TimeoutException message=") })
    }

    private class JoiningEngine : ContinuousSourceEngine {
        val emitted = CountDownLatch(1)
        val joined = AtomicBoolean(false)
        private val running = AtomicBoolean(false)
        private var worker: Thread? = null

        override fun start(sink: EmissionSink) {
            running.set(true)
            worker = Thread({
                sink.emit(
                    SourceEmission(
                        sourceId = "audio",
                        stream = MAIN_STREAM,
                        sourceKind = SourceKind.OBSERVER,
                        captureStartEpochMs = BASE_EPOCH_MS,
                        captureEndEpochMs = BASE_EPOCH_MS + 300_000L,
                        payloadRefs = listOf(PayloadRef("audio.m4a", "audio/mp4", 4, null)),
                        metadata = emptyMap(),
                        gaps = emptyList(),
                    ),
                )
                emitted.countDown()
                while (running.get()) {
                    Thread.sleep(10)
                }
            }, "joining-engine").also { it.start() }
        }

        override fun stop() {
            running.set(false)
            val localWorker = worker
            localWorker?.interrupt()
            localWorker?.join(5_000L)
            joined.set(true)
        }

        override fun condition(): SourceCondition =
            SourceCondition(
                desiredOn = true,
                running = running.get(),
                available = true,
                needsAttention = false,
                paused = false,
            )
    }

    private class ScriptedEngine(private val emissions: List<SourceEmission>) : ContinuousSourceEngine {
        val emitted = CountDownLatch(1)

        override fun start(sink: EmissionSink) {
            emissions.forEach(sink::emit)
            emitted.countDown()
        }

        override fun stop() = Unit

        override fun condition(): SourceCondition =
            SourceCondition(
                desiredOn = true,
                running = false,
                available = true,
                needsAttention = false,
                paused = false,
            )
    }

    private class ManualEngine : ContinuousSourceEngine {
        private lateinit var sink: EmissionSink

        override fun start(sink: EmissionSink) {
            this.sink = sink
        }

        fun emit(emission: SourceEmission) {
            sink.emit(emission)
        }

        override fun stop() = Unit

        override fun condition(): SourceCondition =
            SourceCondition(true, false, true, false, false)
    }

    private class ByteArrayPayloadProvider : PayloadBytesProvider {
        override fun open(payload: SegmentPayload) =
            ByteArrayInputStream(ByteArray(payload.ref.byteSize.toInt()))
    }

    private class RecordingPayloadProvider : PayloadBytesProvider {
        val opened = mutableListOf<SegmentPayload>()
        val released = mutableListOf<SegmentPayload>()

        override fun open(payload: SegmentPayload): ByteArrayInputStream {
            opened += payload
            return ByteArrayInputStream(ByteArray(payload.ref.byteSize.toInt()))
        }

        override fun release(payload: SegmentPayload) {
            released += payload
        }
    }

    private class ThreadCapturingSpoolWriter(private val engineJoined: AtomicBoolean) : SpoolWriter {
        val threadIds = mutableListOf<Long>()
        val flushSawJoinedEngine = AtomicBoolean(false)
        var sealedCount = 0
            private set

        override fun seal(segment: SealedSegment, payloadBytes: PayloadBytesProvider): SealResult {
            threadIds += Thread.currentThread().id
            flushSawJoinedEngine.set(engineJoined.get())
            sealedCount += 1
            segment.payloads.forEach { payloadBytes.open(it).close() }
            return SealResult(
                manifest = BundleManifest(segment.key, files = emptyList(), gaps = segment.gaps),
                directory = null,
                state = SealState.SEALED,
            )
        }
    }

    private class ThreadCapturingSink : SealedSegmentSink {
        val threadIds = mutableListOf<Long>()
        val persistedSegments = CopyOnWriteArrayList<SealedSegment>()
        var persistedCount = 0
            private set

        override fun persistSealed(segment: SealedSegment, result: SealResult, sealedAtEpochMs: Long) {
            threadIds += Thread.currentThread().id
            persistedSegments += segment
            persistedCount += 1
        }
    }

    private class FailingFirstSpoolWriter : SpoolWriter {
        private var calls = 0

        override fun seal(segment: SealedSegment, payloadBytes: PayloadBytesProvider): SealResult {
            calls += 1
            if (calls == 1) throw IllegalStateException("seal down")
            segment.payloads.forEach { payloadBytes.open(it).close() }
            return SealResult(
                manifest = BundleManifest(segment.key, files = emptyList(), gaps = segment.gaps),
                directory = null,
                state = SealState.SEALED,
            )
        }
    }

    private class FailingCallSpoolWriter(private val failCall: Int) : SpoolWriter {
        private var calls = 0

        override fun seal(segment: SealedSegment, payloadBytes: PayloadBytesProvider): SealResult {
            calls += 1
            if (calls == failCall) throw IllegalStateException("seal down")
            segment.payloads.forEach { payloadBytes.open(it).close() }
            return SealResult(
                manifest = BundleManifest(segment.key, files = emptyList(), gaps = segment.gaps),
                directory = null,
                state = SealState.SEALED,
            )
        }
    }

    private class BlockingSpoolWriter : SpoolWriter {
        private val release = CountDownLatch(1)

        override fun seal(segment: SealedSegment, payloadBytes: PayloadBytesProvider): SealResult {
            release.await()
            return SealResult(
                manifest = BundleManifest(segment.key, files = emptyList(), gaps = segment.gaps),
                directory = null,
                state = SealState.SEALED,
            )
        }

        fun release() {
            release.countDown()
        }
    }

    private class FailingFirstSink : SealedSegmentSink {
        private var calls = 0

        override fun persistSealed(segment: SealedSegment, result: SealResult, sealedAtEpochMs: Long) {
            calls += 1
            if (calls == 1) throw IllegalStateException("room down")
        }
    }

    private class StoppableEngine : ContinuousSourceEngine {
        val startAttempted = AtomicBoolean(false)
        val stopAttempted = AtomicBoolean(false)

        override fun start(sink: EmissionSink) {
            startAttempted.set(true)
        }

        override fun stop() {
            stopAttempted.set(true)
        }

        override fun condition(): SourceCondition =
            SourceCondition(true, false, true, false, false)
    }

    private class StopFailingEngine : ContinuousSourceEngine {
        val stopAttempted = AtomicBoolean(false)

        override fun start(sink: EmissionSink) = Unit

        override fun stop() {
            stopAttempted.set(true)
            throw IllegalStateException("stop failed")
        }

        override fun condition(): SourceCondition =
            SourceCondition(true, false, true, false, false)
    }

    private class StartFailingEngine(private val failure: RuntimeException) : ContinuousSourceEngine {
        override fun start(sink: EmissionSink) {
            throw failure
        }

        override fun stop() = Unit

        override fun condition(): SourceCondition =
            SourceCondition(true, false, true, false, false)
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
        const val EMISSION_EPOCH_MS = BASE_EPOCH_MS + 123L
    }
}

private fun waitUntil(description: String, condition: () -> Boolean) {
    repeat(400) {
        if (condition()) return
        Thread.sleep(10L)
    }
    throw AssertionError("timed out waiting for $description")
}

private fun emission(sourceId: String, startEpochMs: Long, endEpochMs: Long, ref: PayloadRef): SourceEmission =
    SourceEmission(
        sourceId = sourceId,
        stream = MAIN_STREAM,
        sourceKind = SourceKind.OBSERVER,
        captureStartEpochMs = startEpochMs,
        captureEndEpochMs = endEpochMs,
        payloadRefs = listOf(ref),
        metadata = emptyMap(),
        gaps = emptyList(),
    )
