// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.GapNoticeStore
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.screenIndexHasRealSample
import app.solstone.platform.power.StorageStatus
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ScreenContinuousSourceEngine(
    private val outputDirectory: File,
    private val storageStatus: StorageStatus,
    private val remuxer: ScreenRemuxer,
    private val gaps: GapNoticeStore,
    private val sessionHolder: ScreenSessionHolder,
    private val encoderFactory: ScreenEncoderFactory = ScreenEncoderFactory { w, h, b, f -> AndroidScreenEncoder(w, h, b, f) },
    private val onFloorTripped: (() -> Unit)? = null,
    private val onFailed: (() -> Unit)? = null,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val diag: (String) -> Unit = {},
    private val stopJoinTimeoutMs: Long = 10_000L,
) : ContinuousSourceEngine, PayloadBytesProvider {
    private val running = AtomicBoolean(false)
    private val lifecycleLock = Any()
    @Volatile private var worker: Thread? = null
    @Volatile private var activeRun: Run? = null
    private var pendingStart: EmissionSink? = null
    @Volatile private var failedReason: ReasonCode? = null
    private data class Key(val start: Long, val end: Long)
    private data class Attempt(val id: String, val dir: File)
    private val attempts = ConcurrentHashMap<String, Attempt>()
    private val registered = ConcurrentHashMap<Key, Attempt>()

    private class Run(val generation: Long, val control: Thread) {
        val ended = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val cutoff = AtomicLong(Long.MAX_VALUE)
        val interruptLock = Any()
        var captureLoop = false
        @Volatile var floor = false
        @Volatile var failed = false
        // One running finalizer and one stopped prefix at most. Attempts retain
        // their slots after enqueue until spool AND persistence release them.
        val finalizer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "ScreenSourceFinalizer") })
    }

    fun inFlightRecordingIds(): Set<String> = attempts.keys.toSet()
    fun reasonWhenOff(): ReasonCode? = when {
        !storageStatus.isStorageOk() -> ReasonCode.STORAGE_FULL
        failedReason != null -> failedReason
        else -> null
    }

    override fun start(sink: EmissionSink) {
        synchronized(lifecycleLock) {
            if (!sessionHolder.isSessionLive()) return
            if (worker?.isAlive == true) {
                if (!running.get() || activeRun?.generation != sessionHolder.generation()) {
                    running.set(false)
                    pendingStart = sink
                    activeRun?.let(::interruptCapture)
                }
                return
            }
            running.set(true)
            failedReason = null
            outputDirectory.mkdirs()
            lateinit var run: Run
            val thread = Thread({ runWorker(sink, run) }, "ScreenSourceEngineWorker")
            run = Run(sessionHolder.generation(), thread)
            activeRun = run
            worker = thread
            thread.start()
        }
    }

    override fun stop() {
        val pair = synchronized(lifecycleLock) {
            running.set(false)
            pendingStart = null
            worker to activeRun
        }
        pair.second?.let(::endCapture)
        val thread = pair.first
        if (thread !== Thread.currentThread()) {
            pair.second?.let(::interruptCapture)
            thread?.join(stopJoinTimeoutMs)
        }
    }

    override fun condition() = SourceCondition(
        desiredOn = true,
        running = running.get() && sessionHolder.isSessionLive() && activeRun?.generation == sessionHolder.generation(),
        available = storageStatus.isStorageOk(),
        needsAttention = reasonWhenOff() != null,
        paused = false,
        silenced = SilencedFact.UNKNOWN,
    )

    override fun open(payload: SegmentPayload): InputStream =
        File(checkNotNull(registered[Key(payload.captureStartEpochMs, payload.captureEndEpochMs)]).dir,
            ScreenConstants.PAYLOAD_NAME).inputStream()

    override fun release(payload: SegmentPayload) {
        registered.remove(Key(payload.captureStartEpochMs, payload.captureEndEpochMs))?.let {
            attempts.remove(it.id)
            it.dir.deleteRecursively()
        }
    }

    override fun discardUncommitted(payload: SegmentPayload): Boolean {
        // Neither a watermark nor a completed finalizer is durable custody.
        // Keep the capacity/protection entry for initialization recovery after restart.
        val attempt = registered.remove(Key(payload.captureStartEpochMs, payload.captureEndEpochMs))
        gaps.recordDiagnostic("screen", "screen restart", attempt?.id ?: "late", payload.captureStartEpochMs)
        return false
    }

    private fun shouldRun(run: Run) = running.get() && sessionHolder.isSessionLive() &&
        sessionHolder.generation() == run.generation && run.failure.get() == null

    private fun interruptCapture(run: Run) = synchronized(run.interruptLock) {
        if (run.captureLoop) run.control.interrupt()
    }

    private fun endCapture(run: Run) {
        run.cutoff.compareAndSet(Long.MAX_VALUE, nowProvider())
        synchronized(lifecycleLock) {
            if (activeRun === run) running.set(false)
        }
        if (run.ended.compareAndSet(false, true) && sessionHolder.stopIfGeneration(run.generation)) {
            // Storage attention follows actual free space after it recovers.
            synchronized(lifecycleLock) {
                if (activeRun === run) {
                    failedReason = if (run.failed || run.failure.get() != null) ReasonCode.SCREEN_CAPTURE_FAILED else null
                }
            }
            if (run.floor) onFloorTripped?.invoke()
            else if (run.failed || run.failure.get() != null) onFailed?.invoke()
        }
    }

    private fun fail(run: Run, error: Throwable) {
        run.failure.compareAndSet(null, error)
        run.failed = true
        val current = attempts.values.toList()
        if (current.isEmpty()) {
            gaps.recordDiagnostic("screen", "no captured frame", "late", null)
        } else {
            for (attempt in current) {
                val reason = if (screenAttemptHasFrame(attempt.dir)) "screen restart" else "no captured frame"
                gaps.recordDiagnostic("screen", reason, attempt.id, null)
            }
        }
        runCatching { diag("screen event=failed type=${error.javaClass.simpleName}") }
        endCapture(run)
        if (run.control !== Thread.currentThread()) interruptCapture(run)
    }

    private fun runWorker(sink: EmissionSink, run: Run) {
        var pending: (() -> Unit)? = null
        try {
            while (shouldRun(run)) {
                if (!storageStatus.isStorageOk()) { run.floor = true; break }
                if (attempts.size >= MAX_ATTEMPTS) { run.failed = true; break }
                val display = sessionHolder.currentVirtualDisplay.get() ?: break
                val geometry = sessionHolder.currentGeometry.get() ?: break
                val end = windowStart(nowProvider()) + ScreenConstants.WINDOW_MS
                val finish = capturePortion(sink, run, display, geometry, end)
                pending = finish
                run.finalizer.execute { try { finish() } catch (t: Throwable) { fail(run, t) } }
                pending = null
            }
        } catch (_: InterruptedException) {
            // A stop/failure has already ended the owning projection.
        } catch (t: Throwable) {
            fail(run, t)
        } finally {
            endCapture(run)
            run.finalizer.shutdown()
            // Public stop has a bounded join. This stopped worker may remain
            // behind a deliberately blocked dependency, without another encoder.
            while (true) {
                try {
                    if (run.finalizer.awaitTermination(1L, TimeUnit.DAYS)) break
                } catch (_: InterruptedException) { /* finish durable cleanup */ }
            }
            pending?.let { try { it() } catch (t: Throwable) { fail(run, t) } }
            synchronized(lifecycleLock) {
                if (worker === Thread.currentThread()) { worker = null; activeRun = null }
                val next = pendingStart
                pendingStart = null
                if (next != null && sessionHolder.isSessionLive()) start(next)
            }
        }
    }

    private fun capturePortion(sink: EmissionSink, run: Run, display: ScreenVirtualDisplay,
        geometry: ScreenGeometry, windowEnd: Long): () -> Unit {
        val start = nowProvider()
        val grid = windowStart(start)
        val zone = zoneProvider()
        val id = "rec-${UUID.randomUUID()}"
        val dir = File(outputDirectory, id)
        check(dir.mkdirs())
        val attempt = Attempt(id, dir)
        attempts[id] = attempt
        val raw = File(dir, "capture.h264")
        val index = File(dir, "samples.idx")
        val record = File(dir, "record.txt")
        val mp4 = File(dir, ScreenConstants.PAYLOAD_NAME)
        var info = ScreenRecordInfo(id, start, zone.id, zone.rules.getOffset(Instant.ofEpochMilli(start)).totalSeconds,
            grid, windowEnd, geometry.width, geometry.height)
        val recordLock = Any()
        try {
            check(writeAndForceRecordTxt(record, info)) { "screen metadata unavailable" }
        } catch (t: Throwable) {
            // No encoder or payload exists yet.
            dir.deleteRecursively()
            attempts.remove(id)
            throw t
        }
        var encoder: ScreenEncoder? = null
        val firstFrame = AtomicReference<Long?>(null)
        var wasInterrupted = false
        var recordingFailure: Throwable? = null
        var cutoffError: Throwable? = null
        var stopError: Throwable? = null
        var drainError: Throwable? = null
        var drainMs = 0L
        try {
            if (!shouldRun(run)) throw InterruptedException()
            encoder = encoderFactory.create(geometry.width, geometry.height, ScreenConstants.BIT_RATE, ScreenConstants.FRAME_RATE)
            encoder.start(raw, index) { first ->
                firstFrame.set(first)
                synchronized(recordLock) {
                    info = info.copy(firstFrameEpochMs = first)
                    check(writeAndForceRecordTxt(record, info))
                }
            }
            display.setSurface(encoder.inputSurface())
            synchronized(run.interruptLock) { run.captureLoop = true }
            while (shouldRun(run) && nowProvider() < windowEnd && sessionHolder.currentGeometry.get() == geometry &&
                !encoder.sizeLimitReached()) {
                if (!storageStatus.isStorageOk()) { run.floor = true; break }
                encoder.failure()?.let { throw it }
                sleeper(minOf(250L, windowEnd - nowProvider()).coerceAtLeast(1L))
            }
        } catch (_: InterruptedException) {
            wasInterrupted = true
        } catch (t: Throwable) {
            recordingFailure = t
            run.failed = true
        } finally {
            val cutoff = minOf(windowEnd, nowProvider(), run.cutoff.get())
            synchronized(run.interruptLock) {
                run.captureLoop = false
                wasInterrupted = Thread.interrupted() || wasInterrupted
            }
            if (shouldRun(run) && !run.floor && attempts.size >= MAX_ATTEMPTS) run.failed = true
            if (wasInterrupted || run.floor || run.failed || !shouldRun(run)) endCapture(run)
            cutoffError = runCatching {
                synchronized(recordLock) {
                    info = info.copy(openEndEpochMs = cutoff)
                    check(writeAndForceRecordTxt(record, info))
                }
            }.exceptionOrNull()
            if (cutoffError != null) fail(run, cutoffError!!)
            runCatching { display.setSurface(null) }
            val drainStarted = System.nanoTime()
            stopError = runCatching { encoder?.stop() }.exceptionOrNull()
            drainError = encoder?.failure()
            runCatching { encoder?.release() }
            drainMs = (System.nanoTime() - drainStarted) / 1_000_000L
            (stopError ?: drainError)?.let { fail(run, it) }
        }
        return {
            // A failed drain may still own file descriptors. Retain the raw
            // attempt for restart recovery instead of publishing/deleting it.
            (stopError ?: drainError)?.let { throw it }
            synchronized(recordLock) {
                info = info.copy(firstFrameEpochMs = firstFrame.get())
                check(writeAndForceRecordTxt(record, info))
            }
            if (firstFrame.get() != null && raw.length() > 0L) {
                val remuxStarted = System.nanoTime()
                val result = remuxer.remux(dir, raw, index, record, mp4)
                runCatching { diag("screen event=portion-timing drainMs=$drainMs remuxMs=${(System.nanoTime() - remuxStarted) / 1_000_000L}") }
                check(result is ScreenRemuxResult.Success && result.sampleDurationMs > 0 && result.outputBytes > 0) {
                    "screen finalization failed"
                }
                synchronized(recordLock) {
                    info = info.copy(sampleDurationMs = result.sampleDurationMs)
                    check(writeAndForceRecordTxt(record, info))
                }
                val bounds = info.captureBounds(result.sampleDurationMs)
                check(bounds.second > bounds.first)
                check(registered.putIfAbsent(Key(bounds.first, bounds.second), attempt) == null) { "screen identity collision" }
                sink.emit(SourceEmission(ScreenConstants.SOURCE_ID, MAIN_STREAM, SourceKind.OBSERVER,
                    bounds.first, bounds.second,
                    listOf(PayloadRef(ScreenConstants.PAYLOAD_NAME, ScreenConstants.MEDIA_TYPE, result.outputBytes, null)),
                    mapOf("zoneId" to info.zoneId, "utcOffsetSeconds" to info.utcOffsetSeconds.toString()),
                    emptyList(), independentTimeline = true))
            } else if (raw.length() == 0L && recordingFailure == null && stopError == null && drainError == null) {
                gaps.recordDiagnostic("screen", "empty recording", id, start)
                dir.deleteRecursively()
                attempts.remove(id)
            } else if (raw.length() > 0L) error("screen has bytes without a captured frame")
            (recordingFailure ?: cutoffError ?: stopError ?: drainError)?.let { throw it }
        }
    }

    private fun screenAttemptHasFrame(dir: File): Boolean {
        val mp4 = File(dir, ScreenConstants.PAYLOAD_NAME)
        if (mp4.isFile && mp4.length() > 0L) return true
        val index = File(dir, "samples.idx")
        if (!index.isFile) return false
        return screenIndexHasRealSample(index.readText())
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        fun windowStart(epochMs: Long): Long = Math.floorDiv(epochMs, ScreenConstants.WINDOW_MS) * ScreenConstants.WINDOW_MS
        fun writeAndForceRecordTxt(file: File, info: ScreenRecordInfo): Boolean {
            val temp = File(file.parentFile, "record.tmp")
            return try {
                FileOutputStream(temp).use { it.write(info.serialize().toByteArray(Charsets.UTF_8)); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                FileChannel.open(file.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
                true
            } catch (_: Exception) { temp.delete(); false }
        }
    }
}
