// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import java.io.IOException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okio.ByteString
import okio.ByteString.Companion.toByteString

class OkHttpWebSocketDuplexTest {
    @Test
    fun outputChunksWritesToSixtyFourKiB() {
        val socket = FakeWebSocket()
        OkHttpWebSocketDuplex(socket).use { duplex ->
            duplex.output.write(ByteArray(OkHttpWebSocketDuplex.MAX_WS_CHUNK_BYTES + 7) { 1 })
        }

        assertEquals(listOf(OkHttpWebSocketDuplex.MAX_WS_CHUNK_BYTES, 7), socket.sent.map { it.size })
    }

    @Test
    fun inboundBytesReadInOrder() {
        val duplex = OkHttpWebSocketDuplex(FakeWebSocket())
        duplex.receive(byteArrayOf(1, 2).toByteString())
        duplex.receive(byteArrayOf(3).toByteString())

        val out = ByteArray(3)
        assertEquals(2, duplex.input.read(out, 0, 2))
        assertEquals(1, duplex.input.read(out, 2, 1))
        assertContentEquals(byteArrayOf(1, 2, 3), out)
    }

    @Test
    fun inboundBackpressureBlocksThenReleases() {
        val duplex = OkHttpWebSocketDuplex(FakeWebSocket(), capacityBytes = 2)
        duplex.receive(byteArrayOf(1, 2).toByteString())

        var delivered = false
        val writer = thread(start = true) {
            duplex.receive(byteArrayOf(3).toByteString())
            delivered = true
        }
        Thread.sleep(100)
        assertTrue(writer.isAlive)

        assertEquals(1, duplex.input.read())
        assertEquals(2, duplex.input.read())
        writer.join(1000)
        assertTrue(delivered)
        assertEquals(3, duplex.input.read())
    }

    @Test
    fun closePropagatesEof() {
        val socket = FakeWebSocket()
        val duplex = OkHttpWebSocketDuplex(socket)

        duplex.closeInbound()

        assertEquals(-1, duplex.input.read())
    }

    @Test
    fun failurePropagatesToReadAndWrite() {
        val duplex = OkHttpWebSocketDuplex(FakeWebSocket())

        duplex.fail(IOException("boom"))

        assertFailsWith<IOException> { duplex.input.read() }
        assertFailsWith<IOException> { duplex.output.write(1) }
    }

    private class FakeWebSocket : BinaryWebSocket {
        val sent = mutableListOf<ByteArray>()
        var closed = false

        override fun send(bytes: ByteString): Boolean {
            sent += bytes.toByteArray()
            return true
        }

        override fun close(code: Int, reason: String?) {
            closed = true
        }
    }
}
