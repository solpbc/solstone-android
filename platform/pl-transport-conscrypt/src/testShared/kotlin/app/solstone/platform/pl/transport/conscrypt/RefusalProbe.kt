// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.ClientCredential
import app.solstone.core.pl.ByteDuplex
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.HandshakeFailure
import app.solstone.core.pl.JournalRefusalException
import app.solstone.core.pl.JournalVerdictListener
import app.solstone.core.pl.handshakeFailureOf
import app.solstone.core.pl.journalRefusalIn
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** What a verdict listener heard, in order. */
class RecordingVerdicts : JournalVerdictListener {
    val heard = CopyOnWriteArrayList<String>()

    override fun onAccepted() {
        heard += "accepted"
    }

    override fun onRefused(refusal: JournalRefusalException) {
        heard += "refused ${refusal.failure} ${refusal.alertCode}"
    }
}

/** How one dial and status request ended, as the refusal rule classifies it. */
/** [failure] is null when the request was answered. */
data class DialResult(val failure: HandshakeFailure?, val alertCode: Int?, val error: Throwable?)

/** A TCP connection as a [ByteDuplex], standing in for the relay's opaque byte pipe. */
class PlainSocketDuplex(private val socket: Socket) : ByteDuplex {
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    override fun close() = socket.close()
}

/** Dial [port] directly with the production opener, then send the status request. */
fun dialDirect(port: Int, credential: ClientCredential, listener: JournalVerdictListener?): DialResult =
    classify {
        openAuthenticatedClient(DirectEndpoint("127.0.0.1", port), credential, verdictListener = listener).use { client ->
            client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0))
        }
    }

/** Dial [port] through the production engine path (the relay's inner TLS), then send the request. */
fun dialEngine(port: Int, credential: ClientCredential, listener: JournalVerdictListener?): DialResult =
    dialThrough({ PlainSocketDuplex(Socket(InetAddress.getByName("127.0.0.1"), port)) }, credential, listener)

fun dialThrough(open: () -> ByteDuplex, credential: ClientCredential, listener: JournalVerdictListener?): DialResult =
    classify {
        openRelayClient(
            host = "127.0.0.1",
            port = 443,
            tunnelOpener = TunnelOpener { open() },
            tlsMode = RelayTlsMode.Authenticated(credential),
            verdictListener = listener,
        ).use { relay -> relay.client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0)) }
    }

private fun classify(block: () -> Unit): DialResult =
    try {
        block()
        DialResult(null, null, null)
    } catch (e: Throwable) {
        DialResult(handshakeFailureOf(e), journalRefusalIn(e)?.alertCode, e)
    }
