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
    private var poisoned = false

    fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        if (poisoned) {
            throw IOException(SESSION_UNUSABLE)
        }
        val streamId = dialer.allocate()
        val request = httpRequestBytes(method, path, headers, body)
        var offset = 0
        while (offset < request.size) {
            val count = minOf(MAX_DATA_CHUNK_BYTES, request.size - offset)
            val flags = if (offset == 0) FLAG_OPEN or FLAG_DATA else FLAG_DATA
            writeFrame(streamId, flags, request.copyOfRange(offset, offset + count))
            offset += count
        }
        writeFrame(streamId, FLAG_CLOSE, ByteArray(0))
        val response = ByteArrayOutputStream()
        // Per-stream flow-control credit is replenished as each DATA frame is consumed.
        var receiveWindow = INITIAL_RECEIVE_WINDOW
        while (true) {
            val frame = readFrame()
            if ((frame.flags and FLAG_RESERVED) != 0) {
                markPoisoned()
                throw IOException("PL protocol error: reserved flag")
            }
            if (frame.streamId == 0) {
                val pong = controlPong(frame)
                if (pong != null) {
                    writeFrame(pong.streamId, pong.flags, pong.payload)
                    continue
                }
                if (frame.flags == FLAG_PONG) {
                    continue
                }
                markPoisoned()
                throw IOException("PL protocol error: malformed control frame")
            }
            if (frame.streamId != streamId) {
                if ((frame.flags and (FLAG_OPEN or FLAG_DATA or FLAG_WINDOW)) != 0) {
                    writeFrame(frame.streamId, FLAG_RESET, byteArrayOf(0x01))
                }
                continue
            }
            if (frame.flags !in VALID_RECEIVE_FLAGS) {
                writeFrame(streamId, FLAG_RESET, byteArrayOf(0x01))
                throw IOException("PL protocol error: invalid flags")
            }
            if ((frame.flags and FLAG_DATA) != 0) {
                val size = frame.payload.size
                if (size > 0) {
                    if (size > receiveWindow) {
                        writeFrame(streamId, FLAG_RESET, byteArrayOf(0x02))
                        throw IOException("PL receive window exceeded")
                    }
                    // This hard total-response ceiling is distinct from replenished flow credit.
                    if (response.size() + size > MAX_RESPONSE_BYTES) {
                        writeFrame(streamId, FLAG_RESET, byteArrayOf(0x05))
                        throw IOException("PL response too large")
                    }
                    receiveWindow -= size
                    response.write(frame.payload)
                    writeFrame(streamId, FLAG_WINDOW, encodeWindowCredit(size))
                    receiveWindow += size
                }
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
            markPoisoned()
            throw IOException("timed out waiting for PL frame", e)
        } catch (e: IOException) {
            markPoisoned()
            throw e
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

    private fun markPoisoned() {
        poisoned = true
    }

    private fun resetReason(payload: ByteArray): String =
        if (payload.isEmpty()) "" else (payload[0].toInt() and 0xff).toString()
}

const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
const val INITIAL_RECEIVE_WINDOW = 1024 * 1024
const val MAX_DATA_CHUNK_BYTES = 64 * 1024
const val SESSION_UNUSABLE = "PL session unusable"
const val SOCKET_TIMEOUT_MS = 30000
