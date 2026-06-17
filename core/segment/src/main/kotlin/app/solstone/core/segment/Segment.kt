// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.segment

import app.solstone.core.model.GapEvent
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SourceEmission
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun wireKeys(startEpochMs: Long, endEpochMs: Long, zoneId: ZoneId): WireKeys {
    val start = Instant.ofEpochMilli(startEpochMs).atZone(zoneId)
    val lenSeconds = ((endEpochMs - startEpochMs).coerceAtLeast(0L)) / 1000L
    val day = DateTimeFormatter.BASIC_ISO_DATE.format(start.toLocalDate())
    val segment = "%02d%02d%02d_%d".format(start.hour, start.minute, start.second, lenSeconds)
    return WireKeys(
        day = day,
        segment = segment,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        zoneId = zoneId.id,
        utcOffsetSeconds = start.offset.totalSeconds,
    )
}

fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    input.use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sha256(path: Path): String = Files.newInputStream(path).use { sha256(it) }

data class SegmentPayload(
    val sourceId: String,
    val ref: PayloadRef,
    val captureStartEpochMs: Long,
    val captureEndEpochMs: Long,
)

data class SealedSegment(
    val stream: String,
    val key: SegmentKey,
    val wireKeys: WireKeys,
    val payloads: List<SegmentPayload>,
    val gaps: List<GapEvent>,
)

class Segmenter(
    private val zoneId: ZoneId,
    private val windowMs: Long = 300_000L,
    private val graceMs: Long = 5_000L,
) {
    private val open = linkedMapOf<WindowKey, WindowBucket>()
    private val sealedWatermark = mutableMapOf<String, Long>()

    init {
        require(windowMs > 0) { "windowMs must be positive" }
        require(graceMs >= 0) { "graceMs must not be negative" }
    }

    fun feed(emission: SourceEmission): List<SealedSegment> {
        val stream = emission.stream
        val windowStartEpochMs = windowStart(emission.captureStartEpochMs)
        val watermark = sealedWatermark[stream]
        if (watermark != null && windowStartEpochMs <= watermark) {
            val lateGap = GapEvent(
                kind = "late_emission",
                atEpochMs = emission.captureStartEpochMs,
                detail = windowStartEpochMs.toString(),
            )
            val lateWindowStart = watermark + windowMs
            val bucket = open.getOrPut(WindowKey(stream, lateWindowStart)) {
                WindowBucket(stream = stream, windowStartEpochMs = lateWindowStart)
            }
            bucket.gaps += lateGap
            bucket.maxCaptureEndEpochMs = maxOf(bucket.maxCaptureEndEpochMs, lateWindowStart)
            return sealWindowsBefore(stream, windowStartEpochMs)
        }

        val key = WindowKey(stream, windowStartEpochMs)
        val bucket = open.getOrPut(key) { WindowBucket(stream = stream, windowStartEpochMs = windowStartEpochMs) }
        bucket.maxCaptureEndEpochMs = maxOf(bucket.maxCaptureEndEpochMs, emission.captureEndEpochMs)
        bucket.payloads += emission.payloadRefs.map { ref ->
            SegmentPayload(
                sourceId = emission.sourceId,
                ref = ref,
                captureStartEpochMs = emission.captureStartEpochMs,
                captureEndEpochMs = emission.captureEndEpochMs,
            )
        }
        bucket.gaps += emission.gaps
        return sealWindowsBefore(stream, windowStartEpochMs)
    }

    fun sealDue(nowEpochMs: Long): List<SealedSegment> {
        val toSeal = open.keys
            .filter { key -> key.windowStartEpochMs + windowMs + graceMs <= nowEpochMs }
            .sortedWith(windowKeyComparator)
        return toSeal.mapNotNull { key -> seal(key) }
    }

    fun flush(): List<SealedSegment> =
        open.keys.sortedWith(windowKeyComparator)
            .mapNotNull { key -> seal(key) }
            .also { open.clear() }

    private fun windowStart(epochMs: Long): Long =
        Math.floorDiv(epochMs, windowMs) * windowMs

    private fun sealWindowsBefore(stream: String, windowStartEpochMs: Long): List<SealedSegment> {
        val toSeal = open.keys
            .filter { it.stream == stream && it.windowStartEpochMs < windowStartEpochMs }
            .sortedWith(windowKeyComparator)
        return toSeal.mapNotNull { key -> seal(key) }
    }

    private fun seal(key: WindowKey): SealedSegment? {
        val bucket = open.remove(key) ?: return null
        sealedWatermark[key.stream] = maxOf(sealedWatermark[key.stream] ?: Long.MIN_VALUE, key.windowStartEpochMs)
        return bucket.toSegment()
    }

    private fun WindowBucket.toSegment(): SealedSegment {
        val coveredMs = (maxCaptureEndEpochMs - windowStartEpochMs).coerceIn(0L, windowMs)
        val keys = wireKeys(windowStartEpochMs, windowStartEpochMs + coveredMs, zoneId)
        return SealedSegment(
            stream = stream,
            key = SegmentKey(keys.day, keys.segment),
            wireKeys = keys,
            payloads = payloads.toList(),
            gaps = gaps.sortedWith(compareBy<GapEvent> { it.atEpochMs }.thenBy { it.kind }.thenBy { it.detail ?: "" }),
        )
    }

    private data class WindowKey(val stream: String, val windowStartEpochMs: Long)

    private data class WindowBucket(
        val stream: String,
        val windowStartEpochMs: Long,
        val payloads: MutableList<SegmentPayload> = mutableListOf(),
        val gaps: MutableList<GapEvent> = mutableListOf(),
        var maxCaptureEndEpochMs: Long = windowStartEpochMs,
    )

    private companion object {
        val windowKeyComparator: Comparator<WindowKey> =
            compareBy<WindowKey> { it.windowStartEpochMs }.thenBy { it.stream }
    }
}
