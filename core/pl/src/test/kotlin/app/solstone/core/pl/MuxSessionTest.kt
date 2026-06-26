// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MuxSessionTest {
    @Test
    fun requestResponseRoundTripsOverByteDuplex() {
        val (client, server) = pairedDuplexes()
        val serverThread = thread(start = true) {
            server.use { duplex ->
                val request = readFrame(duplex.input)
                val requestText = request.payload.toString(Charsets.US_ASCII)
                assertEquals(1, request.streamId)
                assertTrue((request.flags and FLAG_OPEN) != 0)
                assertTrue((request.flags and FLAG_DATA) != 0)
                assertTrue(requestText.contains("GET /x HTTP/1.1"))

                val close = readFrame(duplex.input)
                assertEquals(1, close.streamId)
                assertTrue((close.flags and FLAG_CLOSE) != 0)

                val body = "ok"
                val response = (
                    "HTTP/1.1 200 OK\r\n" +
                        "content-length: ${body.length}\r\n" +
                        "\r\n" +
                        body
                    ).toByteArray(Charsets.US_ASCII)
                duplex.output.write(encodeFrame(request.streamId, FLAG_DATA or FLAG_CLOSE, response))
                duplex.output.flush()
            }
        }

        client.use { duplex ->
            val response = MuxSession(duplex).request("GET", "/x", emptyMap(), null)
            assertEquals(200, response.status)
            assertEquals("ok", response.bodyText())
        }
        serverThread.join(5000)
        assertTrue(!serverThread.isAlive)
    }

    private fun readFrame(input: InputStream): Frame {
        val header = readExactly(input, 8)
        val length = ((header[5].toInt() and 0xff) shl 16) or
            ((header[6].toInt() and 0xff) shl 8) or
            (header[7].toInt() and 0xff)
        val payload = readExactly(input, length)
        return decodeFrame(header + payload).frame
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

    private fun pairedDuplexes(): Pair<TestDuplex, TestDuplex> {
        val clientInput = PipedInputStream()
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream()
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
