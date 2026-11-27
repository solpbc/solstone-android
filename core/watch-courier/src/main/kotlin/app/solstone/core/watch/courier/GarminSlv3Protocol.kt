// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Wire-compatible Kotlin codec for the solstone Garmin watch app (`SLV3` audio slabs and
 * `SLVS` local link-status frames). Matches `solstone-garmin/source/VoiceProtocol.mc` byte-for-byte.
 */
object GarminSlv3Protocol {
    const val SOLSTONE_GARMIN_APP_ID = "c7b3e841-9d2a-4f6b-a108-6e4c2b9f3d12"
    const val SOLSTONE_GARMIN_APP_ID_HEX = "c7b3e8419d2a4f6ba1086e4c2b9f3d12"
    const val COMPANION_LINK_URL = "https://go.solstone.app/garmin"

    const val PROTO_VERSION = 1
    const val HDR_SIZE = 28
    const val STATUS_SIZE = 24

    const val FILE_NAME_GARMIN_AUDIO = "audio.ogg"
    const val MEDIA_TYPE_GARMIN_AUDIO = "audio/ogg"

    const val FLAG_SEG_START = 1
    const val FLAG_SEG_END = 2
    const val FLAG_ACK_REQ = 4
    const val FLAG_MORE_PENDING = 8
    const val FLAG_BG_ORIGIN = 16
    const val FLAG_SEG_PARTIAL = 32

    const val END_NONE = 0
    const val END_USER_STOP = 1
    const val END_STORAGE_FULL = 2
    const val END_MIC_DENIED = 3
    const val END_MIC_ERROR = 4
    const val END_UNEXPECTED = 5

    const val BG_DOORBELL_DELAY_MS = 2_500L

    private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

    fun matchesSolstoneAppId(appId: String?): Boolean {
        if (appId.isNullOrBlank()) return false
        return appId.trim().replace("-", "").lowercase() == SOLSTONE_GARMIN_APP_ID_HEX
    }

    fun fastTag16(bytes: ByteArray, offset: Int, len: Int): Int {
        var h = len and 0xFFFF
        if (len > 0) {
            val samples = if (len < 16) len else 16
            for (k in 0 until samples) {
                val idx = if (samples == 1) 0 else ((k * (len - 1)) / (samples - 1))
                val b = bytes[offset + idx].toInt() and 0xFF
                h = ((h * 33) + b + k) and 0xFFFF
            }
        }
        return h
    }

    fun isSlv3(raw: ByteArray): Boolean =
        raw.size >= HDR_SIZE &&
            raw[0] == 0x53.toByte() &&
            raw[1] == 0x4C.toByte() &&
            raw[2] == 0x56.toByte() &&
            raw[3] == 0x33.toByte()

    fun isSlvs(raw: ByteArray): Boolean =
        raw.size >= STATUS_SIZE &&
            raw[0] == 0x53.toByte() &&
            raw[1] == 0x4C.toByte() &&
            raw[2] == 0x56.toByte() &&
            raw[3] == 0x53.toByte()

    /**
     * Extracts raw bytes from Garmin Android Companion SDK 2.4.0's deserialized message list.
     * Note: Garmin SDK 2.4.0 deserializes Monkey C `ByteArray` into `ArrayList<Byte>` (`List<Number>`),
     * not `kotlin.ByteArray`. Handle both cleanly.
     */
    fun extractBinaryPayload(messageData: List<Any?>?): ByteArray? {
        if (messageData.isNullOrEmpty()) return null
        val first = messageData[0] ?: return null
        if (first is ByteArray) return first
        if (first is List<*>) {
            val out = ByteArray(first.size)
            for (i in first.indices) {
                val elem = first[i] as? Number ?: return null
                out[i] = elem.toByte()
            }
            return out
        }
        return null
    }

    data class Slv3Slab(
        val protoVer: Int,
        val flags: Int,
        val segId: Int,
        val seq: Long,
        val segEpochSec: Long,
        val tzOffsetMin: Int,
        val relMs: Long,
        val rateKhz: Int,
        val payloadLen: Int,
        val wireTag16: Int,
        val tagOk: Boolean,
        val payload: ByteArray,
    ) {
        val isSegStart: Boolean get() = (flags and FLAG_SEG_START) != 0
        val isSegEnd: Boolean get() = (flags and FLAG_SEG_END) != 0
        val isAckReq: Boolean get() = (flags and FLAG_ACK_REQ) != 0
        val isMorePending: Boolean get() = (flags and FLAG_MORE_PENDING) != 0
        val isBgOrigin: Boolean get() = (flags and FLAG_BG_ORIGIN) != 0
        val isSegPartial: Boolean get() = (flags and FLAG_SEG_PARTIAL) != 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Slv3Slab) return false
            return protoVer == other.protoVer && flags == other.flags && segId == other.segId &&
                seq == other.seq && segEpochSec == other.segEpochSec && tzOffsetMin == other.tzOffsetMin &&
                relMs == other.relMs && rateKhz == other.rateKhz && payloadLen == other.payloadLen &&
                wireTag16 == other.wireTag16 && tagOk == other.tagOk && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var r = protoVer
            r = 31 * r + flags
            r = 31 * r + segId
            r = 31 * r + seq.hashCode()
            r = 31 * r + segEpochSec.hashCode()
            r = 31 * r + tzOffsetMin
            r = 31 * r + relMs.hashCode()
            r = 31 * r + payload.contentHashCode()
            return r
        }
    }

    data class SlvsStatus(
        val protoVer: Int,
        val batPct: Int,
        val segId: Int,
        val freeKb: Int,
        val writeMaxMs: Int,
        val droppedChunks: Int,
        val bgWakeCount: Int,
        val resetCount: Int,
        val txErrs: Int,
        val pendingSlabs: Int,
        val statusFlags: Int,
        val lastEndReason: Int,
    ) {
        val charging: Boolean get() = (statusFlags and 1) != 0
        val stalled: Boolean get() = (statusFlags and 2) != 0
        val storageFull: Boolean get() = (statusFlags and 4) != 0
        val liveAudio: Boolean get() = (statusFlags and 8) != 0
        val interrupted: Boolean get() = (statusFlags and 16) != 0
    }

    data class WatchSegmentClock(
        val day: String,
        val segment: String,
        val startEpochMs: Long,
        val endEpochMs: Long,
        val zoneId: String,
        val utcOffsetSeconds: Int,
    )

    fun parseSlv3(raw: ByteArray): Slv3Slab? {
        if (!isSlv3(raw)) return null
        val bb = ByteBuffer.wrap(raw, 0, HDR_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.int // magic "SLV3"
        val protoVer = bb.get().toInt() and 0xFF
        val flags = bb.get().toInt() and 0xFF
        val segId = bb.short.toInt() and 0xFFFF
        val seq = bb.int.toLong() and 0xFFFFFFFFL
        val segEpochSec = bb.int.toLong() and 0xFFFFFFFFL
        val tzOffsetMin = bb.short.toInt() // signed int16
        val relMs = bb.int.toLong() and 0xFFFFFFFFL
        val rateKhz = bb.get().toInt() and 0xFF
        bb.get() // reserved
        val payloadLen = bb.short.toInt() and 0xFFFF
        val wireTag16 = bb.short.toInt() and 0xFFFF
        val actualPayloadLen = raw.size - HDR_SIZE
        val lenOk = (payloadLen == actualPayloadLen)
        val computedTag = if (lenOk && actualPayloadLen >= 0) fastTag16(raw, HDR_SIZE, actualPayloadLen) else -1
        val payload = if (lenOk && actualPayloadLen > 0) raw.copyOfRange(HDR_SIZE, raw.size) else ByteArray(0)
        return Slv3Slab(
            protoVer = protoVer,
            flags = flags,
            segId = segId,
            seq = seq,
            segEpochSec = segEpochSec,
            tzOffsetMin = tzOffsetMin,
            relMs = relMs,
            rateKhz = rateKhz,
            payloadLen = payloadLen,
            wireTag16 = wireTag16,
            tagOk = lenOk && (wireTag16 == computedTag),
            payload = payload,
        )
    }

    fun encodeSlv3(
        flags: Int,
        segId: Int,
        seq: Long,
        segEpochSec: Long,
        tzOffsetMin: Int,
        relMs: Long,
        payload: ByteArray,
        protoVer: Int = PROTO_VERSION,
        rateKhz: Int = 16,
    ): ByteArray {
        val out = ByteArray(HDR_SIZE + payload.size)
        val bb = ByteBuffer.wrap(out, 0, HDR_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.put(0x53.toByte()).put(0x4C.toByte()).put(0x56.toByte()).put(0x33.toByte())
        bb.put((protoVer and 0xFF).toByte())
        bb.put((flags and 0xFF).toByte())
        bb.putShort((segId and 0xFFFF).toShort())
        bb.putInt(seq.toInt())
        bb.putInt(segEpochSec.toInt())
        bb.putShort(tzOffsetMin.toShort())
        bb.putInt(relMs.toInt())
        bb.put((rateKhz and 0xFF).toByte())
        bb.put(0.toByte())
        bb.putShort((payload.size and 0xFFFF).toShort())
        bb.putShort((fastTag16(payload, 0, payload.size) and 0xFFFF).toShort())
        System.arraycopy(payload, 0, out, HDR_SIZE, payload.size)
        return out
    }

    fun parseSlvs(raw: ByteArray): SlvsStatus? {
        if (!isSlvs(raw)) return null
        val bb = ByteBuffer.wrap(raw, 0, STATUS_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.int // magic "SLVS"
        val protoVer = bb.get().toInt() and 0xFF
        val batPct = bb.get().toInt() and 0xFF
        val segId = bb.short.toInt() and 0xFFFF
        val freeKb = bb.short.toInt() and 0xFFFF
        val writeMaxMs = bb.short.toInt() and 0xFFFF
        val droppedChunks = bb.short.toInt() and 0xFFFF
        val bgWakeCount = bb.short.toInt() and 0xFFFF
        val resetCount = bb.short.toInt() and 0xFFFF
        val txErrs = bb.short.toInt() and 0xFFFF
        val pendingSlabs = bb.short.toInt() and 0xFFFF
        val statusFlags = bb.get().toInt() and 0xFF
        val lastEndReason = bb.get().toInt() and 0xFF
        return SlvsStatus(
            protoVer = protoVer,
            batPct = batPct,
            segId = segId,
            freeKb = freeKb,
            writeMaxMs = writeMaxMs,
            droppedChunks = droppedChunks,
            bgWakeCount = bgWakeCount,
            resetCount = resetCount,
            txErrs = txErrs,
            pendingSlabs = pendingSlabs,
            statusFlags = statusFlags,
            lastEndReason = lastEndReason,
        )
    }

    fun buildAckPayload(ackSeq: Long, tzPointLatLon100: IntArray? = null): Map<String, Any> {
        val out = LinkedHashMap<String, Any>(3)
        out["a"] = ackSeq.toInt()
        out["v"] = PROTO_VERSION
        if (tzPointLatLon100 != null && tzPointLatLon100.size == 2) {
            out["z"] = listOf(tzPointLatLon100[0], tzPointLatLon100[1])
        }
        return out
    }

    /**
     * Stamps journal `day` (`YYYY-MM-DD`) and `segment` (`HHMMSS_LEN`) strictly from the watch's
     * recording clock and local UTC offset (never phone receipt time or phone timezone).
     */
    fun watchLocalSegmentClock(
        segEpochSec: Long,
        tzOffsetMin: Int,
        durationSec: Int,
    ): WatchSegmentClock {
        val clampedOffsetMin = tzOffsetMin.coerceIn(-12 * 60, 14 * 60)
        val offsetSeconds = clampedOffsetMin * 60
        val zoneOffset = ZoneOffset.ofTotalSeconds(offsetSeconds)
        val startInstant = Instant.ofEpochSecond(segEpochSec)
        val localStart = startInstant.atOffset(zoneOffset)
        val dur = durationSec.coerceIn(1, 3600)
        val day = DAY_FMT.format(localStart)
        val hhmmss = TIME_FMT.format(localStart)
        val startMs = segEpochSec * 1000L
        return WatchSegmentClock(
            day = day,
            segment = "${hhmmss}_${dur}",
            startEpochMs = startMs,
            endEpochMs = startMs + dur * 1000L,
            zoneId = zoneOffset.id,
            utcOffsetSeconds = offsetSeconds,
        )
    }
}
