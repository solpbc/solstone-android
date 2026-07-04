// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.location

import app.solstone.core.model.SourceKind
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

interface LocationSource {
    fun lastFix(nowEpochMs: Long): LocationFix?
    fun noFixReason(): NoFixReason
}

class LocationContinuousSourceEngine(
    private val source: LocationSource,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sourceId: String = SOURCE_ID,
    private val sampleEveryMs: Long = SAMPLE_EVERY_MS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : ContinuousSourceEngine, PayloadBytesProvider {
    private val running = AtomicBoolean(false)
    private val payloads = ConcurrentHashMap<PayloadKey, ByteArray>()
    private var worker: Thread? = null

    override fun start(sink: EmissionSink) {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ runLoop(sink) }, "solstone-location-source").also { it.start() }
    }

    override fun stop() {
        val localWorker = worker
        running.set(false)
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

    private fun runLoop(sink: EmissionSink) {
        while (running.get()) {
            val windowStart = windowStart(nowProvider())
            val windowEnd = windowStart + WINDOW_MS
            sink.emit(captureWindow(windowStart, windowEnd))
        }
    }

    private fun captureWindow(windowStart: Long, windowEnd: Long): SourceEmission {
        val records = StringBuilder()
        while (running.get()) {
            val now = nowProvider()
            if (now >= windowEnd) break
            source.lastFix(now)?.let { records.append(buildLocationRecord(it)) }
            if (!sleepUntilNextSampleOrWindowEnd(windowEnd)) break
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

    private fun sleepUntilNextSampleOrWindowEnd(windowEnd: Long): Boolean {
        while (running.get()) {
            val remaining = windowEnd - nowProvider()
            if (remaining <= 0L) return true
            try {
                sleeper(minOf(sampleEveryMs, remaining, SLEEP_SLICE_MS))
                return true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
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
        private const val SLEEP_SLICE_MS = 1_000L
        private const val JOIN_TIMEOUT_MS = 5_000L
    }
}
