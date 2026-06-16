// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PlHttpTest {
    @Test
    fun requestBytesMatchReferenceFraming() {
        val request = httpRequestBytes("POST", "/app/link/pair?token=abc", "application/json", """{"x":1}""".toByteArray())
        assertEquals(
            "POST /app/link/pair?token=abc HTTP/1.1\r\n" +
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
}
