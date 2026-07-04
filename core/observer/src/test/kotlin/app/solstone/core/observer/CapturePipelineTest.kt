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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapturePipelineTest {
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
        var persistedCount = 0
            private set

        override fun persistSealed(segment: SealedSegment, result: SealResult, sealedAtEpochMs: Long) {
            threadIds += Thread.currentThread().id
            persistedCount += 1
        }
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
        const val EMISSION_EPOCH_MS = BASE_EPOCH_MS + 123L
    }
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
