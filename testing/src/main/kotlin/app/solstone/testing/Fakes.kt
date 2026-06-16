// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.testing

import app.solstone.core.model.GapEvent
import app.solstone.core.model.SourceKind
import app.solstone.core.segment.MonotonicClock
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.ImportSourceHandler
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.SourceEmission

class VirtualMonotonicClock(initialNanos: Long = 0) : MonotonicClock {
    private var currentNanos = initialNanos

    override fun nanos(): Long = currentNanos

    fun advanceByNanos(nanos: Long) {
        currentNanos += nanos
    }

    fun advanceByMillis(ms: Long) {
        advanceByNanos(ms * 1_000_000L)
    }
}

data class ScriptedGap(val afterEmissionIndex: Int, val gap: GapEvent)

class FakeContinuousSource(
    private val sourceId: String,
    private val clock: VirtualMonotonicClock,
    private val frameEveryMillis: Long,
    private val frameSizeBytes: Int,
    private val frameCount: Int,
    private val gaps: List<ScriptedGap> = emptyList(),
) : ContinuousSourceEngine {
    private var running = false

    fun emitAll(sink: EmissionSink) {
        repeat(frameCount) { index ->
            val startEpochMs = BASE_CAPTURE_EPOCH_MS + (index * frameEveryMillis)
            val name = payloadName(sourceId, index)
            sink.emit(
                SourceEmission(
                    sourceId = sourceId,
                    sourceKind = SourceKind.OBSERVER,
                    captureStartEpochMs = startEpochMs,
                    captureEndEpochMs = startEpochMs + frameEveryMillis,
                    payloadRefs = listOf(PayloadRef(name, "audio/pcm", frameSizeBytes.toLong(), null)),
                    metadata = mapOf("index" to index.toString()),
                    gaps = gaps.filter { it.afterEmissionIndex == index }.map { it.gap },
                ),
            )
            clock.advanceByMillis(frameEveryMillis)
        }
    }

    override fun start(sink: EmissionSink) {
        running = true
        emitAll(sink)
    }

    override fun stop() {
        running = false
    }

    override fun condition(): SourceCondition =
        SourceCondition(
            desiredOn = true,
            running = running,
            available = true,
            needsAttention = false,
            paused = false,
        )
}

class FakeImportSource(private val emissions: List<SourceEmission>) : ImportSourceHandler {
    override fun importNow(): List<SourceEmission> = emissions
}

fun fakeImportEmission(sourceId: String, name: String, captureStartEpochMs: Long, captureEndEpochMs: Long): SourceEmission =
    SourceEmission(
        sourceId = sourceId,
        sourceKind = SourceKind.IMPORTER,
        captureStartEpochMs = captureStartEpochMs,
        captureEndEpochMs = captureEndEpochMs,
        payloadRefs = listOf(PayloadRef(name, "application/octet-stream", fakePayloadBytes(sourceId, name, 0, 16).size.toLong(), null)),
        metadata = emptyMap(),
        gaps = emptyList(),
    )

fun payloadName(sourceId: String, index: Int): String = "$sourceId-frame-$index.pcm"

fun fakePayloadBytes(sourceId: String, name: String, index: Int, size: Int): ByteArray {
    val seed = "$sourceId|$name|$index".encodeToByteArray()
    return ByteArray(size) { i -> seed[i % seed.size] }
}

const val BASE_CAPTURE_EPOCH_MS: Long = 1_772_582_400_000L
