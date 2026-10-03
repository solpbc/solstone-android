// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import app.solstone.core.model.GapEvent
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.UnresolvedInterruption
import app.solstone.platform.power.StorageStatus
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
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
    fun silenced(): SilencedFact
}

data class AudioRecordInfo(
    val id: String,
    val captureStartEpochMs: Long,
    val zoneId: String,
    val utcOffsetSeconds: Int,
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val route: String = "mic",
    val sampleRate: Int = AudioContinuousSourceEngine.SAMPLE_RATE_HZ,
    val encoder: String = "aac",
    val container: String = "mpeg4",
    val channels: Int = AudioContinuousSourceEngine.CHANNELS,
    val openEndEpochMs: Long? = null,
    val sampleDurationMs: Long? = null,
) {
    fun serialize(): String = buildString {
        appendLine("id=$id")
        appendLine("captureStartEpochMs=$captureStartEpochMs")
        appendLine("zoneId=$zoneId")
        appendLine("utcOffsetSeconds=$utcOffsetSeconds")
        appendLine("windowStartEpochMs=$windowStartEpochMs")
        appendLine("windowEndEpochMs=$windowEndEpochMs")
        appendLine("route=$route")
        appendLine("sampleRate=$sampleRate")
        appendLine("encoder=$encoder")
        appendLine("container=$container")
        appendLine("channels=$channels")
        if (openEndEpochMs != null) {
            appendLine("openEndEpochMs=$openEndEpochMs")
        }
        if (sampleDurationMs != null && sampleDurationMs > 0L) {
            appendLine("sampleDurationMs=$sampleDurationMs")
        }
    }

    companion object {
        fun parse(text: String): AudioRecordInfo? {
            val map = text.lines()
                .filter { it.contains("=") }
                .associate { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
            val id = map["id"] ?: return null
            val captureStart = map["captureStartEpochMs"]?.toLongOrNull() ?: return null
            val zoneId = map["zoneId"] ?: return null
            val utcOffsetSeconds = map["utcOffsetSeconds"]?.toIntOrNull() ?: return null
            val windowStart = map["windowStartEpochMs"]?.toLongOrNull() ?: return null
            val windowEnd = map["windowEndEpochMs"]?.toLongOrNull() ?: return null
            val route = map["route"] ?: "mic"
            val sampleRate = map["sampleRate"]?.toIntOrNull() ?: AudioContinuousSourceEngine.SAMPLE_RATE_HZ
            val encoder = map["encoder"] ?: "aac"
            val container = map["container"] ?: "mpeg4"
            val channels = map["channels"]?.toIntOrNull() ?: AudioContinuousSourceEngine.CHANNELS
            val openEndEpochMs = map["openEndEpochMs"]?.toLongOrNull()
            val sampleDurationMs = map["sampleDurationMs"]?.toLongOrNull()
            return AudioRecordInfo(
                id = id,
                captureStartEpochMs = captureStart,
                zoneId = zoneId,
                utcOffsetSeconds = utcOffsetSeconds,
                windowStartEpochMs = windowStart,
                windowEndEpochMs = windowEnd,
                route = route,
                sampleRate = sampleRate,
                encoder = encoder,
                container = container,
                channels = channels,
                openEndEpochMs = openEndEpochMs,
                sampleDurationMs = sampleDurationMs,
            )
        }
    }
}

class AudioContinuousSourceEngine(
    private val outputDirectory: File,
    private val storageStatus: StorageStatus,
    private val remuxer: AacAdtsRemuxer,
    private val interruption: UnresolvedInterruption,
    private val sourceId: String = SOURCE_ID,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val recorderFactory: AudioRecorderFactory = MediaRecorderFactory(),
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val diag: (String) -> Unit = {},
) : ContinuousSourceEngine, PayloadBytesProvider {
    private val running = AtomicBoolean(false)
    private val currentToken = AtomicReference<Any?>(null)
    private val activeRecording = AtomicReference<ActiveRecording?>(null)
    private val recordingStateLock = Any()
    private val registeredAttempts = ConcurrentHashMap<PayloadKey, AttemptEntry>()
    private var worker: Thread? = null

    fun inFlightRecordingIds(): Set<String> {
        val activeId = activeRecording.get()?.attemptId
        val registeredIds = registeredAttempts.values.map { it.attemptId }
        return if (activeId != null) (registeredIds + activeId).toSet() else registeredIds.toSet()
    }

    override fun start(sink: EmissionSink) {
        if (!running.compareAndSet(false, true)) return
        val token = Any()
        synchronized(recordingStateLock) {
            currentToken.set(token)
        }
        outputDirectory.mkdirs()
        worker = Thread({ runWorker(sink, token) }, WORKER_THREAD_NAME).also { it.start() }
    }

    override fun stop() {
        val (localWorker, stoppedToken) = synchronized(recordingStateLock) {
            running.set(false)
            val token = currentToken.get()
            token?.let(::clearActiveRecordingForToken)
            worker to token
        }
        localWorker?.interrupt()
        localWorker?.join(JOIN_TIMEOUT_MS)
        synchronized(recordingStateLock) {
            if (localWorker?.isAlive == true && stoppedToken != null) {
                currentToken.compareAndSet(stoppedToken, null)
            }
            if (worker === localWorker) worker = null
        }
    }

    override fun condition(): SourceCondition {
        val isRunning = running.get()
        val recording = if (isRunning) {
            synchronized(recordingStateLock) {
                activeRecording.get()
                    ?.takeIf { it.token === currentToken.get() }
                    ?.recording
            }
        } else {
            null
        }
        return SourceCondition(
            desiredOn = true,
            running = isRunning,
            available = storageStatus.isStorageOk(),
            needsAttention = !storageStatus.isStorageOk(),
            paused = false,
            silenced = recording?.let { runCatching { it.silenced() }.getOrDefault(SilencedFact.UNKNOWN) }
                ?: SilencedFact.UNKNOWN,
        )
    }

    override fun open(payload: SegmentPayload): InputStream {
        val key = PayloadKey(payload.captureStartEpochMs, payload.captureEndEpochMs)
        val entry = registeredAttempts[key] ?: throw IllegalArgumentException("payload is no longer available: $key")
        return entry.m4aFile.inputStream()
    }

    override fun release(payload: SegmentPayload) {
        val key = PayloadKey(payload.captureStartEpochMs, payload.captureEndEpochMs)
        val entry = registeredAttempts.remove(key)
        entry?.dir?.deleteRecursively()
    }

    override fun discardUncommitted(payload: SegmentPayload): Boolean {
        val key = PayloadKey(payload.captureStartEpochMs, payload.captureEndEpochMs)
        val entry = registeredAttempts.remove(key) ?: return true
        return if (interruption.markUnresolved()) {
            entry.dir.deleteRecursively()
            true
        } else {
            registeredAttempts[key] = entry
            false
        }
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
            synchronized(recordingStateLock) {
                if (currentToken.compareAndSet(token, null)) {
                    clearActiveRecordingForToken(token)
                    running.set(false)
                }
            }
        }
    }

    private fun runLoop(sink: EmissionSink, token: Any) {
        var nextRestartGap: GapEvent? = null
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

            val outcome = recordWindow(windowStart, windowEnd, nextRestartGap, token)
            if (outcome.emission.payloadRefs.isNotEmpty() || outcome.emission.gaps.isNotEmpty()) {
                if (!emitSafely(sink, outcome.emission, token)) break
            }
            nextRestartGap = restartGap(outcome.emission.captureEndEpochMs)
            if (outcome.interrupted) break
        }
    }

    private fun recordWindow(
        windowStart: Long,
        windowEnd: Long,
        restartGap: GapEvent?,
        token: Any,
    ): RecordingOutcome {
        val attemptId = "rec-${UUID.randomUUID()}"
        val attemptDir = File(outputDirectory, attemptId)
        val adtsFile = File(attemptDir, "capture.adts")
        val m4aFile = File(attemptDir, PAYLOAD_NAME)
        val recordFile = File(attemptDir, "record.txt")

        val actualRecordingStart = nowProvider()
        val zone = zoneProvider()
        val utcOffsetSeconds = zone.rules.getOffset(Instant.ofEpochMilli(actualRecordingStart)).totalSeconds
        var info = AudioRecordInfo(
            id = attemptId,
            captureStartEpochMs = actualRecordingStart,
            zoneId = zone.id,
            utcOffsetSeconds = utcOffsetSeconds,
            windowStartEpochMs = windowStart,
            windowEndEpochMs = windowEnd,
        )

        if (!writeAndForceRecordTxt(recordFile, info)) {
            attemptDir.deleteRecursively()
            return RecordingOutcome(
                emission = gapEmission(actualRecordingStart, nowProvider(), "record_sync_failed"),
                interrupted = false,
            )
        }

        var recording: AudioRecording? = null
        var active: ActiveRecording? = null
        try {
            recording = recorderFactory.create(adtsFile)
            recording.start()
            active = ActiveRecording(token, recording, attemptId)
            publishActiveRecording(active)
        } catch (error: Exception) {
            clearActiveRecording(active)
            recording?.discard()
            if (interruption.markUnresolved()) {
                attemptDir.deleteRecursively()
            }
            val completed = sleepUntilWindowEnd(windowEnd, currentToken.get())
            return RecordingOutcome(
                emission = gapEmission(actualRecordingStart, nowProvider(), error.javaClass.simpleName),
                interrupted = !completed,
            )
        }

        val completed = sleepUntilWindowEnd(windowEnd, currentToken.get())
        val openEndEpochMs = if (completed) windowEnd else nowProvider()
        val startedRecording = checkNotNull(recording)
        clearActiveRecording(active)

        info = info.copy(openEndEpochMs = openEndEpochMs)
        writeAndForceRecordTxt(recordFile, info)

        val finishResult = runCatching { startedRecording.finish() }
        if (finishResult.isFailure || finishResult.getOrNull() is RecordingFinishResult.Failure) {
            val remuxResult = runCatching { remuxer.remux(adtsFile, m4aFile) }.getOrNull()
            if (remuxResult != null && remuxResult.sampleDurationMs > 0L && remuxResult.outputBytes > 0L) {
                val updatedInfo = info.copy(openEndEpochMs = openEndEpochMs, sampleDurationMs = remuxResult.sampleDurationMs)
                if (!writeAndForceRecordTxt(recordFile, updatedInfo)) {
                    return RecordingOutcome(
                        emission = emptyEmission(actualRecordingStart),
                        interrupted = !completed,
                    )
                }
                val captureEndEpochMs = minOf(actualRecordingStart + remuxResult.sampleDurationMs, openEndEpochMs)
                val key = PayloadKey(actualRecordingStart, captureEndEpochMs)
                registeredAttempts[key] = AttemptEntry(attemptId, attemptDir, m4aFile)
                return RecordingOutcome(
                    emission = SourceEmission(
                        sourceId = sourceId,
                        stream = MAIN_STREAM,
                        sourceKind = SourceKind.OBSERVER,
                        captureStartEpochMs = actualRecordingStart,
                        captureEndEpochMs = captureEndEpochMs,
                        payloadRefs = listOf(PayloadRef(PAYLOAD_NAME, MEDIA_TYPE, remuxResult.outputBytes, null)),
                        metadata = metadata(),
                        gaps = listOfNotNull(restartGap),
                    ),
                    interrupted = !completed,
                )
            } else {
                return RecordingOutcome(
                    emission = emptyEmission(actualRecordingStart),
                    interrupted = !completed,
                )
            }
        }

        val size = (finishResult.getOrNull() as RecordingFinishResult.Success).byteCount
        if (size <= 0L) {
            if (interruption.markUnresolved()) {
                attemptDir.deleteRecursively()
            }
            return RecordingOutcome(
                emission = gapEmission(actualRecordingStart, openEndEpochMs, "empty_recording"),
                interrupted = !completed,
            )
        }

        val remux = try {
            remuxer.remux(adtsFile, m4aFile)
        } catch (_: Throwable) {
            return RecordingOutcome(
                emission = emptyEmission(actualRecordingStart),
                interrupted = !completed,
            )
        }

        if (remux.sampleDurationMs <= 0L || remux.outputBytes <= 0L) {
            if (interruption.markUnresolved()) {
                attemptDir.deleteRecursively()
            }
            return RecordingOutcome(
                emission = gapEmission(actualRecordingStart, openEndEpochMs, "no_complete_frame"),
                interrupted = !completed,
            )
        }

        val updatedInfo = info.copy(openEndEpochMs = openEndEpochMs, sampleDurationMs = remux.sampleDurationMs)
        if (!writeAndForceRecordTxt(recordFile, updatedInfo)) {
            return RecordingOutcome(
                emission = emptyEmission(actualRecordingStart),
                interrupted = !completed,
            )
        }

        val captureEndEpochMs = minOf(actualRecordingStart + remux.sampleDurationMs, openEndEpochMs)
        val key = PayloadKey(actualRecordingStart, captureEndEpochMs)
        registeredAttempts[key] = AttemptEntry(attemptId, attemptDir, m4aFile)
        return RecordingOutcome(
            emission = SourceEmission(
                sourceId = sourceId,
                stream = MAIN_STREAM,
                sourceKind = SourceKind.OBSERVER,
                captureStartEpochMs = actualRecordingStart,
                captureEndEpochMs = captureEndEpochMs,
                payloadRefs = listOf(PayloadRef(PAYLOAD_NAME, MEDIA_TYPE, remux.outputBytes, null)),
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

    private fun clearActiveRecording(active: ActiveRecording?) {
        if (active != null) activeRecording.compareAndSet(active, null)
    }

    private fun publishActiveRecording(active: ActiveRecording) {
        synchronized(recordingStateLock) {
            if (!running.get() || currentToken.get() !== active.token) return
            val current = activeRecording.get()
            if (current == null || current.token === active.token) {
                activeRecording.set(active)
            }
        }
    }

    private fun clearActiveRecordingForToken(token: Any) {
        while (true) {
            val active = activeRecording.get() ?: return
            if (active.token !== token) return
            if (activeRecording.compareAndSet(active, null)) return
        }
    }

    private fun emptyEmission(epochMs: Long): SourceEmission =
        SourceEmission(
            sourceId = sourceId,
            stream = MAIN_STREAM,
            sourceKind = SourceKind.OBSERVER,
            captureStartEpochMs = epochMs,
            captureEndEpochMs = epochMs,
            payloadRefs = emptyList(),
            metadata = metadata(),
            gaps = emptyList(),
        )

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
    private data class AttemptEntry(val attemptId: String, val dir: File, val m4aFile: File)
    private data class ActiveRecording(val token: Any, val recording: AudioRecording, val attemptId: String)

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

        fun writeAndForceRecordTxt(recordFile: File, info: AudioRecordInfo): Boolean {
            return try {
                val dir = recordFile.parentFile ?: return false
                dir.mkdirs()
                FileOutputStream(recordFile).use { fos ->
                    fos.write(info.serialize().toByteArray(StandardCharsets.UTF_8))
                    fos.fd.sync()
                }
                forceDirectory(dir.toPath())
            } catch (_: Exception) {
                false
            }
        }

        private fun forceDirectory(dir: Path): Boolean {
            val wasInterrupted = Thread.interrupted()
            return try {
                FileChannel.open(dir, StandardOpenOption.READ).use { channel ->
                    channel.force(true)
                }
                true
            } catch (_: Exception) {
                false
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}
