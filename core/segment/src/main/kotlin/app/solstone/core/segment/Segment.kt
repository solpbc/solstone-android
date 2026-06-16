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

fun interface MonotonicClock {
    fun nanos(): Long
}

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

data class SegmenterAnchor(
    val epochMs: Long,
    val monotonicNanos: Long,
    val zoneId: ZoneId,
)

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
    private val clock: MonotonicClock,
    private val anchor: SegmenterAnchor,
    private val windowSeconds: Long = 300,
) {
    private val windowNanos = windowSeconds * 1_000_000_000L
    private val open = linkedMapOf<WindowKey, WindowBucket>()

    init {
        require(windowSeconds > 0) { "windowSeconds must be positive" }
    }

    fun feed(emission: SourceEmission): List<SealedSegment> {
        val sampleNanos = clock.nanos()
        val windowIndex = Math.floorDiv(sampleNanos - anchor.monotonicNanos, windowNanos)
        val sealed = sealWindowsBefore(windowIndex)
        val key = WindowKey(emission.sourceId, windowIndex)
        val bucket = open.getOrPut(key) { WindowBucket(emission.sourceId, windowIndex) }
        bucket.lastSampleNanos = maxOf(bucket.lastSampleNanos ?: sampleNanos, sampleNanos)
        bucket.payloads += emission.payloadRefs.map { ref ->
            SegmentPayload(
                sourceId = emission.sourceId,
                ref = ref,
                captureStartEpochMs = emission.captureStartEpochMs,
                captureEndEpochMs = emission.captureEndEpochMs,
            )
        }
        bucket.gaps += emission.gaps
        return sealed
    }

    fun flush(): List<SealedSegment> =
        open.values.sortedWith(compareBy<WindowBucket> { it.windowIndex }.thenBy { it.stream })
            .map { it.toSegment(fullWindow = false) }
            .also { open.clear() }

    private fun sealWindowsBefore(windowIndex: Long): List<SealedSegment> {
        val toSeal = open.keys.filter { it.windowIndex < windowIndex }.sortedWith(compareBy<WindowKey> { it.windowIndex }.thenBy { it.stream })
        return toSeal.mapNotNull { key -> open.remove(key)?.toSegment(fullWindow = true) }
    }

    private fun WindowBucket.toSegment(fullWindow: Boolean): SealedSegment {
        val windowStartEpochMs = anchor.epochMs + (windowIndex * windowSeconds * 1000L)
        val coveredSeconds = if (fullWindow) {
            windowSeconds
        } else {
            val windowStartNanos = anchor.monotonicNanos + (windowIndex * windowNanos)
            val last = lastSampleNanos ?: windowStartNanos
            ((last - windowStartNanos).coerceAtLeast(0L)) / 1_000_000_000L
        }
        val keys = wireKeys(windowStartEpochMs, windowStartEpochMs + (coveredSeconds * 1000L), anchor.zoneId)
        return SealedSegment(
            stream = stream,
            key = SegmentKey(keys.day, keys.segment),
            wireKeys = keys,
            payloads = payloads.toList(),
            gaps = gaps.sortedWith(compareBy<GapEvent> { it.atEpochMs }.thenBy { it.kind }.thenBy { it.detail ?: "" }),
        )
    }

    private data class WindowKey(val stream: String, val windowIndex: Long)

    private data class WindowBucket(
        val stream: String,
        val windowIndex: Long,
        val payloads: MutableList<SegmentPayload> = mutableListOf(),
        val gaps: MutableList<GapEvent> = mutableListOf(),
        var lastSampleNanos: Long? = null,
    )
}
