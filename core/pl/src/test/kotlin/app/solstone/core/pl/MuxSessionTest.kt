// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_INITIAL_RECEIVE_WINDOW = 1024 * 1024
private const val TEST_MAX_DATA_CHUNK_BYTES = 64 * 1024
private const val TEST_SESSION_UNUSABLE = "PL session unusable"

class MuxSessionTest {
    @Test
    fun requestResponseRoundTripsOverByteDuplex() {
        val (client, server) = pairedDuplexes()
        val home = startHome(server) { duplex ->
            val request = readFrame(duplex.input)
            val requestText = request.payload.toString(Charsets.US_ASCII)
            assertEquals(1, request.streamId)
            assertTrue((request.flags and FLAG_OPEN) != 0)
            assertTrue((request.flags and FLAG_DATA) != 0)
            assertTrue(requestText.contains("GET /x HTTP/1.1"))
            assertEquals(FLAG_CLOSE, readFrame(duplex.input).flags)
            sendFrame(duplex, request.streamId, FLAG_DATA or FLAG_CLOSE, responseBytes(32))
            pollFrame(duplex.input, iterations = 20)
        }

        client.use { duplex ->
            val response = MuxSession(duplex).request("GET", "/x", emptyMap(), null)
            assertEquals(200, response.status)
        }
        finishHome(home)
    }

    @Test
    fun responseBeyondInitialWindowEmitsGrantAndCompletes() {
        val (client, server) = pairedDuplexes()
        val total = TEST_INITIAL_RECEIVE_WINDOW + 17
        val response = responseBytes(total)
        val home = startHome(server) { duplex ->
            val streamId = readRequest(duplex)
            sendFrame(duplex, streamId, FLAG_DATA, response.copyOfRange(0, TEST_INITIAL_RECEIVE_WINDOW))
            val grant = pollFrame(duplex.input)
            if (grant == null) duplex.output.close()
            assertWindow(assertNotNull(grant), streamId, TEST_INITIAL_RECEIVE_WINDOW)
            sendFrame(duplex, streamId, FLAG_DATA or FLAG_CLOSE, response.copyOfRange(TEST_INITIAL_RECEIVE_WINDOW, total))
            assertWindow(readFrame(duplex.input), streamId, 17)
        }

        client.use { assertEquals(total - responseHeader().size, MuxSession(it).request("GET", "/a1", emptyMap(), null).body.size) }
        finishHome(home)
    }

    @Test
    fun windowGrantEqualsConsumedDataFrameSize() {
        val (client, server) = pairedDuplexes()
        val chunkSize = 300_000
        val response = responseBytes(chunkSize)
        val home = startHome(server) { duplex ->
            val streamId = readRequest(duplex)
            sendFrame(duplex, streamId, FLAG_DATA or FLAG_CLOSE, response)
            val grant = pollFrame(duplex.input)
            if (grant == null) duplex.output.close()
            assertWindow(assertNotNull(grant), streamId, chunkSize)
        }

        client.use { MuxSession(it).request("GET", "/a2", emptyMap(), null) }
        finishHome(home)
    }

    @Test
    fun dataBeyondReceiveWindowResetsWithFlowControlError() {
        val (client, server) = pairedDuplexes(bufferSize = TEST_INITIAL_RECEIVE_WINDOW + 32)
        val home = startHome(server) { duplex ->
            val streamId = readRequest(duplex)
            sendFrame(duplex, streamId, FLAG_DATA or FLAG_CLOSE, responseBytes(TEST_INITIAL_RECEIVE_WINDOW + 1))
            assertReset(assertNotNull(pollFrame(duplex.input)), streamId, 0x02)
        }

        client.use {
            val error = assertFailsWith<IOException> { MuxSession(it).request("GET", "/a3", emptyMap(), null) }
            assertEquals("PL receive window exceeded", error.message)
        }
        finishHome(home)
    }

    @Test
    fun responseHardCeilingRemainsDistinctFromReceiveWindow() {
        val (client, server) = pairedDuplexes()
        val response = responseBytes(2 * TEST_INITIAL_RECEIVE_WINDOW + 1)
        val home = startHome(server) { duplex ->
            val streamId = readRequest(duplex)
            sendFrame(duplex, streamId, FLAG_DATA, response.copyOfRange(0, TEST_INITIAL_RECEIVE_WINDOW))
            val first = pollFrame(duplex.input)
            if (first == null) duplex.output.close()
            assertWindow(assertNotNull(first), streamId, TEST_INITIAL_RECEIVE_WINDOW)
            sendFrame(duplex, streamId, FLAG_DATA, response.copyOfRange(TEST_INITIAL_RECEIVE_WINDOW, 2 * TEST_INITIAL_RECEIVE_WINDOW))
            assertWindow(assertNotNull(pollFrame(duplex.input)), streamId, TEST_INITIAL_RECEIVE_WINDOW)
            sendFrame(duplex, streamId, FLAG_DATA, response.copyOfRange(2 * TEST_INITIAL_RECEIVE_WINDOW, response.size))
            assertReset(assertNotNull(pollFrame(duplex.input)), streamId, 0x05)
        }

        client.use {
            val error = assertFailsWith<IOException> { MuxSession(it).request("GET", "/a4", emptyMap(), null) }
            assertEquals("PL response too large", error.message)
        }
        finishHome(home)
    }

    @Test
    fun largeSerializedRequestIsFragmentedIntoSixtyFourKiBDataFrames() {
        val (client, server) = pairedDuplexes(bufferSize = 128 * 1024)
        val body = ByteArray(TEST_MAX_DATA_CHUNK_BYTES + 7) { (it % 251).toByte() }
        val home = startHome(server) { duplex ->
            val frames = readRequestFrames(duplex)
            val dataFrames = frames.dropLast(1)
            assertTrue(dataFrames.size > 1)
            assertTrue(dataFrames.all { it.payload.size <= TEST_MAX_DATA_CHUNK_BYTES })
            assertEquals(FLAG_OPEN or FLAG_DATA, dataFrames.first().flags)
            assertTrue(dataFrames.drop(1).all { it.flags == FLAG_DATA })
            assertEquals(FLAG_CLOSE, frames.last().flags)
            assertContentEquals(httpRequestBytes("POST", "/b1", emptyMap(), body), dataFrames.flatMap { it.payload.asIterable() }.toByteArray())
            sendFrame(duplex, dataFrames.first().streamId, FLAG_DATA or FLAG_CLOSE, responseBytes(32))
            assertNotNull(pollFrame(duplex.input))
        }

        client.use { MuxSession(it).request("POST", "/b1", emptyMap(), body) }
        finishHome(home)
    }

    @Test
    fun partialFrameFailurePoisonsSessionWithoutSecondRequestIo() {
        val (rawClient, server) = pairedDuplexes()
        val client = TrackingDuplex(rawClient)
        val home = startHome(server) { duplex ->
            val streamId = readRequest(duplex)
            val encoded = encodeFrame(streamId, FLAG_DATA, ByteArray(64))
            duplex.output.write(encoded, 0, 16)
            duplex.output.flush()
            duplex.output.close()
        }
        val session = MuxSession(client)
        val first = assertFailsWith<IOException> { session.request("GET", "/c1", emptyMap(), null) }
        assertEquals("socket closed while reading PL frame", first.message)
        val reads = client.readCount
        val writes = client.writeCount
        val second = assertFailsWith<IOException> { session.request("GET", "/c1-again", emptyMap(), null) }
        assertEquals(TEST_SESSION_UNUSABLE, second.message)
        assertEquals(reads, client.readCount)
        assertEquals(writes, client.writeCount)
        client.close()
        finishHome(home)
    }

    @Test
    fun reservedFlagPoisonsSession() {
        val (client, server) = pairedDuplexes()
        val home = startHome(server) { duplex ->
            val streamId = readRequest(duplex)
            sendFrame(duplex, streamId, FLAG_DATA or FLAG_CLOSE or 0x80, responseBytes(32))
        }
        val session = MuxSession(client)
        assertEquals("PL protocol error: reserved flag", assertFailsWith<IOException> { session.request("GET", "/d1", emptyMap(), null) }.message)
        assertEquals(TEST_SESSION_UNUSABLE, assertFailsWith<IOException> { session.request("GET", "/d1-again", emptyMap(), null) }.message)
        client.close()
        finishHome(home)
    }

    @Test
    fun illegalActiveFlagsResetStreamWithoutPoisoningSession() {
        val (client, server) = pairedDuplexes()
        val home = startHome(server) { duplex ->
            val firstId = readRequest(duplex)
            sendFrame(duplex, firstId, FLAG_DATA or FLAG_RESET, byteArrayOf(9))
            assertReset(assertNotNull(pollFrame(duplex.input)), firstId, 0x01)
            val secondId = readRequest(duplex)
            sendFrame(duplex, secondId, FLAG_DATA or FLAG_CLOSE, responseBytes(32))
            assertWindow(readFrame(duplex.input), secondId, 32)
        }
        val session = MuxSession(client)
        assertEquals("PL protocol error: invalid flags", assertFailsWith<IOException> { session.request("GET", "/d2", emptyMap(), null) }.message)
        assertEquals(200, session.request("GET", "/d2-again", emptyMap(), null).status)
        client.close()
        finishHome(home)
    }

    @Test
    fun foreignStreamFramesFollowUnknownIdPolicy() {
        val (client, server) = pairedDuplexes()
        val home = startHome(server) { duplex ->
            val streamId = readRequest(duplex)
            sendFrame(duplex, 20, FLAG_DATA, byteArrayOf(1))
            assertReset(assertNotNull(pollFrame(duplex.input)), 20, 0x01)
            sendFrame(duplex, 22, FLAG_WINDOW, byteArrayOf(0, 0, 0, 1))
            assertReset(assertNotNull(pollFrame(duplex.input)), 22, 0x01)
            sendFrame(duplex, 24, FLAG_OPEN, ByteArray(0))
            assertReset(assertNotNull(pollFrame(duplex.input)), 24, 0x01)
            sendFrame(duplex, 26, FLAG_CLOSE, ByteArray(0))
            sendFrame(duplex, 28, FLAG_RESET, byteArrayOf(1))
            assertNull(pollFrame(duplex.input, iterations = 20))
            sendFrame(duplex, streamId, FLAG_DATA or FLAG_CLOSE, responseBytes(32))
            pollFrame(duplex.input, iterations = 20)
        }
        client.use { assertEquals(200, MuxSession(it).request("GET", "/d3", emptyMap(), null).status) }
        finishHome(home)
    }

    @Test
    fun pingPongCombinationPoisonsWithoutPong() {
        assertMalformedControlPoisons(FLAG_PING or FLAG_PONG, ByteArray(8) { it.toByte() })
    }

    @Test
    fun wrongLengthPingPoisonsWithoutPong() {
        assertMalformedControlPoisons(FLAG_PING, ByteArray(7))
    }

    @Test
    fun validPingAndStrayPongPreserveRequest() {
        val (client, server) = pairedDuplexes()
        val nonce = ByteArray(8) { (it + 3).toByte() }
        val home = startHome(server) { duplex ->
            val streamId = readRequest(duplex)
            sendFrame(duplex, 0, FLAG_PING, nonce)
            val pong = assertNotNull(pollFrame(duplex.input))
            assertEquals(0, pong.streamId)
            assertEquals(FLAG_PONG, pong.flags)
            assertContentEquals(nonce, pong.payload)
            sendFrame(duplex, 0, FLAG_PONG, nonce)
            assertNull(pollFrame(duplex.input, iterations = 20))
            sendFrame(duplex, streamId, FLAG_DATA or FLAG_CLOSE, responseBytes(32))
            pollFrame(duplex.input, iterations = 20)
        }
        client.use { assertEquals(200, MuxSession(it).request("GET", "/d5", emptyMap(), null).status) }
        finishHome(home)
    }

    @Test
    fun emptyDataDoesNotEmitWindowGrant() {
        val (client, server) = pairedDuplexes()
        val home = startHome(server) { duplex ->
            val streamId = readRequest(duplex)
            sendFrame(duplex, streamId, FLAG_DATA, ByteArray(0))
            assertNull(pollFrame(duplex.input, iterations = 20))
            sendFrame(duplex, streamId, FLAG_DATA or FLAG_CLOSE, responseBytes(32))
            pollFrame(duplex.input, iterations = 20)
        }
        client.use { MuxSession(it).request("GET", "/empty", emptyMap(), null) }
        finishHome(home)
    }

    private fun assertMalformedControlPoisons(flags: Int, payload: ByteArray) {
        val (client, server) = pairedDuplexes()
        val home = startHome(server) { duplex ->
            readRequest(duplex)
            sendFrame(duplex, 0, flags, payload)
            assertNull(pollFrame(duplex.input, iterations = 20))
            duplex.output.close()
        }
        val session = MuxSession(client)
        assertEquals("PL protocol error: malformed control frame", assertFailsWith<IOException> { session.request("GET", "/d4", emptyMap(), null) }.message)
        assertEquals(TEST_SESSION_UNUSABLE, assertFailsWith<IOException> { session.request("GET", "/d4-again", emptyMap(), null) }.message)
        client.close()
        finishHome(home)
    }

    private fun assertWindow(frame: Frame, streamId: Int, credit: Int) {
        assertEquals(streamId, frame.streamId)
        assertEquals(FLAG_WINDOW, frame.flags)
        assertEquals(4, frame.payload.size)
        assertEquals(credit, decodeWindowCredit(frame.payload))
    }

    private fun assertReset(frame: Frame, streamId: Int, reason: Int) {
        assertEquals(streamId, frame.streamId)
        assertEquals(FLAG_RESET, frame.flags)
        assertContentEquals(byteArrayOf(reason.toByte()), frame.payload)
    }

    private fun readRequest(duplex: TestDuplex): Int {
        val frames = readRequestFrames(duplex)
        return frames.first().streamId
    }

    private fun readRequestFrames(duplex: TestDuplex): List<Frame> {
        val frames = mutableListOf<Frame>()
        do {
            val frame = readFrame(duplex.input)
            frames += frame
        } while ((frame.flags and FLAG_CLOSE) == 0)
        return frames
    }

    private fun sendFrame(duplex: TestDuplex, streamId: Int, flags: Int, payload: ByteArray) {
        duplex.output.write(encodeFrame(streamId, flags, payload))
        duplex.output.flush()
    }

    private fun readFrame(input: InputStream): Frame {
        val header = readExactly(input, 8)
        val length = ((header[5].toInt() and 0xff) shl 16) or
            ((header[6].toInt() and 0xff) shl 8) or
            (header[7].toInt() and 0xff)
        return decodeFrame(header + readExactly(input, length)).frame
    }

    private fun pollFrame(input: InputStream, iterations: Int = 200): Frame? {
        repeat(iterations) {
            if (input.available() >= 8) return readFrame(input)
            Thread.sleep(5)
        }
        return null
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            check(read >= 0) { "stream closed" }
            offset += read
        }
        return out
    }

    private fun decodeWindowCredit(payload: ByteArray): Int =
        ((payload[0].toInt() and 0xff) shl 24) or
            ((payload[1].toInt() and 0xff) shl 16) or
            ((payload[2].toInt() and 0xff) shl 8) or
            (payload[3].toInt() and 0xff)

    private fun responseHeader(): ByteArray = "HTTP/1.1 200 OK\r\n\r\n".toByteArray(Charsets.US_ASCII)

    private fun responseBytes(totalSize: Int): ByteArray {
        val header = responseHeader()
        require(totalSize >= header.size)
        return header + ByteArray(totalSize - header.size) { 'x'.code.toByte() }
    }

    private fun startHome(server: TestDuplex, block: (TestDuplex) -> Unit): HomeThread {
        val failure = AtomicReference<Throwable?>()
        val worker = thread(start = true) {
            try {
                server.use(block)
            } catch (t: Throwable) {
                failure.set(t)
                runCatching { server.close() }
            }
        }
        return HomeThread(worker, failure)
    }

    private fun finishHome(home: HomeThread) {
        home.thread.join(5000)
        assertTrue(!home.thread.isAlive, "fake home did not finish")
        home.failure.get()?.let { throw it }
    }

    private fun pairedDuplexes(bufferSize: Int = 8 * 1024): Pair<TestDuplex, TestDuplex> {
        val clientInput = PipedInputStream(bufferSize)
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream(bufferSize)
        val clientOutput = PipedOutputStream(serverInput)
        return TestDuplex(clientInput, clientOutput) to TestDuplex(serverInput, serverOutput)
    }

    private data class HomeThread(val thread: Thread, val failure: AtomicReference<Throwable?>)

    private class TrackingDuplex(private val delegate: ByteDuplex) : ByteDuplex {
        var readCount = 0
        var writeCount = 0

        override val input = object : InputStream() {
            override fun read(): Int {
                readCount += 1
                return delegate.input.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readCount += 1
                return delegate.input.read(b, off, len)
            }
        }
        override val output = object : OutputStream() {
            override fun write(b: Int) {
                writeCount += 1
                delegate.output.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                writeCount += 1
                delegate.output.write(b, off, len)
            }

            override fun flush() = delegate.output.flush()
        }

        override fun close() = delegate.close()
    }

    private class TestDuplex(
        override val input: InputStream,
        override val output: OutputStream,
    ) : ByteDuplex {
        override fun close() {
            input.close()
            output.close()
        }
    }
}
