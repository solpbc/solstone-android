// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateEntry
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

/**
 * An in-process TLS 1.3 server (BouncyCastle's) that ends one client connection the way a test
 * chooses. JSSE and Conscrypt servers cannot send an alert code of the caller's choosing; this one
 * raises any fatal alert, assigned or not, at a chosen moment.
 *
 * [Ending.AlertAfterClientFinished] raises the alert when the client's certificate arrives, which a
 * TLS 1.3 client sends together with its Finished: the moment a journal refuses an unpaired device.
 * [Ending.AlertBeforeClientFinished] raises it on the ClientHello, before the client has seen or
 * checked the server's certificate.
 */
class ChosenAlertTlsServer(
    private val chainDer: List<ByteArray>,
    private val keys: KeyPair,
    /** Half-close the connection once the ending is sent, as a relay closing its tunnel would. */
    private val finishAfterEnding: Boolean = false,
) : Closeable {
    sealed interface Ending {
        data class AlertAfterClientFinished(val code: Int) : Ending
        data class AlertBeforeClientFinished(val code: Int) : Ending

        /** Completes the handshake, then sends close_notify before any application byte. */
        data object CloseNotifyAfterHandshake : Ending

        /** Completes the handshake, then resets the TCP connection. */
        data object ResetAfterHandshake : Ending
    }

    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val bcCrypto = BcTlsCrypto(SecureRandom())

    val port: Int
        get() = listener.localPort

    /**
     * Serve the next connection with [ending]. The returned latch opens once the server has sent
     * its ending and let the connection go.
     */
    fun serveOnce(ending: Ending, onAccepted: (Socket) -> Unit = {}): CountDownLatch {
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "chosen-alert-server") {
            try {
                listener.accept().use { socket ->
                    onAccepted(socket)
                    socket.soTimeout = 15_000
                    serve(socket, ending)
                }
            } catch (_: Exception) {
            } finally {
                done.countDown()
            }
        }
        return done
    }

    private fun serve(socket: Socket, ending: Ending) {
        // BouncyCastle closes its streams after a fatal alert. Keep the socket open underneath, so
        // the client reads the alert rather than a reset that unread bytes would provoke.
        val protocol = TlsServerProtocol(
            object : FilterInputStream(socket.getInputStream()) {
                override fun close() = Unit
            },
            object : FilterOutputStream(socket.getOutputStream()) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() = flush()
            },
        )
        val server = object : DefaultTlsServer(bcCrypto) {
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()

            override fun getSelectedCipherSuite(): Int {
                if (ending is Ending.AlertBeforeClientFinished) throw TlsFatalAlert(ending.code.toShort())
                return super.getSelectedCipherSuite()
            }

            override fun getCredentials(): TlsCredentials {
                val entries = chainDer.map { CertificateEntry(bcCrypto.createCertificate(it), null) }.toTypedArray()
                return BcDefaultTlsCredentialedSigner(
                    TlsCryptoParameters(context),
                    bcCrypto,
                    PrivateKeyFactory.createKey(keys.private.encoded),
                    Certificate(ByteArray(0), entries),
                    SignatureAndHashAlgorithm.getInstance(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa),
                )
            }

            override fun getCertificateRequest(): CertificateRequest =
                CertificateRequest(ByteArray(0), TlsUtils.getDefaultSupportedSignatureAlgorithms(context), null, null)

            override fun notifyClientCertificate(clientCertificate: Certificate) {
                if (ending is Ending.AlertAfterClientFinished) throw TlsFatalAlert(ending.code.toShort())
            }
        }
        try {
            protocol.accept(server)
        } catch (_: IOException) {
            // The chosen alert, or the client walking away: either way the ending has been sent.
            drain(socket)
            return
        }
        when (ending) {
            Ending.CloseNotifyAfterHandshake -> {
                protocol.close()
                drain(socket)
            }
            Ending.ResetAfterHandshake -> {
                socket.setSoLinger(true, 0)
                socket.close()
            }
            else -> drain(socket)
        }
    }

    /** Keep the connection until the client lets go, so a late client write never races a close. */
    private fun drain(socket: Socket) {
        if (socket.isClosed) return
        if (finishAfterEnding) runCatching { socket.shutdownOutput() }
        runCatching {
            socket.soTimeout = 3_000
            val input = socket.getInputStream()
            val buffer = ByteArray(4096)
            while (input.read(buffer) >= 0) Unit
        }
    }

    override fun close() {
        listener.close()
    }

    companion object {
        fun await(latch: CountDownLatch, context: () -> String = { "" }) {
            check(latch.await(20, TimeUnit.SECONDS)) { "test TLS server did not finish; ${context()}" }
        }
    }
}
