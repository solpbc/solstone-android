// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.ClientCredential
import app.solstone.core.pl.ByteDuplex
import app.solstone.core.pl.MuxSession
import app.solstone.core.pl.PlStreamObserver
import app.solstone.core.pl.RelayDialObserver
import java.io.Closeable
import okhttp3.OkHttpClient
import okhttp3.Request

fun interface TunnelOpener {
    fun open(): ByteDuplex
}

sealed class RelayTlsMode {
    data object Certless : RelayTlsMode()
    data class Authenticated(val credential: ClientCredential) : RelayTlsMode()
}

class RelayPlClient internal constructor(
    val client: ConscryptPlHttpClient,
    val peerLeafCertificateDer: ByteArray?,
    val peerCertificateChainDer: List<ByteArray>?,
) : Closeable {
    override fun close() {
        client.close()
    }
}

fun openRelayClient(
    host: String,
    port: Int,
    tunnelOpener: TunnelOpener,
    tlsMode: RelayTlsMode,
    streamObserver: PlStreamObserver? = null,
    dialObserver: RelayDialObserver? = null,
): RelayPlClient {
    notifyDialObserver(dialObserver, host, port)
    val raw = tunnelOpener.open()
    try {
        val engine = when (tlsMode) {
            RelayTlsMode.Certless -> certlessEngine(host, port)
            is RelayTlsMode.Authenticated -> authenticatedEngine(tlsMode.credential, host, port)
        }
        val tls = TlsEngineDuplex(engine, raw)
        val client = ConscryptPlHttpClient(MuxSession(tls, streamObserver))
        return RelayPlClient(client, tls.peerLeafCertificateDer, tls.peerCertificateChainDer)
    } catch (e: Exception) {
        raw.close()
        throw e
    }
}

private fun notifyDialObserver(observer: RelayDialObserver?, host: String, port: Int) {
    try {
        observer?.onRelayDialAttempt(1, host, port)
    } catch (_: Throwable) {
        // Optional observation must never alter production dialing.
    }
}

class OkHttpTunnelOpener(
    private val client: OkHttpClient,
    private val request: Request,
    private val waitingTimeoutMillis: Long = 0L,
) : TunnelOpener {
    override fun open(): ByteDuplex = OkHttpWebSocketDuplex.open(client, request, waitingTimeoutMillis)
}
