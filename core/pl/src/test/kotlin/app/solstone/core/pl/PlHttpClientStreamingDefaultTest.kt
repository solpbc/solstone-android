// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlHttpClientStreamingDefaultTest {

    private class RecordingPlHttpClient : PlHttpClient {
        val requestCalls = AtomicInteger(0)
        var lastMethod: String? = null
        var lastPath: String? = null
        var lastHeaders: Map<String, String>? = null
        var lastBody: ByteArray? = null
        var lastMaxResponseBytes: Int = 0
        var throwOnRequest: Throwable? = null

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            requestCalls.incrementAndGet()
            lastMethod = method
            lastPath = path
            lastHeaders = headers
            lastBody = body
            lastMaxResponseBytes = maxResponseBytes
            throwOnRequest?.let { throw it }
            return HttpResponse(200, emptyMap(), ByteArray(0))
        }
    }

    private class TestStreamingBody(
        override val contentLength: Long,
        private val data: ByteArray,
        private val throwOnRead: Throwable? = null,
    ) : StreamingBody {
        var closeCount = 0
        var readChunkCalls = 0
        private var offset = 0

        override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
            readChunkCalls++
            throwOnRead?.let { throw it }
            if (this.offset >= data.size) return -1
            val toRead = minOf(length, data.size - this.offset)
            System.arraycopy(data, this.offset, target, offset, toRead)
            this.offset += toRead
            return toRead
        }

        override fun close() {
            closeCount++
        }
    }

    @Test
    fun defaultStreamingBufferSuccessCallsRequestOnceAndClosesOnce() {
        val client = RecordingPlHttpClient()
        val data = "streaming body payload".toByteArray(Charsets.US_ASCII)
        val body = TestStreamingBody(contentLength = data.size.toLong(), data = data)

        val resp = client.requestStreaming(
            method = "POST",
            path = "/upload",
            headers = mapOf("content-type" to "text/plain"),
            body = body,
        )

        assertEquals(200, resp.status)
        assertEquals(1, client.requestCalls.get())
        assertEquals("POST", client.lastMethod)
        assertEquals("/upload", client.lastPath)
        assertEquals("streaming body payload", client.lastBody?.toString(Charsets.US_ASCII))
        assertEquals(1, body.closeCount)
    }

    @Test
    fun contentLengthOverIntMaxValueThrowsBeforeReadChunkAndClosesOnce() {
        val client = RecordingPlHttpClient()
        val body = TestStreamingBody(contentLength = Int.MAX_VALUE.toLong() + 1L, data = ByteArray(0))

        assertFailsWith<IOException> {
            client.requestStreaming("POST", "/overflow", emptyMap(), body)
        }

        assertEquals(0, body.readChunkCalls)
        assertEquals(1, body.closeCount)
        assertEquals(0, client.requestCalls.get())
    }

    @Test
    fun countMismatchShortThrowsWithoutCallingRequestAndClosesOnce() {
        val client = RecordingPlHttpClient()
        val body = TestStreamingBody(contentLength = 10L, data = ByteArray(5))

        assertFailsWith<IOException> {
            client.requestStreaming("POST", "/short", emptyMap(), body)
        }

        assertEquals(1, body.closeCount)
        assertEquals(0, client.requestCalls.get())
    }

    @Test
    fun countMismatchLongThrowsWithoutCallingRequestAndClosesOnce() {
        val client = RecordingPlHttpClient()
        val body = TestStreamingBody(contentLength = 5L, data = ByteArray(10))

        assertFailsWith<IOException> {
            client.requestStreaming("POST", "/long", emptyMap(), body)
        }

        assertEquals(1, body.closeCount)
        assertEquals(0, client.requestCalls.get())
    }

    @Test
    fun requestThrowsStillClosesOnce() {
        val client = RecordingPlHttpClient().apply {
            throwOnRequest = IOException("simulated network failure")
        }
        val data = "test data".toByteArray(Charsets.US_ASCII)
        val body = TestStreamingBody(contentLength = data.size.toLong(), data = data)

        assertFailsWith<IOException> {
            client.requestStreaming("POST", "/throw", emptyMap(), body)
        }

        assertEquals(1, body.closeCount)
        assertEquals(1, client.requestCalls.get())
    }
}
