// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MuxFrameTest {
    @Test
    fun encodeDecodeRoundTripsEveryFlag() {
        listOf(FLAG_OPEN, FLAG_DATA, FLAG_CLOSE, FLAG_RESET, FLAG_WINDOW, FLAG_PING, FLAG_PONG).forEach { flag ->
            val frame = Frame(7, flag, byteArrayOf(1, 2, 3))
            val decoded = decodeFrame(encodeFrame(frame.streamId, frame.flags, frame.payload))
            assertEquals(frame, decoded.frame)
            assertEquals(11, decoded.bytesConsumed)
        }
    }

    @Test
    fun headerBytesAreBigEndian() {
        val encoded = encodeFrame(0x01020304, FLAG_OPEN or FLAG_DATA, byteArrayOf(9, 8, 7))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 3, 0, 0, 3), encoded.copyOfRange(0, 8))
    }

    @Test
    fun controlPingPongAndDialerMatchReference() {
        val payload = ByteArray(8) { it.toByte() }
        assertEquals(Frame(0, FLAG_PONG, payload), controlPong(Frame(0, FLAG_PING, payload)))
        assertNull(controlPong(Frame(1, FLAG_PING, payload)))
        assertNull(controlPong(Frame(0, FLAG_PING, ByteArray(7))))
        assertNull(controlPong(Frame(0, FLAG_PING or FLAG_PONG, payload)))
        assertNull(controlPong(Frame(0, FLAG_PING or FLAG_OPEN, payload)))

        val dialer = FrameDialer()
        assertEquals(1, dialer.allocate())
        assertEquals(3, dialer.allocate())
        assertEquals(5, dialer.allocate())
    }

    @Test
    fun rejectsOversizedPayload() {
        assertFailsWith<IllegalArgumentException> {
            encodeFrame(1, FLAG_DATA, ByteArray(0x1000000))
        }
    }

    @Test
    fun windowCreditBytesAreBigEndian() {
        assertContentEquals(byteArrayOf(0x01, 0x23, 0x45, 0x67), encodeWindowCredit(0x01234567))
    }
}
