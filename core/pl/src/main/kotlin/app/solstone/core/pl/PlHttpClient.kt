// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

interface StreamingBody : Closeable {
    val contentLength: Long
    fun readChunk(target: ByteArray, offset: Int, length: Int): Int
    override fun close()
}

interface PlHttpClient {
    fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    ): HttpResponse

    fun requestStreaming(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: StreamingBody,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    ): HttpResponse {
        try {
            if (body.contentLength > Int.MAX_VALUE || body.contentLength < 0) {
                throw IOException("Streaming body content length ${body.contentLength} is out of bounds for buffering client")
            }
            val expectedLength = body.contentLength
            val out = ByteArrayOutputStream(expectedLength.toInt())
            val buf = ByteArray(64 * 1024)
            var totalRead = 0L
            while (true) {
                val read = body.readChunk(buf, 0, buf.size)
                if (read < 0) break
                if (read > 0) {
                    totalRead += read
                    if (totalRead > expectedLength) {
                        throw IOException("Streaming body length exceeded declared contentLength: $expectedLength")
                    }
                    out.write(buf, 0, read)
                }
            }
            if (totalRead != expectedLength) {
                throw IOException("Streaming body length ($totalRead) does not match declared contentLength: $expectedLength")
            }
            val bodyBytes = out.toByteArray()
            return request(method, path, headers, bodyBytes, maxResponseBytes)
        } finally {
            body.close()
        }
    }
}

