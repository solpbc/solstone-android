// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.buildCsrPem
import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.chainMatchesPrefix
import app.solstone.core.crypto.generateP256KeyPair
import app.solstone.core.crypto.pem
import app.solstone.core.crypto.pemToDer
import app.solstone.core.crypto.requireTls13
import app.solstone.core.crypto.sha256
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.crypto.startsWith
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.DirectPairLink
import app.solstone.core.pl.PairRequest
import app.solstone.core.pl.PairResponse
import app.solstone.core.pl.parseDirectPairLink
import java.io.IOException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

data class PairProbeResult(
    val handshakePinned: Boolean,
    val pairStatus: Int,
    val statusStatus: Int,
    val statusBody: String,
    val endpoint: DirectEndpoint,
)

fun pairAndProbe(
    pairLink: String,
    deviceLabel: String,
    credentialStore: ClientCredentialStore,
    identityStore: IdentityStore,
): PairProbeResult {
    val link = parseDirectPairLink(pairLink)
    val keyPair = generateP256KeyPair()
    val privateKeyPem = pem("PRIVATE KEY", keyPair.private.encoded)
    val csr = buildCsrPem(deviceLabel, keyPair)
    val body = PairRequest(csr, deviceLabel).toJson().toByteArray(Charsets.UTF_8)

    val pairSession = openCertlessSession(link)
    val pinned = pairSession.handshakePinned
    val pairHttp = pairSession.session.use { session ->
        session.request("POST", "/app/link/pair?token=" + link.nonce, mapOf("content-type" to "application/json"), body)
    }
    if (pairHttp.status != 200) {
        throw IOException("pair failed HTTP " + pairHttp.status + ": " + pairHttp.bodyText())
    }

    val resp = PairResponse.fromJson(pairHttp.bodyText())
    val caDer = pemToDer(resp.caChain.first(), "CERTIFICATE")
    if (!startsWith(sha256(caDer), link.caFingerprintPrefix)) {
        throw SSLException("pair response CA fingerprint did not match QR pin")
    }
    val clientDer = certificateFromPem(resp.clientCert).encoded
    if ("sha256:" + sha256Hex(clientDer) != resp.fingerprint) {
        throw SSLException("pair response client fingerprint mismatch")
    }

    val credential = ClientCredential(privateKeyPem, resp.clientCert, resp.caChain)
    credentialStore.save(credential)
    val home = PairedHome(
        instanceId = resp.instanceId,
        homeLabel = resp.homeLabel,
        relayOrigin = null,
        caChainFingerprint = "sha256:" + sha256Hex(caDer),
        clientCertFingerprint = "sha256:" + sha256Hex(clientDer),
        observerHandle = null,
        state = IdentityState.PAIRED,
    )
    identityStore.save(home)

    val endpoint = link.endpoint()
    val statusHttp = openAuthenticatedClient(endpoint, credential).use { client ->
        client.request("GET", "/app/link/api/status", emptyMap(), ByteArray(0))
    }
    return PairProbeResult(pinned, pairHttp.status, statusHttp.status, statusHttp.bodyText(), endpoint)
}

/**
 * Open an authenticated mTLS PL session to [endpoint] using a previously persisted
 * [credential] and wrap it as a reusable [ConscryptPlHttpClient]. This is the seam a
 * paired observer reuses across process restarts to register, ingest, and reconcile —
 * pairing happens once (see [pairAndProbe]); every later request opens an authenticated
 * client from the stored credential. Caller owns the returned client and must close it.
 */
fun openAuthenticatedClient(endpoint: DirectEndpoint, credential: ClientCredential): ConscryptPlHttpClient =
    ConscryptPlHttpClient(openAuthenticatedSession(endpoint, credential))

private data class CertlessSession(val session: MuxSession, val handshakePinned: Boolean)

private fun openCertlessSession(link: DirectPairLink): CertlessSession {
    val endpoint = link.endpoint()
    val socket = certlessFactory().createSocket(endpoint.host, endpoint.port) as SSLSocket
    try {
        configureSocket(socket)
        socket.startHandshake()
        val pinned = chainMatchesPrefix(
            socket.session.peerCertificates.map { it.encoded },
            link.caFingerprintPrefix,
        )
        if (!pinned) {
            throw SSLException("pair TLS peer chain did not match QR CA pin")
        }
        return CertlessSession(MuxSession(socket), pinned)
    } catch (e: Exception) {
        socket.close()
        throw e
    }
}

private fun openAuthenticatedSession(endpoint: DirectEndpoint, credential: ClientCredential): MuxSession {
    val socket = authenticatedFactory(credential).createSocket(endpoint.host, endpoint.port) as SSLSocket
    try {
        configureSocket(socket)
        socket.startHandshake()
        return MuxSession(socket)
    } catch (e: Exception) {
        socket.close()
        throw e
    }
}

private fun configureSocket(socket: SSLSocket) {
    socket.soTimeout = SOCKET_TIMEOUT_MS
    socket.enabledProtocols = requireTls13(socket.supportedProtocols)
}
