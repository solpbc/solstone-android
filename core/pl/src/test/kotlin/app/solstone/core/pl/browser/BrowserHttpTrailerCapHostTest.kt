// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl.browser

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowserHttpTrailerCapHostTest {

    private class LocalSink : BrowserResponseSink {
        var completed: Boolean = false
        var error: Throwable? = null

        override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {}
        override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
        override fun onTrailers(trailers: List<Pair<String, String>>) {}
        override fun onComplete() {
            completed = true
        }
        override fun onError(cause: Throwable) {
            error = cause
        }
    }

    @Test
    fun enforcesSixtyFourKilobyteCapOnChunkedTrailers() {
        val head = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n".toByteArray(Charsets.US_ASCII)

        // 1. Trailer block of exactly 64 KiB ends the trailer section and completes
        val sink1 = LocalSink()
        val parser1 = ProgressiveBrowserResponseParser(sink1)
        parser1.feed(head, 0, head.size)

        val exactValue = "a".repeat(64 * 1024 - 14) // "X-Custom: " (10) + value + "\r\n\r\n" (4) = 64 KiB
        val exactTrailerBlock = "X-Custom: $exactValue\r\n\r\n".toByteArray(Charsets.US_ASCII)
        assertTrue(exactTrailerBlock.size == 64 * 1024, "exactTrailerBlock must be 65536 bytes")

        parser1.feed(exactTrailerBlock, 0, exactTrailerBlock.size)
        parser1.onMuxClose(successful = true)

        assertTrue(sink1.completed, "parser1 should complete successfully")
        assertTrue(sink1.error == null, "parser1 should have no error")

        // 2. Trailer block of 64 KiB + 1 byte throws IOException and onError runs
        val sink2 = LocalSink()
        val parser2 = ProgressiveBrowserResponseParser(sink2)
        parser2.feed(head, 0, head.size)

        val overValue = "a".repeat(64 * 1024 - 13) // total 65537 bytes
        val overTrailerBlock = "X-Custom: $overValue\r\n\r\n".toByteArray(Charsets.US_ASCII)
        assertTrue(overTrailerBlock.size == 64 * 1024 + 1, "overTrailerBlock must be 65537 bytes")

        var threwIoException = false
        try {
            parser2.feed(overTrailerBlock, 0, overTrailerBlock.size)
        } catch (e: IOException) {
            threwIoException = true
        }

        assertTrue(threwIoException, "feed must throw IOException when trailer block exceeds 64 KiB")
        assertNotNull(sink2.error, "sink2.error must be set by onError")
        assertTrue(sink2.error is IOException)
        assertFalse(sink2.completed, "sink2 must not be completed")
    }
}
