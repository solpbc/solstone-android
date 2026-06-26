// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException

class MuxSession(private val duplex: ByteDuplex) : Closeable {
    private val input = duplex.input
    private val output = duplex.output
    private val dialer = FrameDialer()

    fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val streamId = dialer.allocate()
        writeFrame(streamId, FLAG_OPEN or FLAG_DATA, httpRequestBytes(method, path, headers, body))
        writeFrame(streamId, FLAG_CLOSE, ByteArray(0))
        val response = ByteArrayOutputStream()
        while (true) {
            val frame = readFrame()
            if (frame.streamId == 0) {
                controlPong(frame)?.let { writeFrame(it.streamId, it.flags, it.payload) }
                continue
            }
            if (frame.streamId != streamId) {
                if ((frame.flags and FLAG_OPEN) != 0) {
                    writeFrame(frame.streamId, FLAG_RESET, byteArrayOf(0x01))
                }
                continue
            }
            if ((frame.flags and FLAG_DATA) != 0) {
                if (response.size() + frame.payload.size > MAX_RESPONSE_BYTES) {
                    writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                    throw IOException("PL response too large")
                }
                response.write(frame.payload)
            }
            if ((frame.flags and FLAG_RESET) != 0) {
                throw IOException("PL stream reset: " + resetReason(frame.payload))
            }
            if ((frame.flags and FLAG_WINDOW) != 0) {
                continue
            }
            if ((frame.flags and FLAG_CLOSE) != 0) {
                return parseHttpResponse(response.toByteArray())
            }
        }
    }

    private fun writeFrame(streamId: Int, flags: Int, payload: ByteArray) {
        output.write(encodeFrame(streamId, flags, payload))
        output.flush()
    }

    private fun readFrame(): Frame {
        try {
            val header = readExactly(8)
            val length = ((header[5].toInt() and 0xff) shl 16) or
                ((header[6].toInt() and 0xff) shl 8) or
                (header[7].toInt() and 0xff)
            val payload = readExactly(length)
            return decodeFrame(header + payload, 0).frame
        } catch (e: SocketTimeoutException) {
            throw IOException("timed out waiting for PL frame", e)
        }
    }

    private fun readExactly(length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) {
                throw IOException("socket closed while reading PL frame")
            }
            offset += read
        }
        return out
    }

    override fun close() {
        duplex.close()
    }

    private fun resetReason(payload: ByteArray): String =
        if (payload.isEmpty()) "" else (payload[0].toInt() and 0xff).toString()
}

const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
const val SOCKET_TIMEOUT_MS = 30000
