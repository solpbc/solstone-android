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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

fun systemZoneId(): ZoneId = ZoneId.systemDefault()

// A segment key's LEN is the captured duration in whole seconds, bounded so that a
// sub-second capture still names a segment of at least one second and no key spans
// more than one capture window. Every key, live or recovered, is built here.
const val MIN_SEGMENT_LEN_SECONDS = 1L
const val MAX_SEGMENT_LEN_SECONDS = 300L

fun wireKeys(startEpochMs: Long, endEpochMs: Long, zoneId: ZoneId): WireKeys {
    val start = Instant.ofEpochMilli(startEpochMs).atZone(zoneId)
    val lenSeconds = ((endEpochMs - startEpochMs) / 1000L).coerceIn(MIN_SEGMENT_LEN_SECONDS, MAX_SEGMENT_LEN_SECONDS)
    val day = DateTimeFormatter.BASIC_ISO_DATE.format(start.toLocalDate())
    val segment = "%02d%02d%02d_%d".format(Locale.ROOT, start.hour, start.minute, start.second, lenSeconds)
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

data class SegmenterResult(
    val sealed: List<SealedSegment>,
    val droppedPayloads: List<SegmentPayload>,
)

class Segmenter(
    private val zoneSource: () -> ZoneId,
    private val windowMs: Long = 300_000L,
    private val graceMs: Long = 5_000L,
) {
    constructor(zoneId: ZoneId, windowMs: Long = 300_000L, graceMs: Long = 5_000L) : this({ zoneId }, windowMs, graceMs)

    private val open = linkedMapOf<WindowKey, WindowBucket>()
    private val sealedWatermark = mutableMapOf<String, Long>()
    private val windowHolds = mutableMapOf<WindowKey, Int>()
    private var lastSuccessfulZone: ZoneId? = null

    init {
        require(windowMs > 0) { "windowMs must be positive" }
        require(graceMs >= 0) { "graceMs must not be negative" }
    }

    fun feed(emission: SourceEmission): SegmenterResult {
        val stream = emission.stream
        val windowStartEpochMs = windowStart(emission.captureStartEpochMs)
        if (emission.independentTimeline) {
            // Closed media owns its first-sample timeline. It neither waits for
            // nor advances the watermark of the shared source windows.
            val zone = emission.metadata["zoneId"]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: resolveBucketZone(windowStartEpochMs)
            val keys = wireKeys(emission.captureStartEpochMs, emission.captureEndEpochMs, zone)
            val mediaKeys = keys.copy(utcOffsetSeconds =
                emission.metadata["utcOffsetSeconds"]?.toIntOrNull() ?: keys.utcOffsetSeconds)
            return SegmenterResult(listOf(SealedSegment(stream, SegmentKey(mediaKeys.day, mediaKeys.segment),
                mediaKeys, emission.payloadRefs.map { ref -> SegmentPayload(emission.sourceId, ref,
                    emission.captureStartEpochMs, emission.captureEndEpochMs) }, emission.gaps)), emptyList())
        }
        val watermark = sealedWatermark[stream]
        if (watermark != null && windowStartEpochMs <= watermark) {
            val droppedPayloads = emission.payloadRefs.map { ref ->
                SegmentPayload(
                    sourceId = emission.sourceId,
                    ref = ref,
                    captureStartEpochMs = emission.captureStartEpochMs,
                    captureEndEpochMs = emission.captureEndEpochMs,
                )
            }
            val lateGap = GapEvent(
                kind = "late_emission",
                atEpochMs = emission.captureStartEpochMs,
                detail = windowStartEpochMs.toString(),
            )
            val lateWindowStart = watermark + windowMs
            val bucket = open.getOrPut(WindowKey(stream, lateWindowStart)) {
                WindowBucket(
                    stream = stream,
                    windowStartEpochMs = lateWindowStart,
                    zoneId = resolveBucketZone(lateWindowStart),
                )
            }
            bucket.gaps += lateGap
            bucket.gaps += emission.gaps
            bucket.maxCaptureEndEpochMs = maxOf(bucket.maxCaptureEndEpochMs, lateWindowStart)
            return SegmenterResult(
                sealed = sealWindowsBefore(stream, emission.captureStartEpochMs),
                droppedPayloads = droppedPayloads,
            )
        }

        val key = WindowKey(stream, windowStartEpochMs)
        val bucket = open.getOrPut(key) {
            WindowBucket(
                stream = stream,
                windowStartEpochMs = windowStartEpochMs,
                zoneId = resolveBucketZone(windowStartEpochMs),
            )
        }
        bucket.maxCaptureEndEpochMs = maxOf(bucket.maxCaptureEndEpochMs, emission.captureEndEpochMs)
        val incoming = emission.payloadRefs.map { ref ->
            SegmentPayload(
                sourceId = emission.sourceId,
                ref = ref,
                captureStartEpochMs = emission.captureStartEpochMs,
                captureEndEpochMs = emission.captureEndEpochMs,
            )
        }
        bucket.payloads += incoming
        bucket.gaps += emission.gaps
        return SegmenterResult(
            sealed = sealWindowsBefore(stream, emission.captureStartEpochMs),
            droppedPayloads = emptyList(),
        )
    }

    fun holdWindow(stream: String, windowStartEpochMs: Long) {
        val key = WindowKey(stream, windowStartEpochMs)
        windowHolds[key] = (windowHolds[key] ?: 0) + 1
    }

    fun releaseWindow(stream: String, windowStartEpochMs: Long) {
        val key = WindowKey(stream, windowStartEpochMs)
        val count = windowHolds[key] ?: 0
        if (count <= 1) {
            windowHolds.remove(key)
        } else {
            windowHolds[key] = count - 1
        }
    }

    fun sealDue(nowEpochMs: Long): SegmenterResult {
        val minHeldByStream = minHeldByStream()
        val toSeal = open.keys
            .filter { key ->
                val minHeld = minHeldByStream[key.stream]
                (minHeld == null || key.windowStartEpochMs < minHeld) &&
                    key.windowStartEpochMs + windowMs + graceMs <= nowEpochMs
            }
            .sortedWith(windowKeyComparator)
        return SegmenterResult(
            sealed = toSeal.flatMap { key -> seal(key) },
            droppedPayloads = emptyList(),
        )
    }

    fun flush(): SegmenterResult =
        SegmenterResult(
            sealed = open.keys.sortedWith(windowKeyComparator)
                .flatMap { key -> seal(key) }
                .also {
                    open.clear()
                    windowHolds.clear()
                },
            droppedPayloads = emptyList(),
        )

    private fun resolveBucketZone(windowStartEpochMs: Long): ZoneId =
        try {
            val resolved = zoneSource()
            lastSuccessfulZone = resolved
            resolved
        } catch (_: Exception) {
            lastSuccessfulZone ?: ZoneOffset.ofTotalSeconds(
                Math.toIntExact(Math.floorDiv(TimeZone.getDefault().getOffset(windowStartEpochMs).toLong(), 1000L)),
            )
        }

    fun windowStart(epochMs: Long): Long =
        Math.floorDiv(epochMs, windowMs) * windowMs

    private fun sealWindowsBefore(stream: String, incomingCaptureStartEpochMs: Long): List<SealedSegment> {
        val minHeld = minHeldForStream(stream)
        val toSeal = open.keys
            .filter { key ->
                key.stream == stream &&
                    (minHeld == null || key.windowStartEpochMs < minHeld) &&
                    key.windowStartEpochMs + windowMs + graceMs <= incomingCaptureStartEpochMs
            }
            .sortedWith(windowKeyComparator)
        return toSeal.flatMap { key -> seal(key) }
    }

    private fun minHeldByStream(): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        for ((key, count) in windowHolds) {
            if (count > 0) {
                val current = map[key.stream]
                if (current == null || key.windowStartEpochMs < current) {
                    map[key.stream] = key.windowStartEpochMs
                }
            }
        }
        return map
    }

    private fun minHeldForStream(stream: String): Long? =
        windowHolds.entries
            .filter { it.key.stream == stream && it.value > 0 }
            .minOfOrNull { it.key.windowStartEpochMs }

    private fun seal(key: WindowKey): List<SealedSegment> {
        val bucket = open.remove(key) ?: return emptyList()
        sealedWatermark[key.stream] = maxOf(sealedWatermark[key.stream] ?: Long.MIN_VALUE, key.windowStartEpochMs)
        return bucket.toSegments()
    }

    private fun WindowBucket.toSegments(): List<SealedSegment> {
        if (payloads.isEmpty() && gaps.isEmpty()) return emptyList()
        val coveredMs = (maxCaptureEndEpochMs - windowStartEpochMs).coerceIn(0L, windowMs)
        val endEpochMs = windowStartEpochMs + coveredMs
        val earliestPayloadStart = payloads.minOfOrNull { it.captureStartEpochMs }
        // Payloads starting within graceMs of windowStartEpochMs (including equal) keep the mark name.
        val firstStartEpochMs = if (earliestPayloadStart != null && earliestPayloadStart - windowStartEpochMs > graceMs) {
            earliestPayloadStart
        } else {
            windowStartEpochMs
        }
        // The journal places a recording from the start of its segment, so a recording that
        // begins more than graceMs into a segment opens one of its own. Everything that began
        // before it stays whole in the earlier segment.
        val starts = mutableListOf(firstStartEpochMs)
        payloads.filter { it.ref.mediaType.startsWith("audio/") }
            .map { it.captureStartEpochMs }
            .sorted()
            .forEach { if (it - starts.last() > graceMs) starts += it }
        val segmentOf = { epochMs: Long -> starts.indexOfLast { it <= epochMs }.coerceAtLeast(0) }
        val payloadsBySegment = payloads.groupBy { segmentOf(it.captureStartEpochMs) }
        val gapsBySegment = gaps.groupBy { segmentOf(it.atEpochMs) }
        return starts.mapIndexed { index, startEpochMs ->
            val segmentPayloads = payloadsBySegment[index].orEmpty()
            val segmentEndEpochMs = if (index == starts.lastIndex) {
                endEpochMs
            } else {
                (segmentPayloads.maxOfOrNull { it.captureEndEpochMs } ?: startEpochMs).coerceIn(startEpochMs, endEpochMs)
            }
            val keys = wireKeys(startEpochMs, segmentEndEpochMs, zoneId)
            SealedSegment(stream, SegmentKey(keys.day, keys.segment), keys, segmentPayloads,
                gapsBySegment[index].orEmpty()
                    .sortedWith(compareBy<GapEvent> { it.atEpochMs }.thenBy { it.kind }.thenBy { it.detail ?: "" }))
        }
    }

    private data class WindowKey(val stream: String, val windowStartEpochMs: Long)

    private data class WindowBucket(
        val stream: String,
        val windowStartEpochMs: Long,
        val zoneId: ZoneId,
        val payloads: MutableList<SegmentPayload> = mutableListOf(),
        val gaps: MutableList<GapEvent> = mutableListOf(),
        var maxCaptureEndEpochMs: Long = windowStartEpochMs,
    )

    private companion object {
        val windowKeyComparator: Comparator<WindowKey> =
            compareBy<WindowKey> { it.windowStartEpochMs }.thenBy { it.stream }
    }
}
