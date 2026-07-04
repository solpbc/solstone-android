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
    @Volatile private var lastEmissionEpochMs: Long? = null
    private var sealedCount: Long = 0

    init {
        require(tickIntervalMs > 0) { "tickIntervalMs must be positive" }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        emitDiag("capture event=start")
        tick = executor.scheduleAtFixedRate(
            { runDraining("engine") { segmenter.sealDue(nowProvider()) } },
            tickIntervalMs,
            tickIntervalMs,
            TimeUnit.MILLISECONDS,
        )
        engines.forEach { engine ->
            try {
                engine.start(
                    EmissionSink { emission ->
                        lastEmissionEpochMs = nowProvider()
                        executor.execute {
                            runDraining(sourceLabel(emission.sourceId)) { segmenter.feed(emission) }
                        }
                    },
                )
            } catch (t: Throwable) {
                emitError("engine", t)
                throw t
            }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        emitDiag("capture event=stop")
        engines.forEach {
            try {
                it.stop()
            } catch (t: Throwable) {
                emitError("engine", t)
                throw t
            }
        }
        tick?.cancel(false)
        val flush = executor.submit {
            runDraining("engine") { segmenter.flush() }
        }
        flush.get(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        executor.shutdown()
        executor.awaitTermination(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun lastEmissionEpochMs(): Long? = lastEmissionEpochMs

    private fun runDraining(source: String, block: () -> SegmenterResult) {
        try {
            val result = block()
            releaseDropped(result)
            drain(result.sealed)
        } catch (t: Throwable) {
            emitError(source, t)
            throw t
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
            val result = spoolWriter.seal(segment, payloadBytes)
            sealedSink.persistSealed(segment, result, nowProvider())
            sealedCount += 1
            emitDiag("capture event=segment-sealed count=$sealedCount")
        }
    }

    private fun sourceLabel(sourceId: String): String =
        when {
            sourceId.contains("camera", ignoreCase = true) -> "camera"
            sourceId.contains("audio", ignoreCase = true) -> "audio"
            else -> "engine"
        }

    private fun emitError(source: String, throwable: Throwable) {
        emitDiag("capture event=error source=$source type=${throwable.javaClass.simpleName}")
    }

    private fun emitDiag(line: String) {
        runCatching { diag(line) }
    }

    private companion object {
        const val TERMINATION_TIMEOUT_MS = 5_000L
    }
}
