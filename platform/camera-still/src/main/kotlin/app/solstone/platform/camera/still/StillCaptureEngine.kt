// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.camera.still

import app.solstone.core.model.GapEvent
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
import java.util.concurrent.atomic.AtomicLong

interface StillCamera {
    fun takeStill(): ByteArray?
}

interface CameraLock {
    fun tryAcquire(): Boolean
    fun release()
}

class SingleHolderCameraLock : CameraLock {
    private val held = AtomicBoolean(false)

    override fun tryAcquire(): Boolean = held.compareAndSet(false, true)

    override fun release() {
        held.set(false)
    }
}

class StillCaptureEngine(
    private val stillCamera: StillCamera,
    private val cameraLock: CameraLock = SingleHolderCameraLock(),
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val stillEveryMs: Long = STILL_EVERY_MS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val sourceId: String = SOURCE_ID,
) : ContinuousSourceEngine, PayloadBytesProvider {
    private val running = AtomicBoolean(false)
    private val seq = AtomicLong(0)
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private var worker: Thread? = null

    override fun start(sink: EmissionSink) {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ runLoop(sink) }, WORKER_THREAD_NAME).also { it.start() }
    }

    override fun stop() {
        val localWorker = worker
        running.set(false)
        localWorker?.interrupt()
        localWorker?.join(JOIN_TIMEOUT_MS)
        worker = null
    }

    override fun condition(): SourceCondition =
        SourceCondition(
            desiredOn = true,
            running = running.get(),
            available = true,
            needsAttention = false,
            paused = false,
        )

    override fun open(payload: SegmentPayload): InputStream {
        val bytes = cache.remove(payload.ref.name)
            ?: throw IllegalArgumentException("payload is no longer available: ${payload.ref.name}")
        return ByteArrayInputStream(bytes)
    }

    private fun runLoop(sink: EmissionSink) {
        while (running.get()) {
            val start = nowProvider()
            if (!cameraLock.tryAcquire()) {
                val end = nowProvider()
                sink.emit(gapEmission(start, end, "camera_busy"))
                if (!sleepUntilNext(start)) break
                continue
            }

            var bytes: ByteArray? = null
            try {
                bytes = stillCamera.takeStill()
            } finally {
                cameraLock.release()
            }

            val end = nowProvider()
            if (bytes == null || bytes.isEmpty()) {
                sink.emit(gapEmission(start, end, "capture_failed"))
            } else {
                val name = "camera-$start-${seq.getAndIncrement()}.jpg"
                cache[name] = bytes
                sink.emit(successEmission(start, end, name, bytes))
            }
            if (!sleepUntilNext(start)) break
        }
    }

    private fun sleepUntilNext(startEpochMs: Long): Boolean {
        val remaining = stillEveryMs - (nowProvider() - startEpochMs)
        if (remaining > 0L) {
            try {
                sleeper(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
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
            gaps = listOf(GapEvent("capture_gap", captureEndEpochMs, reason)),
        )

    private fun successEmission(
        captureStartEpochMs: Long,
        captureEndEpochMs: Long,
        name: String,
        bytes: ByteArray,
    ): SourceEmission =
        SourceEmission(
            sourceId = sourceId,
            stream = MAIN_STREAM,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = captureStartEpochMs,
            captureEndEpochMs = captureEndEpochMs,
            payloadRefs = listOf(PayloadRef(name, MEDIA_TYPE, bytes.size.toLong(), null)),
            metadata = emptyMap(),
            gaps = emptyList(),
        )

    companion object {
        const val SOURCE_ID = "camera"
        const val MEDIA_TYPE = "image/jpeg"
        const val STILL_EVERY_MS = 60_000L
        const val JOIN_TIMEOUT_MS = 5_000L
        const val WORKER_THREAD_NAME = "solstone-camera-source"
    }
}
