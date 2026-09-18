// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrowserHttpFramingHostTest {

    @Test
    fun validatesStrictFramingWithoutTruncation() {
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html\r\n" +
            "Content-Length: 13\r\n" +
            "\r\n" +
            "Hello, World!").toByteArray(Charsets.UTF_8)

        val parsed = parseBrowserHttpResponse(raw)
        assertEquals(200, parsed.status)
        assertEquals("Hello, World!", parsed.bodyText())
        assertEquals("text/html", parsed.header("Content-Type"))
    }

    @Test
    fun rejectsExtraTrailingBytesAfterContentLength() {
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "Content-Length: 5\r\n" +
            "\r\n" +
            "HelloEXTRA").toByteArray(Charsets.UTF_8)

        assertFailsWith<IOException> {
            parseBrowserHttpResponse(raw)
        }
    }

    @Test
    fun rejectsTruncatedBodyBeforeContentLength() {
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n" +
            "Hello").toByteArray(Charsets.UTF_8)

        assertFailsWith<IOException> {
            parseBrowserHttpResponse(raw)
        }
    }

    @Test
    fun parsesChunkedTransferEncodingStrictly() {
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "5\r\nHello\r\n" +
            "7\r\n, World\r\n" +
            "1\r\n!\r\n" +
            "0\r\n\r\n").toByteArray(Charsets.UTF_8)

        val parsed = parseBrowserHttpResponse(raw)
        assertEquals(200, parsed.status)
        assertEquals("Hello, World!", parsed.bodyText())
    }

    @Test
    fun rejectsExtraTrailingBytesAfterChunkedTerminator() {
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "5\r\nHello\r\n" +
            "0\r\n\r\n" +
            "EXTRA").toByteArray(Charsets.UTF_8)

        assertFailsWith<IOException> {
            parseBrowserHttpResponse(raw)
        }
    }

    @Test
    fun rejectsMissingBothContentLengthAndChunkedTransferEncodingFor200() {
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "Hello").toByteArray(Charsets.UTF_8)

        assertFailsWith<IOException> {
            parseBrowserHttpResponse(raw)
        }
    }

    @Test
    fun allowsEmptyBodyFor204And304WithoutContentLength() {
        val raw204 = "HTTP/1.1 204 No Content\r\n\r\n".toByteArray(Charsets.UTF_8)
        val parsed204 = parseBrowserHttpResponse(raw204)
        assertEquals(204, parsed204.status)
        assertEquals(0, parsed204.body.size)

        val raw304 = "HTTP/1.1 304 Not Modified\r\nETag: \"123\"\r\n\r\n".toByteArray(Charsets.UTF_8)
        val parsed304 = parseBrowserHttpResponse(raw304)
        assertEquals(304, parsed304.status)
        assertEquals(0, parsed304.body.size)
    }

    @Test
    fun preservesRepeatedHeadersInOrder() {
        val raw = ("HTTP/1.1 200 OK\r\n" +
            "Content-Length: 0\r\n" +
            "Set-Cookie: a=1\r\n" +
            "Set-Cookie: b=2\r\n" +
            "Set-Cookie: c=3\r\n" +
            "\r\n").toByteArray(Charsets.UTF_8)

        val parsed = parseBrowserHttpResponse(raw)
        val cookies = parsed.headers("Set-Cookie")
        assertEquals(listOf("a=1", "b=2", "c=3"), cookies)
    }
}
