// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MuxStreamingHostTest {

    @Test
    fun streamsRequestAndResponseProgressively() {
        val (client, server) = pairedDuplexes(bufferSize = 128 * 1024)
        val bodySent = "test stream body".encodeToByteArray()

        val serverDone = CountDownLatch(1)
        val homeThread = thread(start = true) {
            server.use { duplex ->
                val openFrame = readFrame(duplex.input)
                assertEquals(1, openFrame.streamId)
                assertTrue((openFrame.flags and FLAG_OPEN) != 0)
                assertTrue((openFrame.flags and FLAG_DATA) != 0)

                val bodyFrame = readFrame(duplex.input)
                assertEquals(1, bodyFrame.streamId)
                assertEquals(FLAG_DATA, bodyFrame.flags)
                assertEquals("test stream body", bodyFrame.payload.toString(Charsets.US_ASCII))

                val closeFrame = readFrame(duplex.input)
                assertEquals(FLAG_CLOSE, closeFrame.flags)

                // Send response in progressive chunks
                val resHeaders = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA, resHeaders)

                // Small chunks draw no WINDOW: credit comes back only at half the window.
                assertTrue(pollFrame(duplex.input, iterations = 20) == null)

                val chunk1 = "Hello, ".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA, chunk1)

                assertTrue(pollFrame(duplex.input, iterations = 20) == null)

                val chunk2 = "streaming world!".toByteArray(Charsets.US_ASCII)
                sendFrame(duplex, 1, FLAG_DATA or FLAG_CLOSE, chunk2)
                serverDone.countDown()
            }
        }

        val receivedStatus = AtomicInteger(0)
        val receivedBody = ByteArrayOutputStream()
        val completed = AtomicBoolean(false)
        val sinkLatch = CountDownLatch(1)

        val sink = object : BrowserResponseSink {
            override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
                receivedStatus.set(status)
            }

            override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {
                receivedBody.write(chunk, offset, length)
            }

            override fun onComplete() {
                completed.set(true)
                sinkLatch.countDown()
            }

            override fun onError(cause: Throwable) {
                sinkLatch.countDown()
            }
        }

        val bais = ByteArrayInputStream(bodySent)
        val bodySource = BrowserRequestBodySource { target, offset, length ->
            bais.read(target, offset, length)
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            session.requestStreaming(
                method = "POST",
                path = "/stream-endpoint",
                headers = listOf("Content-Type" to "text/plain"),
                bodySource = bodySource,
                responseSink = sink,
            )
        }

        assertTrue(sinkLatch.await(5, TimeUnit.SECONDS))
        assertTrue(serverDone.await(5, TimeUnit.SECONDS))
        homeThread.join(5000)

        assertEquals(200, receivedStatus.get())
        assertEquals("Hello, streaming world!", receivedBody.toString(Charsets.US_ASCII))
        assertTrue(completed.get())
    }

    @Test
    fun sessionTerminalOnReservedFlagBeforeAllocation() {
        val (client, server) = pairedDuplexes()
        val homeThread = thread(start = true) {
            server.use { duplex ->
                readFrame(duplex.input) // open frame
                readFrame(duplex.input) // close frame
                // Send frame with reserved flag bit 0x80
                sendFrame(duplex, 1, FLAG_RESERVED or FLAG_DATA, ByteArray(16))
            }
        }

        val sink = object : BrowserResponseSink {
            override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {}
            override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
            override fun onComplete() {}
            override fun onError(cause: Throwable) {}
        }

        client.use { duplex ->
            val session = MuxSession(duplex)
            val err = assertFailsWith<IOException> {
                session.requestStreaming("GET", "/test", emptyList(), null, sink)
            }
            assertTrue(err.message?.contains("reserved") == true)
            assertTrue(session.isPoisoned)
        }
        homeThread.join(5000)
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
        val payload = readExactly(input, length)
        return decodeFrame(header + payload, 0).frame
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) throw IOException("stream closed")
            offset += read
        }
        return out
    }

    private fun pollFrame(input: InputStream, iterations: Int = 100): Frame? {
        for (i in 0 until iterations) {
            if (input.available() >= 8) {
                return readFrame(input)
            }
            Thread.sleep(10)
        }
        return null
    }

    private fun pairedDuplexes(bufferSize: Int = 16 * 1024): Pair<TestDuplex, TestDuplex> {
        val clientInput = PipedInputStream(bufferSize)
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream(bufferSize)
        val clientOutput = PipedOutputStream(serverInput)
        return TestDuplex(clientInput, clientOutput) to TestDuplex(serverInput, serverOutput)
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
