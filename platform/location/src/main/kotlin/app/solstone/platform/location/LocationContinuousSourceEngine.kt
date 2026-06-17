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

    private fun runLoop(sink: EmissionSink) {
        while (running.get()) {
            val captureStartEpochMs = nowProvider()
            sink.emit(captureWindow(captureStartEpochMs))
        }
    }

    private fun captureWindow(captureStartEpochMs: Long): SourceEmission {
        val records = StringBuilder()
        while (running.get()) {
            val now = nowProvider()
            source.lastFix(now)?.let { records.append(buildLocationRecord(it)) }
            val remaining = WINDOW_MS - (nowProvider() - captureStartEpochMs)
            if (remaining <= 0L) break
            try {
                Thread.sleep(minOf(sampleEveryMs, remaining))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        val captureEndEpochMs = maxOf(nowProvider(), captureStartEpochMs)
        val bytes = records.toString().encodeToByteArray()
        val refs = if (bytes.isEmpty()) {
            emptyList()
        } else {
            payloads[PayloadKey(captureStartEpochMs, captureEndEpochMs)] = bytes
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
            captureStartEpochMs = captureStartEpochMs,
            captureEndEpochMs = captureEndEpochMs,
            payloadRefs = refs,
            metadata = emptyMap(),
            gaps = gaps,
        )
    }

    private data class PayloadKey(val captureStartEpochMs: Long, val captureEndEpochMs: Long)

    companion object {
        const val SOURCE_ID = "location"
        const val PAYLOAD_NAME = "location.jsonl"
        const val MEDIA_TYPE = "application/x-ndjson"
        const val WINDOW_MS = 300_000L
        const val SAMPLE_EVERY_MS = 60_000L
        private const val JOIN_TIMEOUT_MS = 5_000L
    }
}
