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
import java.util.concurrent.atomic.AtomicReference

sealed interface StillCaptureResult {
    data class Image(val bytes: ByteArray) : StillCaptureResult
    data class Failure(val cause: Throwable) : StillCaptureResult
}

interface StillCamera {
    fun takeStill(): StillCaptureResult
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
    private val diag: (String) -> Unit = {},
) : ContinuousSourceEngine, PayloadBytesProvider {
    private val running = AtomicBoolean(false)
    private val currentToken = AtomicReference<Any?>(null)
    private val seq = AtomicLong(0)
    private val cache = ConcurrentHashMap<String, ByteArray>()
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

    override fun release(payload: SegmentPayload) {
        cache.remove(payload.ref.name)
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
            val start = nowProvider()
            if (!cameraLock.tryAcquire()) {
                val end = nowProvider()
                if (!emitSafely(sink, gapEmission(start, end, "camera_busy"), token)) break
                if (!sleepUntilNext(start, token)) break
                continue
            }

            val result: StillCaptureResult
            try {
                result = stillCamera.takeStill()
            } finally {
                cameraLock.release()
            }

            val end = nowProvider()
            when (result) {
                is StillCaptureResult.Failure -> {
                    if (!emitSafely(
                            sink,
                            gapEmission(start, end, "capture_failed type=${result.cause.javaClass.simpleName} message=${result.cause.message ?: ""}"),
                            token,
                        )
                    ) {
                        break
                    }
                }
                is StillCaptureResult.Image -> {
                    if (result.bytes.isEmpty()) {
                        if (!emitSafely(sink, gapEmission(start, end, "empty_image"), token)) break
                    } else {
                        val name = "camera-$start-${seq.getAndIncrement()}.jpg"
                        cache[name] = result.bytes
                        if (!emitSafely(sink, successEmission(start, end, name, result.bytes), token)) break
                    }
                }
            }
            if (!sleepUntilNext(start, token)) break
        }
    }

    private fun sleepUntilNext(startEpochMs: Long, token: Any): Boolean {
        val remaining = stillEveryMs - (nowProvider() - startEpochMs)
        if (remaining > 0L) {
            try {
                sleeper(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return running.get() && currentToken.get() === token
    }

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
