// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

        duplex.closeFromWebSocket(1000, "normal")

        assertEquals(-1, duplex.input.read())
    }

    @Test
    fun nonNormalClosePropagatesTypedException() {
        val duplex = OkHttpWebSocketDuplex(FakeWebSocket())

        duplex.closeFromWebSocket(4401, "expired")

        val readFailure = assertFailsWith<RelayWebSocketClosedException> { duplex.input.read() }
        assertEquals(4401, readFailure.code)
        assertEquals("expired", readFailure.reason)
        val writeFailure = assertFailsWith<RelayWebSocketClosedException> { duplex.output.write(1) }
        assertEquals(4401, writeFailure.code)
        assertEquals("expired", writeFailure.reason)
    }

    @Test
    fun failurePropagatesToReadAndWrite() {
        val duplex = OkHttpWebSocketDuplex(FakeWebSocket())

        duplex.fail(IOException("boom"))

        val readFailure = assertFailsWith<IOException> { duplex.input.read() }
        val writeFailure = assertFailsWith<IOException> { duplex.output.write(1) }
        assertFalse(readFailure is RelayWebSocketClosedException)
        assertFalse(writeFailure is RelayWebSocketClosedException)
    }

    @Test
    fun heldFirstReadTimesOutToWaitingException() {
        val duplex = OkHttpWebSocketDuplex(FakeWebSocket(), waitingTimeoutMillis = 30L)
        val failure = AtomicReference<Throwable?>()

        val reader = thread(start = true) {
            try {
                duplex.input.read()
            } catch (t: Throwable) {
                failure.set(t)
            }
        }

        reader.join(1000)
        assertFalse(reader.isAlive)
        assertTrue(failure.get() is RelayDialWaitingException)
        assertFalse(failure.get() is RelayWebSocketClosedException)
    }

    @Test
    fun disabledWaitingTimeoutKeepsBlocking() {
        val duplex = OkHttpWebSocketDuplex(FakeWebSocket(), waitingTimeoutMillis = 0L)
        val failure = AtomicReference<Throwable?>()
        val readResult = AtomicInteger(Int.MIN_VALUE)

        val reader = thread(start = true) {
            try {
                readResult.set(duplex.input.read())
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        Thread.sleep(100)
        assertTrue(reader.isAlive)

        duplex.close()
        reader.join(1000)
        assertFalse(reader.isAlive)
        assertFalse(failure.get() is RelayDialWaitingException)
        assertEquals(-1, readResult.get())
    }

    @Test
    fun brokerWithinWindowReturnsData() {
        val duplex = OkHttpWebSocketDuplex(FakeWebSocket(), waitingTimeoutMillis = 1000L)
        val failure = AtomicReference<Throwable?>()
        val readResult = AtomicInteger(Int.MIN_VALUE)
        val bytes = AtomicReference<ByteArray?>()

        val reader = thread(start = true) {
            try {
                val buffer = ByteArray(2)
                val read = duplex.input.read(buffer)
                readResult.set(read)
                bytes.set(buffer.copyOf(read))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        Thread.sleep(100)

        duplex.receive(byteArrayOf(4, 5).toByteString())

        reader.join(1000)
        assertFalse(reader.isAlive)
        assertEquals(2, readResult.get())
        assertContentEquals(byteArrayOf(4, 5), bytes.get())
        assertEquals(null, failure.get())
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
