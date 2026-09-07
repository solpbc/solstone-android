// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okhttp3.OkHttpClient

class OkHttpHttpsPosterTest {
    @Test
    fun responseExceeding64KiBThrowsStreamCapError() {
        val server = ServerSocket(0)
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            val socket = server.accept()
            val reader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }
            val out = socket.getOutputStream()
            val bigBody = ByteArray(65 * 1024) { 'a'.code.toByte() }
            val header = "HTTP/1.1 200 OK\r\nContent-Length: ${bigBody.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
            out.write(header)
            out.write(bigBody)
            out.flush()
            socket.close()
            server.close()
        }

        val poster = OkHttpHttpsPoster()
        val error = assertFailsWith<IOException> {
            poster.post("http://127.0.0.1:$port/test", ByteArray(0), emptyMap())
        }
        assertEquals("relay control response too large", error.message)
    }

    @Test
    fun redirectRefusedThrowsRedirectRefusedError() {
        val server = ServerSocket(0)
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            val socket = server.accept()
            val reader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }
            val out = socket.getOutputStream()
            val response = "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:$port/other\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
            out.write(response)
            out.flush()
            socket.close()
            server.close()
        }

        val poster = OkHttpHttpsPoster()
        val error = assertFailsWith<IOException> {
            poster.post("http://127.0.0.1:$port/test", ByteArray(0), emptyMap())
        }
        assertEquals("relay control redirect refused", error.message)
    }

    @Test
    fun crossOriginRedirectRefusedThrowsRedirectRefusedError() {
        val server = ServerSocket(0)
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            val socket = server.accept()
            val reader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }
            val out = socket.getOutputStream()
            val response = "HTTP/1.1 302 Found\r\nLocation: https://evil.example/attack\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
            out.write(response)
            out.flush()
            socket.close()
            server.close()
        }

        val poster = OkHttpHttpsPoster()
        val error = assertFailsWith<IOException> {
            poster.post("http://127.0.0.1:$port/test", ByteArray(0), emptyMap())
        }
        assertEquals("relay control redirect refused", error.message)
    }

    @Test
    fun readStallThrowsRelayControlTimeout() {
        val server = ServerSocket(0)
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            val socket = server.accept()
            val reader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }
            Thread.sleep(600)
            socket.close()
            server.close()
        }

        val client = OkHttpClient.Builder()
            .callTimeout(200, TimeUnit.MILLISECONDS)
            .build()
        val poster = OkHttpHttpsPoster(client)
        val error = assertFailsWith<IOException> {
            poster.post("http://127.0.0.1:$port/test", ByteArray(0), emptyMap())
        }
        assertEquals("relay control timeout", error.message)
    }

    @Test
    fun slowProgressExceedingCallTimeoutThrowsRelayControlTimeout() {
        val server = ServerSocket(0)
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            val socket = server.accept()
            val reader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            try {
                repeat(5) {
                    Thread.sleep(100)
                    out.write("chunk".toByteArray(Charsets.US_ASCII))
                    out.flush()
                }
            } catch (_: Exception) {
            }
            socket.close()
            server.close()
        }

        val client = OkHttpClient.Builder()
            .callTimeout(250, TimeUnit.MILLISECONDS)
            .build()
        val poster = OkHttpHttpsPoster(client)
        val error = assertFailsWith<IOException> {
            poster.post("http://127.0.0.1:$port/test", ByteArray(0), emptyMap())
        }
        assertEquals("relay control timeout", error.message)
    }

    @Test
    fun sendsSplUserAgentHeader() {
        val server = ServerSocket(0)
        val port = server.localPort
        val userAgents = mutableListOf<String>()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            val socket = server.accept()
            val reader = socket.getInputStream().bufferedReader()
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
                if (line.lowercase().startsWith("user-agent:")) {
                    userAgents += line.substringAfter(':').trim()
                }
            }
            val out = socket.getOutputStream()
            val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray(Charsets.US_ASCII)
            out.write(response)
            out.flush()
            socket.close()
            server.close()
        }

        val poster = OkHttpHttpsPoster()
        val result = poster.post("http://127.0.0.1:$port/test", ByteArray(0), emptyMap())
        assertEquals(200, result.status)
        assertEquals(listOf("spl"), userAgents)
    }

    @Test
    fun relayPairDialRequestCarriesSplUserAgent() {
        val req = relayPairDialRequest("wss://link.solstone.app", byteArrayOf(1, 2, 3))
        assertEquals("spl", req.header("User-Agent"))
    }
}
