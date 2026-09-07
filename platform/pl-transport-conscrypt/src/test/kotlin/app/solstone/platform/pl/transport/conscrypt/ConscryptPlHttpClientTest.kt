// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.ByteDuplex
import app.solstone.core.pl.FLAG_CLOSE
import app.solstone.core.pl.FLAG_DATA
import app.solstone.core.pl.MuxSession
import app.solstone.core.pl.encodeFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConscryptPlHttpClientTest {

    private class FakeDuplex(
        responseBytes: ByteArray,
    ) : ByteDuplex {
        override val input: InputStream = ByteArrayInputStream(
            encodeFrame(1, FLAG_DATA or FLAG_CLOSE, responseBytes),
        )
        override val output: OutputStream = ByteArrayOutputStream()
        val closed = AtomicBoolean(false)

        override fun close() {
            closed.set(true)
        }
    }

    @Test
    fun returnsRedirectStatusWithoutFollowing() {
        val rawResponse = ("HTTP/1.1 302 Found\r\n" +
            "Location: /redirected\r\n" +
            "Content-Length: 0\r\n\r\n").toByteArray(Charsets.US_ASCII)

        val duplex = FakeDuplex(rawResponse)
        val session = MuxSession(duplex)
        val client = ConscryptPlHttpClient(session)

        val response = client.request("GET", "/initial", emptyMap(), null)

        assertEquals(302, response.status)
        assertEquals("/redirected", response.headers["location"])
        assertEquals(0, response.body.size)
    }

    @Test
    fun closeClosesUnderlyingSessionAndDuplex() {
        val rawResponse = ("HTTP/1.1 200 OK\r\n" +
            "Content-Length: 2\r\n\r\nOK").toByteArray(Charsets.US_ASCII)

        val duplex = FakeDuplex(rawResponse)
        val session = MuxSession(duplex)
        val client = ConscryptPlHttpClient(session)

        val response = client.request("GET", "/path", emptyMap(), null)
        assertEquals(200, response.status)
        assertEquals("OK", response.bodyText())

        client.close()
        assertTrue(duplex.closed.get())
    }
}
