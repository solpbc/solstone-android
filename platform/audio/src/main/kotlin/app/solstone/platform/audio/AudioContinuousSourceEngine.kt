// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import android.media.MediaRecorder
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

class AudioContinuousSourceEngine(
    private val outputDirectory: File,
    private val storageStatus: StorageStatus,
    private val sourceId: String = SOURCE_ID,
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

    private fun runLoop(sink: EmissionSink) {
        var nextRestartGap: GapEvent? = null
        while (running.get()) {
            val captureStartEpochMs = System.currentTimeMillis()
            val emission = if (!storageStatus.isStorageOk()) {
                gapEmission(captureStartEpochMs, captureStartEpochMs, "storage")
            } else {
                recordWindow(captureStartEpochMs, nextRestartGap)
            }
            sink.emit(emission)
            nextRestartGap = restartGap(emission.captureEndEpochMs)
        }
    }

    private fun recordWindow(captureStartEpochMs: Long, restartGap: GapEvent?): SourceEmission {
        val output = File(outputDirectory, "audio-${captureStartEpochMs}.m4a")
        var recorder: MediaRecorder? = null
        var started = false
        return try {
            recorder = MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(SAMPLE_RATE_HZ)
            recorder.setAudioChannels(CHANNELS)
            recorder.setAudioEncodingBitRate(BIT_RATE)
            recorder.setOutputFile(output.absolutePath)
            recorder.prepare()
            recorder.start()
            started = true
            sleepUntilWindowEnds(captureStartEpochMs)
            val captureEndEpochMs = System.currentTimeMillis()
            stopRecorder(recorder, started)
            recorder.release()
            recorder = null
            val key = PayloadKey(captureStartEpochMs, captureEndEpochMs)
            payloadFiles[key] = output
            SourceEmission(
                sourceId = sourceId,
                stream = MAIN_STREAM,
                sourceKind = SourceKind.OBSERVER,
                captureStartEpochMs = captureStartEpochMs,
                captureEndEpochMs = captureEndEpochMs,
                payloadRefs = listOf(PayloadRef(PAYLOAD_NAME, MEDIA_TYPE, output.length(), null)),
                metadata = metadata(),
                gaps = listOfNotNull(restartGap),
            )
        } catch (error: Exception) {
            stopRecorder(recorder, started)
            recorder?.release()
            output.delete()
            gapEmission(captureStartEpochMs, System.currentTimeMillis(), error.javaClass.simpleName)
        }
    }

    private fun sleepUntilWindowEnds(captureStartEpochMs: Long) {
        while (running.get()) {
            val remaining = WINDOW_MS - (System.currentTimeMillis() - captureStartEpochMs)
            if (remaining <= 0L) return
            Thread.sleep(minOf(remaining, 1_000L))
        }
    }

    private fun stopRecorder(recorder: MediaRecorder?, started: Boolean) {
        if (!started || recorder == null) return
        runCatching { recorder.stop() }
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
        private const val CHANNELS = 1
        private const val BIT_RATE = 64_000
        private const val JOIN_TIMEOUT_MS = 5_000L
    }
}
