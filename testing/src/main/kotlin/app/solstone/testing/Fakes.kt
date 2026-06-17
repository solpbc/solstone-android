// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.testing

import app.solstone.core.model.GapEvent
import app.solstone.core.model.SourceKind
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.ImportSourceHandler
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.SourceEmission

data class ScriptedGap(val afterEmissionIndex: Int, val gap: GapEvent)

class FakeContinuousSource(
    private val sourceId: String,
    private val stream: String = MAIN_STREAM,
    private val frameEveryMillis: Long,
    private val frameSizeBytes: Int,
    private val frameCount: Int,
    private val gaps: List<ScriptedGap> = emptyList(),
    private val fixedPayloadName: String? = null,
    private val mediaType: String = "audio/pcm",
) : ContinuousSourceEngine {
    private var running = false

    fun emitAll(sink: EmissionSink) {
        repeat(frameCount) { index ->
            val startEpochMs = BASE_CAPTURE_EPOCH_MS + (index * frameEveryMillis)
            val name = fixedPayloadName ?: payloadName(sourceId, index)
            sink.emit(
                SourceEmission(
                    sourceId = sourceId,
                    stream = stream,
                    sourceKind = SourceKind.OBSERVER,
                    captureStartEpochMs = startEpochMs,
                    captureEndEpochMs = startEpochMs + frameEveryMillis,
                    payloadRefs = listOf(PayloadRef(name, mediaType, frameSizeBytes.toLong(), null)),
                    metadata = mapOf("index" to index.toString()),
                    gaps = gaps.filter { it.afterEmissionIndex == index }.map { it.gap },
                ),
            )
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

fun fakeImportEmission(
    sourceId: String,
    name: String,
    captureStartEpochMs: Long,
    captureEndEpochMs: Long,
    stream: String = sourceId,
): SourceEmission =
    SourceEmission(
        sourceId = sourceId,
        stream = stream,
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
