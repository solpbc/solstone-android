// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

const val FLAG_OPEN = 0x01
const val FLAG_DATA = 0x02
const val FLAG_CLOSE = 0x04
const val FLAG_RESET = 0x08
const val FLAG_WINDOW = 0x10
const val FLAG_PING = 0x20
const val FLAG_PONG = 0x40
const val FLAG_RESERVED = 0x80

val VALID_RECEIVE_FLAGS = setOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x10, 0x20, 0x40)

class Frame(
    val streamId: Int,
    val flags: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return streamId == other.streamId &&
            flags == other.flags &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = streamId
        result = 31 * result + flags
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Frame(streamId=$streamId, flags=$flags, payload=${payload.contentToString()})"
}

data class DecodedFrame(val frame: Frame, val bytesConsumed: Int)

fun encodeFrame(streamId: Int, flags: Int, payload: ByteArray): ByteArray {
    if (payload.size > 0xffffff) {
        throw IllegalArgumentException("PL frame payload too large: ${payload.size}")
    }
    val out = ByteArray(8 + payload.size)
    out[0] = ((streamId shr 24) and 0xff).toByte()
    out[1] = ((streamId shr 16) and 0xff).toByte()
    out[2] = ((streamId shr 8) and 0xff).toByte()
    out[3] = (streamId and 0xff).toByte()
    out[4] = flags.toByte()
    out[5] = ((payload.size shr 16) and 0xff).toByte()
    out[6] = ((payload.size shr 8) and 0xff).toByte()
    out[7] = (payload.size and 0xff).toByte()
    payload.copyInto(out, 8)
    return out
}

fun encodeWindowCredit(n: Int): ByteArray = byteArrayOf(
    ((n shr 24) and 0xff).toByte(),
    ((n shr 16) and 0xff).toByte(),
    ((n shr 8) and 0xff).toByte(),
    (n and 0xff).toByte(),
)

fun decodeFrame(buf: ByteArray, offset: Int = 0): DecodedFrame {
    if (offset < 0 || buf.size - offset < 8) {
        throw IllegalArgumentException("socket closed while reading frame")
    }
    val streamId = ((buf[offset].toInt() and 0xff) shl 24) or
        ((buf[offset + 1].toInt() and 0xff) shl 16) or
        ((buf[offset + 2].toInt() and 0xff) shl 8) or
        (buf[offset + 3].toInt() and 0xff)
    val flags = buf[offset + 4].toInt() and 0xff
    val length = ((buf[offset + 5].toInt() and 0xff) shl 16) or
        ((buf[offset + 6].toInt() and 0xff) shl 8) or
        (buf[offset + 7].toInt() and 0xff)
    if (buf.size - offset < 8 + length) {
        throw IllegalArgumentException("socket closed while reading frame")
    }
    return DecodedFrame(
        Frame(streamId, flags, buf.copyOfRange(offset + 8, offset + 8 + length)),
        8 + length,
    )
}

fun controlPong(frame: Frame): Frame? =
    if (frame.streamId == 0 && frame.flags == FLAG_PING && frame.payload.size == 8) {
        Frame(0, FLAG_PONG, frame.payload)
    } else {
        null
    }

class FrameDialer {
    var nextStreamId = 1
        private set

    fun allocate(): Int {
        val id = nextStreamId
        nextStreamId += 2
        return id
    }
}
