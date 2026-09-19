// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import app.solstone.core.identity.PairingGeneration
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class BrowserHttpStreamingMatrixHostTest {

    private val pairingGen = PairingGeneration("inst1", "sha256:client")

    private class RecordingSink : BrowserResponseSink {
        var status: Int = 0
        var reason: String = ""
        val headers = mutableListOf<Pair<String, String>>()
        val interimHeaders = mutableListOf<Pair<String, String>>()
        val body = ByteArrayOutputStream()
        val trailers = mutableListOf<Pair<String, String>>()
        var completed = false
        var error: Throwable? = null

        override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {
            if (status in 100..199) {
                this.interimHeaders.addAll(headers)
            } else {
                this.status = status
                this.reason = reason
                this.headers.addAll(headers)
            }
        }

        override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {
            body.write(chunk, offset, length)
        }

        override fun onTrailers(trailers: List<Pair<String, String>>) {
            this.trailers.addAll(trailers)
        }

        override fun onComplete() {
            completed = true
        }

        override fun onError(cause: Throwable) {
            error = cause
        }
    }

    @Test
    fun cl0ZeroChunkNoBody() {
        val sink = RecordingSink()
        val parser = ProgressiveBrowserResponseParser(sink)
        val resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        parser.feed(resp, 0, resp.size)
        parser.onMuxClose(successful = true)
        assertTrue(sink.completed)
        assertEquals(200, sink.status)
        assertEquals(0, sink.body.size())

        // Zero chunk
        val sink2 = RecordingSink()
        val parser2 = ProgressiveBrowserResponseParser(sink2)
        val resp2 = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        parser2.feed(resp2, 0, resp2.size)
        parser2.onMuxClose(successful = true)
        assertTrue(sink2.completed)
        assertEquals(200, sink2.status)
        assertEquals(0, sink2.body.size())
    }

    @Test
    fun expect100HandshakeAfterFourGates() {
        var upstreamCalled = false
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse = error("Not used")
                    override fun requestStreaming(method: String, path: String, headers: List<Pair<String, String>>, bodySource: BrowserRequestBodySource?, responseSink: BrowserResponseSink) {
                        upstreamCalled = true
                        responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Length" to "4"))
                        responseSink.onBodyChunk("done".toByteArray(Charsets.US_ASCII), 0, 4)
                        responseSink.onComplete()
                    }
                    override fun close() {}
                }
            },
            diag = {},
        )
        val origin = session.start()
        val uri = URI(origin.url)
        val socket = Socket("127.0.0.1", uri.port)
        val out = socket.getOutputStream()
        val `in` = socket.getInputStream()

        out.write("POST /post HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nExpect: 100-continue\r\nContent-Length: 4\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()

        // Read 100 Continue
        val buffer = ByteArray(1024)
        val read = `in`.read(buffer)
        val respText = String(buffer, 0, read, Charsets.US_ASCII)
        assertTrue(respText.startsWith("HTTP/1.1 100 Continue\r\n\r\n"))

        // Now send body
        out.write("test".toByteArray(Charsets.US_ASCII))
        out.flush()

        val resp2Out = ByteArrayOutputStream()
        while (!resp2Out.toString(Charsets.US_ASCII.name()).contains("done")) {
            val r = `in`.read(buffer)
            if (r < 0) break
            resp2Out.write(buffer, 0, r)
        }
        val resp2Text = resp2Out.toString(Charsets.US_ASCII.name())
        assertTrue(resp2Text.contains("200 OK"))
        assertTrue(resp2Text.contains("done"))

        socket.close()
        session.stop()
    }

    @Test
    fun duplicateCommaOrUnsupportedExpectReturns417() {
        val session = JournalBrowserSession(
            pairing = { pairingGen },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse = error("Not used")
                    override fun requestStreaming(method: String, path: String, headers: List<Pair<String, String>>, bodySource: BrowserRequestBodySource?, responseSink: BrowserResponseSink) {
                        fail("Upstream should not be called")
                    }
                    override fun close() {}
                }
            },
            diag = {},
        )
        val origin = session.start()
        val uri = URI(origin.url)

        // Duplicate Expect headers
        var socket = Socket("127.0.0.1", uri.port)
        var out = socket.getOutputStream()
        var `in` = socket.getInputStream()
        out.write("POST / HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nExpect: 100-continue\r\nExpect: 100-continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        var resp = String(`in`.readBytes(), Charsets.US_ASCII)
        assertTrue(resp.contains("417 Expectation Failed"))
        socket.close()

        // Comma separated Expect
        socket = Socket("127.0.0.1", uri.port)
        out = socket.getOutputStream()
        `in` = socket.getInputStream()
        out.write("POST / HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nExpect: 100-continue, other\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        resp = String(`in`.readBytes(), Charsets.US_ASCII)
        assertTrue(resp.contains("417 Expectation Failed"))
        socket.close()

        // Unsupported Expect
        socket = Socket("127.0.0.1", uri.port)
        out = socket.getOutputStream()
        `in` = socket.getInputStream()
        out.write("POST / HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nExpect: 200-ok\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        resp = String(`in`.readBytes(), Charsets.US_ASCII)
        assertTrue(resp.contains("417 Expectation Failed"))
        socket.close()

        session.stop()
    }

    @Test
    fun tePlusClAndBadClTeHandling() {
        val sink = RecordingSink()
        val parser = ProgressiveBrowserResponseParser(sink)
        // TE overrides CL per RFC
        val resp = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 100\r\n\r\n5\r\nhello\r\n0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        parser.feed(resp, 0, resp.size)
        parser.onMuxClose(successful = true)
        assertTrue(sink.completed)
        assertEquals("hello", sink.body.toString(Charsets.US_ASCII.name()))

        // Bad CL
        val sinkBad = RecordingSink()
        val parserBad = ProgressiveBrowserResponseParser(sinkBad)
        try {
            val badResp = "HTTP/1.1 200 OK\r\nContent-Length: -5\r\n\r\n".toByteArray(Charsets.US_ASCII)
            parserBad.feed(badResp, 0, badResp.size)
            fail("Expected failure on negative CL")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun malformedChunkAndTrailerHandling() {
        val sink = RecordingSink()
        val parser = ProgressiveBrowserResponseParser(sink)
        try {
            val badChunk = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZZZ\r\n".toByteArray(Charsets.US_ASCII)
            parser.feed(badChunk, 0, badChunk.size)
            fail("Expected malformed chunk size failure")
        } catch (_: IOException) {
            // expected
        }

        // Valid chunked with Digest trailer
        val sinkValid = RecordingSink()
        val parserValid = ProgressiveBrowserResponseParser(sinkValid)
        val validChunkWithTrailer = ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: Digest\r\n\r\n" +
            "5\r\nhello\r\n0\r\nDigest: sha-256=abc\r\n\r\n").toByteArray(Charsets.US_ASCII)
        parserValid.feed(validChunkWithTrailer, 0, validChunkWithTrailer.size)
        parserValid.onMuxClose(successful = true)
        assertTrue(sinkValid.completed)
        assertEquals("hello", sinkValid.body.toString(Charsets.US_ASCII.name()))
        assertEquals(1, sinkValid.trailers.size)
        assertEquals("Digest", sinkValid.trailers[0].first)
        assertEquals("sha-256=abc", sinkValid.trailers[0].second)
    }

    @Test
    fun headRequestPreservesContentLengthZeroBody() {
        val sink = RecordingSink()
        val parser = ProgressiveBrowserResponseParser(sink, isHeadRequest = true)
        val resp = "HTTP/1.1 200 OK\r\nContent-Length: 1024\r\n\r\n".toByteArray(Charsets.US_ASCII)
        parser.feed(resp, 0, resp.size)
        parser.onMuxClose(successful = true)
        assertTrue(sink.completed)
        assertEquals(200, sink.status)
        assertEquals("1024", sink.headers.firstOrNull { it.first.equals("content-length", ignoreCase = true) }?.second)
        assertEquals(0, sink.body.size())
    }

    @Test
    fun status204RejectsIllegalFraming() {
        val sink = RecordingSink()
        val parser = ProgressiveBrowserResponseParser(sink)
        try {
            val resp = "HTTP/1.1 204 No Content\r\nContent-Length: 5\r\n\r\nhello".toByteArray(Charsets.US_ASCII)
            parser.feed(resp, 0, resp.size)
            fail("Expected 204 with CL > 0 to fail")
        } catch (_: IOException) {
            // expected
        }

        val sinkValid = RecordingSink()
        val parserValid = ProgressiveBrowserResponseParser(sinkValid)
        val respValid = "HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.US_ASCII)
        parserValid.feed(respValid, 0, respValid.size)
        parserValid.onMuxClose(successful = true)
        assertTrue(sinkValid.completed)
        assertEquals(204, sinkValid.status)
        assertEquals(0, sinkValid.body.size())
    }

    @Test
    fun status304KeepsContentLengthNoBody() {
        val sink = RecordingSink()
        val parser = ProgressiveBrowserResponseParser(sink)
        val resp = "HTTP/1.1 304 Not Modified\r\nContent-Length: 500\r\n\r\n".toByteArray(Charsets.US_ASCII)
        parser.feed(resp, 0, resp.size)
        parser.onMuxClose(successful = true)
        assertTrue(sink.completed)
        assertEquals(304, sink.status)
        assertEquals("500", sink.headers.firstOrNull { it.first.equals("content-length", ignoreCase = true) }?.second)
        assertEquals(0, sink.body.size())
    }

    @Test
    fun status103EarlyHintsThenFinalWithOrderedLinks() {
        val sink = RecordingSink()
        val parser = ProgressiveBrowserResponseParser(sink)
        val resp103 = "HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload; as=style\r\nLink: </script.js>; rel=preload; as=script\r\n\r\n".toByteArray(Charsets.US_ASCII)
        parser.feed(resp103, 0, resp103.size)
        assertEquals(2, sink.interimHeaders.size)
        assertEquals("Link", sink.interimHeaders[0].first)
        assertEquals("</style.css>; rel=preload; as=style", sink.interimHeaders[0].second)
        assertEquals("</script.js>; rel=preload; as=script", sink.interimHeaders[1].second)

        val respFinal = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray(Charsets.US_ASCII)
        parser.feed(respFinal, 0, respFinal.size)
        parser.onMuxClose(successful = true)
        assertTrue(sink.completed)
        assertEquals(200, sink.status)
        assertEquals("ok", sink.body.toString(Charsets.US_ASCII.name()))
    }

    @Test
    fun status101SwitchingProtocolsRejectedRequestLocally() {
        val sink = RecordingSink()
        val parser = ProgressiveBrowserResponseParser(sink)
        try {
            val resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray(Charsets.US_ASCII)
            parser.feed(resp, 0, resp.size)
            fail("Expected 101 to be rejected")
        } catch (_: IOException) {
            // expected
        }
    }
}
