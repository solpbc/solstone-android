// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserverHealthTest {
    @Test
    fun reportPostsHealthJsonWithProtocolHeaders() {
        val http = RecordingPlHttpClient(HttpResponse(200, emptyMap(), ByteArray(0)))

        ObserverHealthClient(http).report(
            ObserverHealth(
                name = "observer-handle",
                streamType = "phone",
                version = "0.1",
                uptime = 123,
                lastSuccessfulSync = 456,
                pendingQueueDepth = 7,
                recentErrorCount = 2,
                lastErrorReason = null,
            ),
        )

        assertEquals("POST", http.lastRequest.method)
        assertEquals(HEALTH_PATH, http.lastRequest.path)
        assertEquals("application/json", http.lastRequest.headers["Content-Type"])
        assertEquals("observer-handle", http.lastRequest.headers[OBSERVER_HANDLE_HEADER])
        assertEquals("2", http.lastRequest.headers[PROTOCOL_VERSION_HEADER])
        val body = parseJson(http.lastRequest.body!!.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals(
            setOf(
                "name",
                "stream_type",
                "version",
                "uptime",
                "last_successful_sync",
                "pending_queue_depth",
                "recent_error_count",
                "last_error_reason",
            ),
            body.keys,
        )
        assertEquals("observer-handle", body["name"])
        assertEquals("phone", body["stream_type"])
        assertEquals("0.1", body["version"])
        assertEquals(123.0, body["uptime"])
        assertEquals(456.0, body["last_successful_sync"])
        assertEquals(7.0, body["pending_queue_depth"])
        assertEquals(2.0, body["recent_error_count"])
        assertEquals(null, body["last_error_reason"])
    }

    @Test
    fun reportIgnoresNon200Response() {
        val http = RecordingPlHttpClient(HttpResponse(500, emptyMap(), "nope".toByteArray()))

        ObserverHealthClient(http).report(
            ObserverHealth(
                name = "observer-handle",
                streamType = "watch",
                version = "0.1",
                uptime = 0,
                lastSuccessfulSync = null,
                pendingQueueDepth = 0,
                recentErrorCount = 1,
                lastErrorReason = "retry",
            ),
        )

        assertEquals(1, http.requests.size)
    }
}
