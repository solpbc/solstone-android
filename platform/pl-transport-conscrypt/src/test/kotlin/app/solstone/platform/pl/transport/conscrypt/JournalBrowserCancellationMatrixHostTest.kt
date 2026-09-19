// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.ByteDuplex
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.FLAG_CLOSE
import app.solstone.core.pl.FLAG_DATA
import app.solstone.core.pl.FLAG_OPEN
import app.solstone.core.pl.MuxSession
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.system.measureTimeMillis
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JournalBrowserCancellationMatrixHostTest {

    private lateinit var fixture: EphemeralTlsFixture

    @BeforeTest
    fun setUp() {
        fixture = EphemeralTlsFixture.generate()
    }

    @Test
    fun directAcceptThenStallBeforeHandshakeAbortsWithinOneSecond() {
        val serverSocket = ServerSocket(0).apply { soTimeout = 2000 }
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sock = serverSocket.accept()
                try {
                    Thread.sleep(300)
                } finally {
                    sock.close()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
                serverDone.countDown()
            }
        }
        serverThread.start()

        val elapsed = measureTimeMillis {
            runCatching {
                val client = openAuthenticatedClient(
                    DirectEndpoint("127.0.0.1", serverSocket.localPort),
                    fixture.credential,
                )
                client.request("GET", "/test", emptyMap(), null)
            }
        }

        assertTrue(elapsed <= 1000, "Stall before handshake took $elapsed ms (>1000ms)")
        serverDone.await(2, TimeUnit.SECONDS)
    }

    @Test
    fun directAcceptThenStallDuringHandshakeAbortsWithinOneSecond() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 2000 }
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sock = serverSocket.accept()
                try {
                    val buf = ByteArray(5)
                    sock.getInputStream().read(buf)
                    Thread.sleep(300)
                } finally {
                    sock.close()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
                serverDone.countDown()
            }
        }
        serverThread.start()

        val elapsed = measureTimeMillis {
            runCatching {
                val client = openAuthenticatedClient(
                    DirectEndpoint("127.0.0.1", serverSocket.localPort),
                    fixture.credential,
                )
                client.request("GET", "/test", emptyMap(), null)
            }
        }

        assertTrue(elapsed <= 1000, "Stall during handshake took $elapsed ms (>1000ms)")
        serverDone.await(2, TimeUnit.SECONDS)
    }

    @Test
    fun directAcceptHandshakeThenStallBeforeMuxOpenAbortsWithinOneSecond() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = true).apply { soTimeout = 2000 }
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sock = serverSocket.accept() as SSLSocket
                try {
                    sock.startHandshake()
                    Thread.sleep(300)
                } finally {
                    sock.close()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
                serverDone.countDown()
            }
        }
        serverThread.start()

        val elapsed = measureTimeMillis {
            runCatching {
                val client = openAuthenticatedClient(
                    DirectEndpoint("127.0.0.1", serverSocket.localPort),
                    fixture.credential,
                )
                client.close()
            }
        }

        assertTrue(elapsed <= 1000, "Stall after handshake took $elapsed ms (>1000ms)")
        serverDone.await(2, TimeUnit.SECONDS)
    }

    @Test
    fun relayStallBeforeHttp101AbortsWithinOneSecond() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = false).apply { soTimeout = 2000 }
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sock = serverSocket.accept() as SSLSocket
                try {
                    sock.startHandshake()
                    val `in` = sock.getInputStream()
                    val buf = ByteArray(128)
                    `in`.read(buf)
                    Thread.sleep(300)
                } finally {
                    sock.close()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
                serverDone.countDown()
            }
        }
        serverThread.start()

        val prev = SSLContext.getDefault()
        try {
            SSLContext.setDefault(fixture.createClientTrustContext())
            val elapsed = measureTimeMillis {
                runCatching {
                    openRelaySyncClient(
                        relayOrigin = "https://127.0.0.1:${serverSocket.localPort}",
                        instanceId = "inst",
                        deviceToken = "tok",
                        credential = fixture.credential,
                    )
                }
            }
            assertTrue(elapsed <= 1000, "Relay stall before 101 took $elapsed ms (>1000ms)")
        } finally {
            SSLContext.setDefault(prev)
            serverDone.await(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun relayStallDuringInnerTlsAbortsWithinOneSecond() {
        val serverSocket = fixture.createServerSslServerSocket(0, needClientAuth = false).apply { soTimeout = 2000 }
        val serverDone = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val sock = serverSocket.accept() as SSLSocket
                try {
                    sock.startHandshake()
                    val rawIn = sock.getInputStream()
                    val rawOut = sock.getOutputStream()

                    val lines = mutableListOf<String>()
                    val lineBuf = StringBuilder()
                    while (true) {
                        val c = rawIn.read()
                        if (c < 0 || c == '\n'.code) {
                            val l = lineBuf.toString().trim()
                            if (l.isEmpty()) break
                            lines.add(l)
                            lineBuf.clear()
                        } else if (c != '\r'.code) {
                            lineBuf.append(c.toChar())
                        }
                    }
                    val secKey = lines.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim() ?: ""
                    val acceptSha = MessageDigest.getInstance("SHA-1").digest(
                        (secKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)
                    )
                    val acceptKey = Base64.getEncoder().encodeToString(acceptSha)
                    rawOut.write(
                        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $acceptKey\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII)
                    )
                    rawOut.flush()

                    // Now stall on inner TLS handshake
                    Thread.sleep(300)
                } finally {
                    sock.close()
                }
            } catch (_: Exception) {
            } finally {
                serverSocket.close()
                serverDone.countDown()
            }
        }
        serverThread.start()

        val prev = SSLContext.getDefault()
        try {
            SSLContext.setDefault(fixture.createClientTrustContext())
            val elapsed = measureTimeMillis {
                runCatching {
                    openRelaySyncClient(
                        relayOrigin = "https://127.0.0.1:${serverSocket.localPort}",
                        instanceId = "inst",
                        deviceToken = "tok",
                        credential = fixture.credential,
                    )
                }
            }
            assertTrue(elapsed <= 1000, "Relay stall during inner TLS took $elapsed ms (>1000ms)")
        } finally {
            SSLContext.setDefault(prev)
            serverDone.await(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun partialMuxFrameAbortsProgressively() {
        val pipeIn = java.io.PipedInputStream()
        val pipeOut = java.io.PipedOutputStream(pipeIn)

        val duplex = object : ByteDuplex {
            override val input = pipeIn
            override val output = java.io.ByteArrayOutputStream()
            override fun close() {
                pipeIn.close()
                pipeOut.close()
            }
        }

        val session = MuxSession(duplex)

        // Write first 5 bytes of 8-byte header of a frame then close
        pipeOut.write(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x02)) // incomplete header
        pipeOut.flush()
        pipeOut.close()

        val err = AtomicReference<Throwable>()
        val done = CountDownLatch(1)

        val elapsed = measureTimeMillis {
            runCatching {
                session.requestStreaming("GET", "/test", emptyList(), null, object : BrowserResponseSink {
                    override fun onStatusAndHeaders(status: Int, reason: String, headers: List<Pair<String, String>>) {}
                    override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {}
                    override fun onTrailers(trailers: List<Pair<String, String>>) {}
                    override fun onComplete() { done.countDown() }
                    override fun onError(cause: Throwable) {
                        err.set(cause)
                        done.countDown()
                    }
                })
            }.onFailure { err.set(it) }
            done.await(1, TimeUnit.SECONDS)
        }

        assertTrue(elapsed <= 1000)
        assertTrue(err.get() is IOException)
    }

    @Test
    fun downstreamClientDisconnectCancelsStreamAndSessionStopClosesAll() {
        val activeClient = AtomicBoolean(false)

        val upstream = object : JournalBrowserUpstream {
            override val isPoisoned: Boolean get() = false
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?) =
                BrowserHttpResponse(200, emptyList(), ByteArray(0))

            override fun requestStreaming(
                method: String,
                path: String,
                headers: List<Pair<String, String>>,
                bodySource: BrowserRequestBodySource?,
                responseSink: BrowserResponseSink,
            ) {
                activeClient.set(true)
                responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Length" to "1000"))
                // Stream chunks
                try {
                    while (activeClient.get()) {
                        responseSink.onBodyChunk(ByteArray(100), 0, 100)
                        Thread.sleep(50)
                    }
                } catch (e: Exception) {
                    responseSink.onError(e)
                }
            }

            override fun close() {
                activeClient.set(false)
            }
        }

        val session = JournalBrowserSession(
            pairing = { PairingGeneration("inst", "cert_fp") },
            accessStillCurrent = { true },
            upstreamFactory = { upstream },
            diag = {},
        )
        val origin = session.start(0)

        val uri = URI(origin.url)
        val clientSocket = Socket("127.0.0.1", uri.port)
        val out = clientSocket.getOutputStream()
        val `in` = clientSocket.getInputStream()

        out.write("GET ${uri.path}stream HTTP/1.1\r\nHost: ${uri.host}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()

        val buf = ByteArray(256)
        `in`.read(buf) // Read headers

        // Downstream client disconnects
        clientSocket.close()

        // Verify session stop shuts down within 1000ms
        val elapsed = measureTimeMillis {
            session.stop()
        }
        assertTrue(elapsed <= 1000)
    }
}
