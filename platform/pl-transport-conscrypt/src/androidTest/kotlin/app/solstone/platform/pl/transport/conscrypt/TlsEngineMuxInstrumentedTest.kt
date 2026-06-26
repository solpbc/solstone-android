// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.crypto.generateP256KeyPair
import app.solstone.core.crypto.requireTls13
import app.solstone.core.pl.ByteDuplex
import app.solstone.core.pl.FLAG_CLOSE
import app.solstone.core.pl.FLAG_DATA
import app.solstone.core.pl.FLAG_OPEN
import app.solstone.core.pl.Frame
import app.solstone.core.pl.MuxSession
import app.solstone.core.pl.decodeFrame
import app.solstone.core.pl.encodeFrame
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TlsEngineMuxInstrumentedTest {
    @Test
    fun tls13EngineCarriesOneMuxRequest() {
        installConscrypt()
        val (clientRaw, serverRaw) = pairedDuplexes()
        val serverEngine = serverEngine()
        var serverError: Throwable? = null
        val serverThread = thread(start = true) {
            try {
                TlsEngineDuplex(serverEngine, serverRaw).use { serverTls ->
                    val request = readFrame(serverTls.input)
                    val requestText = request.payload.toString(Charsets.US_ASCII)
                    assertEquals(1, request.streamId)
                    assertEquals(true, (request.flags and FLAG_OPEN) != 0)
                    assertEquals(true, (request.flags and FLAG_DATA) != 0)
                    assertEquals(true, requestText.contains("GET /health HTTP/1.1"))

                    val close = readFrame(serverTls.input)
                    assertEquals(1, close.streamId)
                    assertEquals(true, (close.flags and FLAG_CLOSE) != 0)

                    val body = "healthy".toByteArray(Charsets.US_ASCII)
                    val response = (
                        "HTTP/1.1 200 OK\r\n" +
                            "content-length: ${body.size}\r\n" +
                            "\r\n"
                        ).toByteArray(Charsets.US_ASCII) + body
                    serverTls.output.write(encodeFrame(request.streamId, FLAG_DATA or FLAG_CLOSE, response))
                    serverTls.output.flush()
                }
            } catch (t: Throwable) {
                serverError = t
            }
        }

        val clientTls = TlsEngineDuplex(certlessEngine("localhost", 7657), clientRaw)
        assertNotNull(clientTls.peerLeafCertificateDer)
        clientTls.use { tls ->
            val response = MuxSession(tls).request("GET", "/health", emptyMap(), null)
            assertEquals(200, response.status)
            assertArrayEquals("healthy".toByteArray(Charsets.US_ASCII), response.body)
        }
        serverThread.join(5000)
        serverError?.let { throw it }
        assertEquals(false, serverThread.isAlive)
    }

    private fun serverEngine(): SSLEngine {
        val context = SSLContext.getInstance("TLS", "Conscrypt")
        context.init(serverKeyManagers(), arrayOf<TrustManager>(TrustAllManager), SecureRandom())
        val engine = context.createSSLEngine()
        engine.useClientMode = false
        engine.enabledProtocols = requireTls13(engine.supportedProtocols)
        return engine
    }

    private fun serverKeyManagers(): Array<KeyManager> {
        val keyPair = generateP256KeyPair()
        val cert = selfSignedCertificate(keyPair)
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null)
        store.setKeyEntry("server", keyPair.private, PASSWORD, arrayOf(cert))
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(store, PASSWORD)
        return factory.keyManagers
    }

    private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val name = X500Principal("CN=localhost")
        val algorithm = der(0x30, ECDSA_WITH_SHA256_OID)
        val tbs = der(
            0x30,
            concat(
                der(0xa0, derInteger(BigInteger.valueOf(2))),
                derInteger(BigInteger.valueOf(System.currentTimeMillis()).abs()),
                algorithm,
                name.encoded,
                der(0x30, concat(derTime("20260101000000Z"), derTime("20360101000000Z"))),
                name.encoded,
                keyPair.public.encoded,
            ),
        )
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(tbs)
        val cert = der(
            0x30,
            concat(
                tbs,
                algorithm,
                der(0x03, concat(byteArrayOf(0x00), signer.sign())),
            ),
        )
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(cert.inputStream()) as X509Certificate
    }

    private fun readFrame(input: InputStream): Frame {
        val header = readExactly(input, 8)
        val length = ((header[5].toInt() and 0xff) shl 16) or
            ((header[6].toInt() and 0xff) shl 8) or
            (header[7].toInt() and 0xff)
        val payload = readExactly(input, length)
        return decodeFrame(header + payload).frame
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            check(read >= 0) { "stream closed" }
            offset += read
        }
        return out
    }

    private fun pairedDuplexes(): Pair<TestDuplex, TestDuplex> {
        val clientInput = PipedInputStream(PIPE_SIZE)
        val serverOutput = PipedOutputStream(clientInput)
        val serverInput = PipedInputStream(PIPE_SIZE)
        val clientOutput = PipedOutputStream(serverInput)
        return TestDuplex(clientInput, clientOutput) to TestDuplex(serverInput, serverOutput)
    }

    private class TestDuplex(
        override val input: InputStream,
        override val output: OutputStream,
    ) : ByteDuplex {
        override fun close() {
            input.close()
            output.close()
        }
    }

    private object TrustAllManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    }

    private companion object {
        val PASSWORD: CharArray = "test".toCharArray()
        const val PIPE_SIZE = 128 * 1024
        val ECDSA_WITH_SHA256_OID = byteArrayOf(
            0x06,
            0x08,
            0x2a,
            0x86.toByte(),
            0x48,
            0xce.toByte(),
            0x3d,
            0x04,
            0x03,
            0x02,
        )

        fun der(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            writeDerLength(out, content.size)
            out.write(content, 0, content.size)
            return out.toByteArray()
        }

        fun derInteger(value: BigInteger): ByteArray = der(0x02, value.toByteArray())

        fun derTime(value: String): ByteArray = der(0x18, value.toByteArray(Charsets.US_ASCII))

        fun writeDerLength(out: ByteArrayOutputStream, length: Int) {
            if (length < 0x80) {
                out.write(length)
                return
            }
            var bytes = 0
            var value = length
            while (value > 0) {
                bytes += 1
                value = value shr 8
            }
            out.write(0x80 or bytes)
            for (shift in (bytes - 1) * 8 downTo 0 step 8) {
                out.write((length shr shift) and 0xff)
            }
        }

        fun concat(vararg parts: ByteArray): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var offset = 0
            for (part in parts) {
                part.copyInto(out, offset)
                offset += part.size
            }
            return out
        }
    }
}
