// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.metadata

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
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PhotoMetadataEngine(
    private val scheduler: MetadataScheduler,
    private val battery: BatterySource,
    private val imu: ImuSensorPort,
    private val diag: (String) -> Unit = {},
) : ContinuousSourceEngine, PayloadBytesProvider {
    private val running = AtomicBoolean(false)
    private val accepting = AtomicBoolean(true)
    private val windows = TreeMap<Long, WindowBuffer>()
    private val payloads = ConcurrentHashMap<PayloadKey, ByteArray>()
    private var sink: EmissionSink? = null
    private var sequence = 0L

    override fun start(sink: EmissionSink) {
        if (!running.compareAndSet(false, true)) return
        accepting.set(true)
        execute("start") {
            this.sink = sink
            flushDueWindows()
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        val stopped = CountDownLatch(1)
        execute("stop") {
            try {
                windows.values.forEach { window ->
                    window.flushTask?.cancel()
                    window.records.values.forEach { record ->
                        record.snapshotTask?.cancel()
                        record.stopHandle()
                        record.finalizeOmitted()
                    }
                }
                windows.keys.toList().forEach(::flushWindow)
                sink = null
                accepting.set(false)
            } finally {
                stopped.countDown()
            }
        }
        if (!stopped.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            emitDiag("capture event=metadata-stop-timeout")
        }
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
        val key = PayloadKey(payload.captureStartEpochMs, payload.captureEndEpochMs)
        val bytes = payloads.remove(key) ?: throw IllegalArgumentException("payload is no longer available: $key")
        return ByteArrayInputStream(bytes)
    }

    override fun release(payload: SegmentPayload) {
        payloads.remove(PayloadKey(payload.captureStartEpochMs, payload.captureEndEpochMs))
    }

    fun onCameraEmission(emission: SourceEmission) {
        execute("emission") {
            if (!accepting.get() || emission.payloadRefs.isEmpty()) return@execute
            val ts = emission.captureStartEpochMs
            val windowStart = windowStart(ts)
            val window = windows.getOrPut(windowStart) {
                WindowBuffer(windowStart).also { scheduleFlush(it) }
            }
            val key = RecordKey(ts, sequence++)
            val record = PendingRecord(key = key, ts = ts, battery = battery.snapshot())
            window.records[key] = record
            startSnapshot(record)
        }
    }

    private fun startSnapshot(record: PendingRecord) {
        val listener = object : ImuListener {
            override fun onRotationVector(sample: RotationVectorSample) {
                execute("snapshot") {
                    if (!record.finalized) record.rotationSamples += sample
                }
            }

            override fun onLinearAcceleration(sample: LinearAccelerationSample) {
                execute("snapshot") {
                    if (!record.finalized) record.linearSamples += sample
                }
            }
        }
        record.handle = runCatching { imu.start(listener) }.getOrNull()
        record.snapshotTask = schedule(PhotoMetadataContract.SNAPSHOT_MS, "snapshot") {
            execute("snapshot") {
                record.stopHandle()
                record.finalizeFromSamples()
            }
        }
    }

    private fun scheduleFlush(window: WindowBuffer) {
        val dueAt = window.windowStart + PhotoMetadataContract.WINDOW_MS
        val delay = (dueAt - scheduler.nowEpochMs()).coerceAtLeast(0L)
        window.flushTask = schedule(delay, "flush") {
            execute("flush") {
                flushWindow(window.windowStart)
            }
        }
    }

    private fun flushWindow(windowStart: Long) {
        if (sink == null) return
        val window = windows.remove(windowStart) ?: return
        window.flushTask?.cancel()
        val records = window.records.values.toList()
        records.forEach { record ->
            if (!record.finalized) {
                record.snapshotTask?.cancel()
                record.stopHandle()
                record.finalizeOmitted()
            }
        }
        if (records.isEmpty()) return
        val bytes = records.joinToString(separator = "") { record ->
            serializeLine(record.toMetadataRecord())
        }.encodeToByteArray()
        if (bytes.isEmpty()) return
        val captureEnd = windowStart + PhotoMetadataContract.WINDOW_MS
        payloads[PayloadKey(windowStart, captureEnd)] = bytes
        sink?.emit(
            SourceEmission(
                sourceId = PhotoMetadataContract.SOURCE_ID,
                stream = MAIN_STREAM,
                sourceKind = SourceKind.OBSERVER,
                captureStartEpochMs = windowStart,
                captureEndEpochMs = captureEnd,
                payloadRefs = listOf(
                    PayloadRef(
                        PhotoMetadataContract.PAYLOAD_NAME,
                        PhotoMetadataContract.MEDIA_TYPE,
                        bytes.size.toLong(),
                        null,
                    ),
                ),
                metadata = emptyMap(),
                gaps = emptyList(),
            ),
        )
    }

    private fun windowStart(epochMs: Long): Long =
        Math.floorDiv(epochMs, PhotoMetadataContract.WINDOW_MS) * PhotoMetadataContract.WINDOW_MS

    private fun flushDueWindows() {
        val now = scheduler.nowEpochMs()
        windows.keys
            .filter { windowStart -> windowStart + PhotoMetadataContract.WINDOW_MS <= now }
            .toList()
            .forEach(::flushWindow)
    }

    private fun execute(phase: String, task: () -> Unit) {
        scheduler.execute {
            runTask(phase, task)
        }
    }

    private fun schedule(delayMs: Long, phase: String, task: () -> Unit): Cancellable =
        scheduler.schedule(delayMs) {
            runTask(phase, task)
        }

    private fun runTask(phase: String, task: () -> Unit) {
        try {
            task()
        } catch (t: Throwable) {
            emitDiag("capture event=metadata-task-failed phase=$phase type=${t.javaClass.simpleName} message=${t.message ?: ""}")
        }
    }

    private fun emitDiag(line: String) {
        runCatching { diag(line) }
    }

    private data class PayloadKey(val captureStartEpochMs: Long, val captureEndEpochMs: Long)

    private data class RecordKey(val ts: Long, val sequence: Long) : Comparable<RecordKey> {
        override fun compareTo(other: RecordKey): Int =
            compareValuesBy(this, other, RecordKey::ts, RecordKey::sequence)
    }

    private class WindowBuffer(val windowStart: Long) {
        val records = TreeMap<RecordKey, PendingRecord>()
        var flushTask: Cancellable? = null
    }

    private class PendingRecord(
        val key: RecordKey,
        val ts: Long,
        val battery: BatterySnapshot?,
    ) {
        val rotationSamples = mutableListOf<RotationVectorSample>()
        val linearSamples = mutableListOf<LinearAccelerationSample>()
        var handle: ImuHandle? = null
        var snapshotTask: Cancellable? = null
        var tilt: Tilt? = null
        var motion: Motion? = null
        var finalized: Boolean = false

        fun stopHandle() {
            handle?.stop()
            handle = null
        }

        fun finalizeFromSamples() {
            if (finalized) return
            tilt = nearestTilt(ts, rotationSamples)
            motion = motionFrom(linearSamples)
            finalized = true
        }

        fun finalizeOmitted() {
            if (finalized) return
            finalized = true
        }

        fun toMetadataRecord(): PhotoMetadataRecord =
            PhotoMetadataRecord(
                ts = ts,
                battery = battery,
                tilt = tilt,
                motion = motion,
            )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
