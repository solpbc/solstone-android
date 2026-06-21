// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlHttpTest {
    @Test
    fun requestBytesMatchReferenceFraming() {
        val request = httpRequestBytes(
            "POST",
            "/app/network/pair?token=abc",
            mapOf("content-type" to "application/json"),
            """{"x":1}""".toByteArray(),
        )
        assertEquals(
            "POST /app/network/pair?token=abc HTTP/1.1\r\n" +
                "host: spl.local\r\n" +
                "accept: application/json\r\n" +
                "content-type: application/json\r\n" +
                "content-length: 7\r\n" +
                "\r\n" +
                """{"x":1}""",
            request.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun requestBytesPreserveCallerHeadersAndOrder() {
        val request = httpRequestBytes(
            "POST",
            "/app/observer/ingest",
            linkedMapOf(
                "Content-Type" to "multipart/mixed",
                "X-Solstone-Observer" to "abc123handle",
                "X-Solstone-Protocol-Version" to "2",
            ),
            "payload".toByteArray(),
        )
        assertEquals(
            "POST /app/observer/ingest HTTP/1.1\r\n" +
                "host: spl.local\r\n" +
                "accept: application/json\r\n" +
                "Content-Type: multipart/mixed\r\n" +
                "X-Solstone-Observer: abc123handle\r\n" +
                "X-Solstone-Protocol-Version: 2\r\n" +
                "content-length: 7\r\n" +
                "\r\n" +
                "payload",
            request.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun requestBytesKeepFramingOwnedHeaders() {
        val request = httpRequestBytes(
            "POST",
            "/app/observer/ingest",
            linkedMapOf(
                "host" to "evil.example",
                "Host" to "evil2.example",
                "content-length" to "999",
                "Content-Length" to "888",
                "X-Real" to "ok",
            ),
            "payload".toByteArray(),
        ).toString(Charsets.US_ASCII)

        assertTrue(request.contains("host: spl.local"))
        assertTrue(request.contains("content-length: 7"))
        assertFalse(request.contains("evil.example"))
        assertFalse(request.contains("evil2.example"))
        assertFalse(request.contains("999"))
        assertFalse(request.contains("888"))
        assertEquals(1, headerLineCount(request, "host"))
        assertEquals(1, headerLineCount(request, "content-length"))
        assertTrue(request.contains("X-Real: ok"))
    }

    @Test
    fun requestBytesAllowCallerAcceptOverride() {
        val request = httpRequestBytes("GET", "/x", mapOf("Accept" to "text/plain"), ByteArray(0))
            .toString(Charsets.US_ASCII)

        assertTrue(request.contains("Accept: text/plain"))
        assertFalse(request.contains("accept: application/json"))
        assertEquals(1, headerLineCount(request, "accept"))
    }

    @Test
    fun requestBytesWithEmptyHeaders() {
        val request = httpRequestBytes("GET", "/app/network/api/status", emptyMap(), ByteArray(0))
            .toString(Charsets.US_ASCII)

        assertEquals(
            "GET /app/network/api/status HTTP/1.1\r\n" +
                "host: spl.local\r\n" +
                "accept: application/json\r\n" +
                "content-length: 0\r\n" +
                "\r\n",
            request,
        )
        assertFalse(request.contains("content-type"))
    }

    @Test
    fun parsesContentLengthResponse() {
        val response = parseHttpResponse("HTTP/1.1 201 Created\r\nContent-Length: 5\r\nX-Test: yes\r\n\r\nhello ignored".toByteArray())
        assertEquals(201, response.status)
        assertEquals("5", response.headers["content-length"])
        assertEquals("yes", response.headers["x-test"])
        assertContentEquals("hello".toByteArray(), response.body)
    }

    @Test
    fun parsesChunkedResponse() {
        val response = parseHttpResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n".toByteArray())
        assertEquals(200, response.status)
        assertEquals("hello world", response.bodyText())
    }

    private fun headerLineCount(request: String, headerName: String): Int =
        request.split("\r\n").count { line ->
            val colon = line.indexOf(':')
            colon > 0 && line.substring(0, colon).equals(headerName, ignoreCase = true)
        }
}
