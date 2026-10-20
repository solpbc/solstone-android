// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.StreamingBody
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloseablePlHttpClientAdapterTest {

    private class OverrideStreamingClient : PlHttpClient {
        val requestCalls = AtomicInteger(0)
        val requestStreamingCalls = AtomicInteger(0)

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            requestCalls.incrementAndGet()
            return HttpResponse(200, emptyMap(), ByteArray(0))
        }

        override fun requestStreaming(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: StreamingBody,
            maxResponseBytes: Int,
        ): HttpResponse {
            requestStreamingCalls.incrementAndGet()
            body.close()
            return HttpResponse(200, emptyMap(), ByteArray(0))
        }
    }

    @Test
    fun adapterForwardsRequestStreamingToDelegateOverrideWithoutCallingRequest() {
        val delegate = OverrideStreamingClient()
        val adapter = CloseablePlHttpClientAdapter(delegate)

        val body = object : StreamingBody {
            var closed = false
            override val contentLength: Long = 0L
            override fun readChunk(target: ByteArray, offset: Int, length: Int): Int = -1
            override fun close() {
                closed = true
            }
        }

        val resp = adapter.requestStreaming("POST", "/stream", emptyMap(), body)
        assertEquals(200, resp.status)
        assertEquals(1, delegate.requestStreamingCalls.get())
        assertEquals(0, delegate.requestCalls.get())
        assertTrue(body.closed)
    }
}
