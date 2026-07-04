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

interface AudioRecorderFactory {
    fun create(output: File): AudioRecording
}

interface AudioRecording {
    fun start()
    fun finish(): Long
    fun discard()
}

class AudioContinuousSourceEngine(
    private val outputDirectory: File,
    private val storageStatus: StorageStatus,
    private val sourceId: String = SOURCE_ID,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val recorderFactory: AudioRecorderFactory = MediaRecorderFactory(),
) : ContinuousSourceEngine, PayloadBytesProvider {
    private val running = AtomicBoolean(false)
    private val payloadFiles = ConcurrentHashMap<PayloadKey, File>()
    private var worker: Thread? = null

    override fun start(sink: EmissionSink) {
        if (!running.compareAndSet(false, true)) return
        outputDirectory.mkdirs()
        worker = Thread({ runLoop(sink) }, "solstone-audio-source").also { it.start() }
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

    private fun runLoop(sink: EmissionSink) {
        var nextRestartGap: GapEvent? = null
        while (running.get()) {
            val windowStart = windowStart(nowProvider())
            val windowEnd = windowStart + WINDOW_MS
            if (!storageStatus.isStorageOk()) {
                val emission = gapEmission(windowStart, nowProvider(), "storage")
                sink.emit(emission)
                nextRestartGap = restartGap(emission.captureEndEpochMs)
                if (!sleepUntilWindowEnd(windowEnd)) break
                continue
            }

            val outcome = recordWindow(windowStart, windowEnd, nextRestartGap)
            sink.emit(outcome.emission)
            nextRestartGap = restartGap(outcome.emission.captureEndEpochMs)
            if (outcome.interrupted) break
        }
    }

    private fun recordWindow(windowStart: Long, windowEnd: Long, restartGap: GapEvent?): RecordingOutcome {
        val output = File(outputDirectory, "audio-$windowStart.m4a")
        var recording: AudioRecording? = null
        try {
            recording = recorderFactory.create(output)
            recording.start()
        } catch (error: Exception) {
            recording?.discard() ?: output.delete()
            val completed = sleepUntilWindowEnd(windowEnd)
            return RecordingOutcome(
                emission = gapEmission(windowStart, nowProvider(), error.javaClass.simpleName),
                interrupted = !completed,
            )
        }

        val completed = sleepUntilWindowEnd(windowEnd)
        val size = recording.finish()
        val captureEndEpochMs = if (completed) windowEnd else nowProvider()
        if (size <= 0L) {
            output.delete()
            return RecordingOutcome(
                emission = gapEmission(windowStart, captureEndEpochMs, "empty_recording"),
                interrupted = !completed,
            )
        }
        val key = PayloadKey(windowStart, captureEndEpochMs)
        payloadFiles[key] = output
        return RecordingOutcome(
            emission = SourceEmission(
                sourceId = sourceId,
                stream = MAIN_STREAM,
                sourceKind = SourceKind.OBSERVER,
                captureStartEpochMs = windowStart,
                captureEndEpochMs = captureEndEpochMs,
                payloadRefs = listOf(PayloadRef(PAYLOAD_NAME, MEDIA_TYPE, size, null)),
                metadata = metadata(),
                gaps = listOfNotNull(restartGap),
            ),
            interrupted = !completed,
        )
    }

    private fun sleepUntilWindowEnd(windowEnd: Long): Boolean {
        while (running.get()) {
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
        private const val SLEEP_SLICE_MS = 1_000L
        private const val JOIN_TIMEOUT_MS = 5_000L
    }
}
