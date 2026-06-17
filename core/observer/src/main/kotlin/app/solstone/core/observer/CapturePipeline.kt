// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.segment.SealedSegment
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
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "solstone-capture-pipeline")
    }
    private val started = AtomicBoolean(false)
    private var tick: ScheduledFuture<*>? = null
    @Volatile private var lastEmissionEpochMs: Long? = null

    init {
        require(tickIntervalMs > 0) { "tickIntervalMs must be positive" }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        tick = executor.scheduleAtFixedRate(
            { drain(segmenter.sealDue(nowProvider())) },
            tickIntervalMs,
            tickIntervalMs,
            TimeUnit.MILLISECONDS,
        )
        engines.forEach { engine ->
            engine.start(
                EmissionSink { emission ->
                    lastEmissionEpochMs = nowProvider()
                    executor.execute {
                        drain(segmenter.feed(emission))
                    }
                },
            )
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        engines.forEach { it.stop() }
        tick?.cancel(false)
        val flush = executor.submit {
            drain(segmenter.flush())
        }
        flush.get(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        executor.shutdown()
        executor.awaitTermination(TERMINATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    fun lastEmissionEpochMs(): Long? = lastEmissionEpochMs

    private fun drain(sealed: List<SealedSegment>) {
        sealed.forEach { segment ->
            val result = spoolWriter.seal(segment, payloadBytes)
            sealedSink.persistSealed(segment, result, nowProvider())
        }
    }

    private companion object {
        const val TERMINATION_TIMEOUT_MS = 5_000L
    }
}
