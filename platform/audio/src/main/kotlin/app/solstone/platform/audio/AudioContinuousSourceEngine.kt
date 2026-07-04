// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

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
import app.solstone.platform.power.StorageStatus
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface AudioRecorderFactory {
    fun create(output: File): AudioRecording
}

sealed interface RecordingFinishResult {
    data class Success(val byteCount: Long) : RecordingFinishResult
    data class Failure(val cause: Throwable) : RecordingFinishResult
}

interface AudioRecording {
    fun start()
    fun finish(): RecordingFinishResult
    fun discard()
}

class AudioContinuousSourceEngine(
    private val outputDirectory: File,
    private val storageStatus: StorageStatus,
    private val sourceId: String = SOURCE_ID,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val recorderFactory: AudioRecorderFactory = MediaRecorderFactory(),
    private val diag: (String) -> Unit = {},
) : ContinuousSourceEngine, PayloadBytesProvider {
    private val running = AtomicBoolean(false)
    private val currentToken = AtomicReference<Any?>(null)
    private val payloadFiles = ConcurrentHashMap<PayloadKey, File>()
    private var worker: Thread? = null

    override fun start(sink: EmissionSink) {
        if (!running.compareAndSet(false, true)) return
        val token = Any()
        currentToken.set(token)
        outputDirectory.mkdirs()
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
            available = storageStatus.isStorageOk(),
            needsAttention = !storageStatus.isStorageOk(),
            paused = false,
        )

    override fun open(payload: SegmentPayload): InputStream {
        val key = PayloadKey(payload.captureStartEpochMs, payload.captureEndEpochMs)
        val path = payloadFiles[key] ?: throw IllegalArgumentException("payload is no longer available: $key")
        return DeleteOnCloseInputStream(path.inputStream(), path) {
            payloadFiles.remove(key, path)
        }
    }

    override fun release(payload: SegmentPayload) {
        val key = PayloadKey(payload.captureStartEpochMs, payload.captureEndEpochMs)
        payloadFiles.remove(key)?.delete()
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
        var nextRestartGap: GapEvent? = null
        var firstRecordingWindow = true
        while (running.get() && currentToken.get() === token) {
            val windowStart = windowStart(nowProvider())
            val windowEnd = windowStart + WINDOW_MS
            if (!storageStatus.isStorageOk()) {
                val emission = gapEmission(windowStart, nowProvider(), "storage")
                if (!emitSafely(sink, emission, token)) break
                nextRestartGap = restartGap(emission.captureEndEpochMs)
                if (!sleepUntilWindowEnd(windowEnd, token)) break
                continue
            }

            val outcome = recordWindow(windowStart, windowEnd, nextRestartGap, firstRecordingWindow)
            firstRecordingWindow = false
            if (!emitSafely(sink, outcome.emission, token)) break
            nextRestartGap = restartGap(outcome.emission.captureEndEpochMs)
            if (outcome.interrupted) break
        }
    }

    private fun recordWindow(windowStart: Long, windowEnd: Long, restartGap: GapEvent?, firstRecordingWindow: Boolean): RecordingOutcome {
        val output = File(outputDirectory, "audio-$windowStart.m4a")
        var recording: AudioRecording? = null
        var actualRecordingStart = windowStart
        try {
            recording = recorderFactory.create(output)
            recording.start()
            actualRecordingStart = nowProvider()
        } catch (error: Exception) {
            recording?.discard() ?: output.delete()
            val completed = sleepUntilWindowEnd(windowEnd, currentToken.get())
            return RecordingOutcome(
                emission = gapEmission(windowStart, nowProvider(), error.javaClass.simpleName),
                interrupted = !completed,
            )
        }

        val completed = sleepUntilWindowEnd(windowEnd, currentToken.get())
        val captureEndEpochMs = if (completed) windowEnd else nowProvider()
        val captureStartEpochMs = if (firstRecordingWindow) actualRecordingStart else windowStart
        val finishResult = recording.finish()
        val size = when (finishResult) {
            is RecordingFinishResult.Failure -> {
                output.delete()
                return RecordingOutcome(
                    emission = gapEmission(captureStartEpochMs, captureEndEpochMs, "finish_failed type=${finishResult.cause.javaClass.simpleName} message=${finishResult.cause.message ?: ""}"),
                    interrupted = !completed,
                )
            }
            is RecordingFinishResult.Success -> finishResult.byteCount
        }
        if (size <= 0L) {
            output.delete()
            return RecordingOutcome(
                emission = gapEmission(captureStartEpochMs, captureEndEpochMs, "empty_recording"),
                interrupted = !completed,
            )
        }
        val key = PayloadKey(captureStartEpochMs, captureEndEpochMs)
        payloadFiles[key] = output
        return RecordingOutcome(
            emission = SourceEmission(
                sourceId = sourceId,
                stream = MAIN_STREAM,
                sourceKind = SourceKind.OBSERVER,
                captureStartEpochMs = captureStartEpochMs,
                captureEndEpochMs = captureEndEpochMs,
                payloadRefs = listOf(PayloadRef(PAYLOAD_NAME, MEDIA_TYPE, size, null)),
                metadata = metadata(),
                gaps = listOfNotNull(restartGap),
            ),
            interrupted = !completed,
        )
    }

    private fun sleepUntilWindowEnd(windowEnd: Long, token: Any?): Boolean {
        while (running.get() && currentToken.get() === token) {
            val remaining = windowEnd - nowProvider()
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
            metadata = metadata(),
            gaps = listOf(GapEvent("capture_gap", captureEndEpochMs, reason)),
        )

    private fun restartGap(atEpochMs: Long): GapEvent =
        GapEvent("encoder_restart", atEpochMs, "media_recorder_restart")

    private fun metadata(): Map<String, String> =
        mapOf(
            "route" to "mic",
            "sampleRate" to SAMPLE_RATE_HZ.toString(),
            "encoder" to "aac",
            "container" to "mpeg4",
            "channels" to CHANNELS.toString(),
        )

    private fun windowStart(epochMs: Long): Long =
        Math.floorDiv(epochMs, WINDOW_MS) * WINDOW_MS

    private data class RecordingOutcome(val emission: SourceEmission, val interrupted: Boolean)

    private data class PayloadKey(val captureStartEpochMs: Long, val captureEndEpochMs: Long)

    private class DeleteOnCloseInputStream(
        delegate: InputStream,
        private val path: File,
        private val afterDelete: () -> Unit,
    ) : FilterInputStream(delegate) {
        override fun close() {
            super.close()
            path.delete()
            afterDelete()
        }
    }

    companion object {
        const val SOURCE_ID = "audio"
        const val PAYLOAD_NAME = "audio.m4a"
        const val MEDIA_TYPE = "audio/mp4"
        const val WINDOW_MS = 300_000L
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNELS = 1
        const val BIT_RATE = 64_000
        const val WORKER_THREAD_NAME = "solstone-audio-source"
        private const val SLEEP_SLICE_MS = 1_000L
        private const val JOIN_TIMEOUT_MS = 5_000L
    }
}
