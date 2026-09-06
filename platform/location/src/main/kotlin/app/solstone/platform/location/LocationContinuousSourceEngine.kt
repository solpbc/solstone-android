// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.location

import app.solstone.core.model.SourceKind
import app.solstone.core.model.SilencedFact
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.PayloadBytesProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

fun interface FreshFixCancel {
    fun cancel()
}

interface LocationSource {
    fun lastFix(nowEpochMs: Long): LocationFix?
    fun noFixReason(): NoFixReason
    fun requestFreshFix(onResult: (LocationFix?) -> Unit): FreshFixCancel
}

class LocationContinuousSourceEngine(
    private val source: LocationSource,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sourceId: String = SOURCE_ID,
    private val sampleEveryMs: Long = SAMPLE_EVERY_MS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val diag: (String) -> Unit = {},
) : ContinuousSourceEngine, PayloadBytesProvider {
    private val running = AtomicBoolean(false)
    private val currentToken = AtomicReference<Any?>(null)
    private val inFlightCancel = AtomicReference<FreshFixCancel?>(null)
    private val payloads = ConcurrentHashMap<PayloadKey, ByteArray>()
    private var worker: Thread? = null

    override fun start(sink: EmissionSink) {
        if (!running.compareAndSet(false, true)) return
        val token = Any()
        currentToken.set(token)
        worker = Thread({ runWorker(sink, token) }, WORKER_THREAD_NAME).also { it.start() }
    }

    override fun stop() {
        val localWorker = worker
        running.set(false)
        currentToken.set(null)
        cancelInFlight()
        localWorker?.interrupt()
        localWorker?.join(JOIN_TIMEOUT_MS)
        worker = null
    }

    override fun condition(): SourceCondition {
        val reason = source.noFixReason()
        return SourceCondition(
            desiredOn = true,
            running = running.get(),
            available = reason != NoFixReason.PERMISSION && reason != NoFixReason.PROVIDER_DISABLED,
            needsAttention = reason == NoFixReason.PERMISSION || reason == NoFixReason.PROVIDER_DISABLED,
            paused = false,
            silenced = SilencedFact.UNKNOWN,
        )
    }

    override fun open(payload: SegmentPayload): InputStream {
        val key = PayloadKey(payload.captureStartEpochMs, payload.captureEndEpochMs)
        val bytes = payloads.remove(key) ?: throw IllegalArgumentException("payload is no longer available: $key")
        return ByteArrayInputStream(bytes)
    }

    override fun release(payload: SegmentPayload) {
        payloads.remove(PayloadKey(payload.captureStartEpochMs, payload.captureEndEpochMs))
    }

    private fun runWorker(sink: EmissionSink, token: Any) {
        try {
            runLoop(sink, token)
        } catch (t: Throwable) {
            if (currentToken.get() === token) {
                val now = nowProvider()
                emitSafely(sink, gapEmission(now, now, "engine_failed type=${t.javaClass.simpleName} message=${t.message ?: ""}"), token)
                emitDiag("capture event=engine-failed source=$sourceId type=${t.javaClass.simpleName} message=${t.message ?: ""}")
            }
        } finally {
            if (currentToken.compareAndSet(token, null)) {
                running.set(false)
            }
        }
    }

    private fun runLoop(sink: EmissionSink, token: Any) {
        while (running.get() && currentToken.get() === token) {
            val windowStart = windowStart(nowProvider())
            val windowEnd = windowStart + WINDOW_MS
            if (!emitSafely(sink, captureWindow(windowStart, windowEnd, token), token)) break
        }
    }

    private fun captureWindow(windowStart: Long, windowEnd: Long, token: Any): SourceEmission {
        val records = StringBuilder()
        // The underlying platform fix updates far more slowly than we sample, so without this
        // every window repeats one fix N times, differing only in fixAge.
        var lastRecordedFixMs: Long? = null
        val pendingFreshFix = AtomicReference<LocationFix?>(null)
        val requestDeadline = minOf(windowEnd, nowProvider() + FRESH_FIX_TIMEOUT_MS)
        var nextSampleAt = nowProvider()
        var wakeAlsoAt = requestDeadline
        fun recordIfNew(fix: LocationFix) {
            if (fix.timestampEpochMs != lastRecordedFixMs) {
                records.append(buildLocationRecord(fix))
                lastRecordedFixMs = fix.timestampEpochMs
            }
        }
        fun drainFresh() {
            pendingFreshFix.getAndSet(null)?.let { recordIfNew(it) }
        }
        // One one-shot request per window; cancel before seal so a late callback cannot land here.
        inFlightCancel.set(
            source.requestFreshFix { fix ->
                if (fix != null && currentToken.get() === token) {
                    pendingFreshFix.set(fix)
                }
            },
        )
        if (!running.get() || currentToken.get() !== token) {
            cancelInFlight()
        }
        try {
            while (running.get() && currentToken.get() === token) {
                val now = nowProvider()
                if (now >= windowEnd) break
                if (now >= nextSampleAt) {
                    source.lastFix(now)?.let { recordIfNew(it) }
                    nextSampleAt = now + sampleEveryMs
                }
                drainFresh()
                if (nowProvider() >= requestDeadline) {
                    cancelInFlight()
                    wakeAlsoAt = Long.MAX_VALUE
                }
                val wakeAt = minOf(windowEnd, nextSampleAt, wakeAlsoAt)
                if (!sleepUntil(wakeAt, token)) break
            }
            if (running.get() && currentToken.get() === token) {
                drainFresh()
            }
        } finally {
            cancelInFlight()
            pendingFreshFix.set(null)
        }

        val completed = nowProvider() >= windowEnd
        val captureEndEpochMs = if (completed) windowEnd else maxOf(nowProvider(), windowStart)
        val bytes = records.toString().encodeToByteArray()
        val refs = if (bytes.isEmpty()) {
            emptyList()
        } else {
            payloads[PayloadKey(windowStart, captureEndEpochMs)] = bytes
            trimPayloadCache()
            listOf(PayloadRef(PAYLOAD_NAME, MEDIA_TYPE, bytes.size.toLong(), null))
        }
        val gaps = if (bytes.isEmpty()) {
            listOf(decideGap(source.noFixReason(), captureEndEpochMs))
        } else {
            emptyList()
        }
        return SourceEmission(
            sourceId = sourceId,
            stream = MAIN_STREAM,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = windowStart,
            captureEndEpochMs = captureEndEpochMs,
            payloadRefs = refs,
            metadata = emptyMap(),
            gaps = gaps,
        )
    }

    private fun cancelInFlight() {
        inFlightCancel.getAndSet(null)?.cancel()
    }

    private fun sleepUntil(wakeAt: Long, token: Any): Boolean {
        // Sleep in short slices so stop() stays responsive. Keep slicing until wakeAt; returning
        // after the first slice would make SLEEP_SLICE_MS the sample interval.
        while (running.get() && currentToken.get() === token) {
            val remaining = wakeAt - nowProvider()
            if (remaining <= 0L) return true
            try {
                sleeper(minOf(remaining, SLEEP_SLICE_MS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun gapEmission(captureStartEpochMs: Long, captureEndEpochMs: Long, reason: String): SourceEmission =
        SourceEmission(
            sourceId = sourceId,
            stream = MAIN_STREAM,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = captureStartEpochMs,
            captureEndEpochMs = captureEndEpochMs,
            payloadRefs = emptyList(),
            metadata = emptyMap(),
            gaps = listOf(app.solstone.core.model.GapEvent("capture_gap", captureEndEpochMs, reason)),
        )

    private fun emitSafely(sink: EmissionSink, emission: SourceEmission, token: Any): Boolean {
        if (currentToken.get() !== token) return false
        return try {
            sink.emit(emission)
            true
        } catch (t: Throwable) {
            emitDiag("capture event=emit-failed source=$sourceId type=${t.javaClass.simpleName} message=${t.message ?: ""}")
            if (currentToken.compareAndSet(token, null)) {
                running.set(false)
            }
            false
        }
    }

    private fun emitDiag(line: String) {
        runCatching { diag(line) }
    }

    private fun trimPayloadCache() {
        while (payloads.size > MAX_CACHED_WINDOWS) {
            val oldest = payloads.keys.minByOrNull { it.captureStartEpochMs } ?: return
            payloads.remove(oldest)
        }
    }

    private fun windowStart(epochMs: Long): Long =
        Math.floorDiv(epochMs, WINDOW_MS) * WINDOW_MS

    private data class PayloadKey(val captureStartEpochMs: Long, val captureEndEpochMs: Long)

    companion object {
        const val SOURCE_ID = "location"
        const val PAYLOAD_NAME = "location.jsonl"
        const val MEDIA_TYPE = "application/x-ndjson"
        const val WINDOW_MS = 300_000L
        const val SAMPLE_EVERY_MS = 60_000L
        const val MAX_CACHED_WINDOWS = 3
        const val WORKER_THREAD_NAME = "solstone-location-source"
        const val FRESH_FIX_TIMEOUT_MS = 30_000L
        private const val SLEEP_SLICE_MS = 1_000L
        private const val JOIN_TIMEOUT_MS = 5_000L
    }
}
