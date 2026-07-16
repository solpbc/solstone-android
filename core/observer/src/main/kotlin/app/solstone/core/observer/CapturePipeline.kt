// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmenterResult
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealedSegmentSink
import app.solstone.core.spool.SpoolWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CapturePipeline(
    private val segmenter: Segmenter,
    private val spoolWriter: SpoolWriter,
    private val sealedSink: SealedSegmentSink,
    private val payloadBytes: PayloadBytesProvider,
    private val engines: List<ContinuousSourceEngine>,
    private val nowProvider: () -> Long,
    private val tickIntervalMs: Long,
    private val diag: (String) -> Unit = {},
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "solstone-capture-pipeline")
    }
    private val started = AtomicBoolean(false)
    private var tick: ScheduledFuture<*>? = null
    @Volatile private var startedEpochMs: Long? = null
    @Volatile private var lastEmissionEpochMs: Long? = null
    private var sealedCount: Long = 0

    init {
        require(tickIntervalMs > 0) { "tickIntervalMs must be positive" }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        startedEpochMs = nowProvider()
        emitDiag("capture event=start")
        tick = executor.scheduleAtFixedRate(
            { runDraining("engine") { segmenter.sealDue(nowProvider()) } },
            tickIntervalMs,
            tickIntervalMs,
            TimeUnit.MILLISECONDS,
        )
        val startedEngines = mutableListOf<ContinuousSourceEngine>()
        try {
            engines.forEach { engine ->
                engine.start(
                    EmissionSink { emission ->
                        lastEmissionEpochMs = nowProvider()
                        executor.execute {
                            runDraining(sourceLabel(emission.sourceId)) { segmenter.feed(emission) }
                        }
                    },
                )
                startedEngines += engine
            }
        } catch (t: Throwable) {
            emitDiag("capture event=start-failed source=engine type=${t.javaClass.simpleName} message=${t.message ?: ""}")
            startedEngines.forEach { stopEngine(it) }
            tick?.cancel(false)
            tick = null
            startedEpochMs = null
            started.set(false)
            throw t
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        emitDiag("capture event=stop")
        try {
            engines.forEach(::stopEngine)
            tick?.cancel(false)
            tick = null
            val flush = executor.submit {
                runDraining("engine") { segmenter.flush() }
            }
            try {
                flush.get(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                emitDiag("capture event=flush-timeout type=${t.javaClass.simpleName} message=${t.message ?: ""}")
            }
        } finally {
            executor.shutdown()
            executor.awaitTermination(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun startedEpochMs(): Long? = startedEpochMs

    fun lastEmissionEpochMs(): Long? = lastEmissionEpochMs

    private fun runDraining(source: String, block: () -> SegmenterResult) {
        try {
            val result = block()
            releaseDropped(result)
            drain(result.sealed)
        } catch (t: Throwable) {
            emitError(source, t)
        }
    }

    private fun releaseDropped(result: SegmenterResult) {
        result.droppedPayloads.forEach { payload ->
            try {
                payloadBytes.release(payload)
            } catch (t: Throwable) {
                emitDiag("capture event=payload-release-failed source=${payload.sourceId} type=${t.javaClass.simpleName}")
            }
        }
    }

    private fun drain(sealed: List<SealedSegment>) {
        sealed.forEach { segment ->
            val result = try {
                spoolWriter.seal(segment, payloadBytes)
            } catch (t: Throwable) {
                emitDiag(
                    "capture event=segment-seal-failed day=${segment.key.day} stream=${segment.stream} segment=${segment.key.segment} type=${t.javaClass.simpleName} message=${t.message ?: ""}",
                )
                releasePayloads(segment.payloads)
                return@forEach
            }
            try {
                sealedSink.persistSealed(segment, result, nowProvider())
            } catch (t: Throwable) {
                emitDiag(
                    "capture event=segment-orphaned recovery=SpoolRoomReconciler day=${segment.key.day} stream=${segment.stream} segment=${segment.key.segment} type=${t.javaClass.simpleName} message=${t.message ?: ""}",
                )
                return@forEach
            }
            sealedCount += 1
            emitDiag("capture event=segment-sealed count=$sealedCount")
        }
    }

    private fun releasePayloads(payloads: List<app.solstone.core.segment.SegmentPayload>) {
        payloads.forEach { payload ->
            try {
                payloadBytes.release(payload)
            } catch (t: Throwable) {
                emitDiag("capture event=payload-release-failed source=${payload.sourceId} type=${t.javaClass.simpleName}")
            }
        }
    }

    private fun sourceLabel(sourceId: String): String =
        when {
            sourceId.contains("camera", ignoreCase = true) -> "camera"
            sourceId.contains("audio", ignoreCase = true) -> "audio"
            else -> "engine"
        }

    private fun emitError(source: String, throwable: Throwable) {
        emitDiag("capture event=error source=$source type=${throwable.javaClass.simpleName} message=${throwable.message ?: ""}")
    }

    private fun stopEngine(engine: ContinuousSourceEngine) {
        try {
            engine.stop()
        } catch (t: Throwable) {
            emitDiag("capture event=engine-stop-failed type=${t.javaClass.simpleName} message=${t.message ?: ""}")
        }
    }

    private fun emitDiag(line: String) {
        runCatching { diag(line) }
    }

    private companion object {
        const val TERMINATION_TIMEOUT_MS = 5_000L
    }
}
