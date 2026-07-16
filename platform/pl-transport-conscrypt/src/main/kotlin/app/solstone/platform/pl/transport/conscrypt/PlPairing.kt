// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.CaPinException
import app.solstone.core.crypto.assertDirectCaPin
import app.solstone.core.crypto.buildCsrPem
import app.solstone.core.crypto.certificateFromPem
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
import app.solstone.core.pl.DialDecision
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.LocalIPv4Interface
import app.solstone.core.pl.MuxSession
import app.solstone.core.pl.PairRequest
import app.solstone.core.pl.PairResponse
import app.solstone.core.pl.SOCKET_TIMEOUT_MS
import app.solstone.core.pl.classifyPairResponseStatus
import app.solstone.core.pl.orderCandidatesBySubnet
import app.solstone.core.pl.parseDirectPairLink
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket

data class PairProbeResult(
    val handshakePinned: Boolean,
    val pairStatus: Int,
    val statusStatus: Int,
    val statusBody: String,
    val endpoint: DirectEndpoint,
    val connectionMode: DirectPairConnectionMode = DirectPairConnectionMode.PAIRING,
)

enum class DirectPairConnectionMode { PAIRING, ALREADY_CONNECTED, RECONNECTING }

class DirectPairEndpointException(
    val endpointHost: String,
    val endpointPort: Int,
    cause: Exception,
) : IOException("direct pair endpoint failed: $endpointHost:$endpointPort", cause)

fun pairAndProbe(
    pairLink: String,
    deviceLabel: String,
    credentialStore: ClientCredentialStore,
    identityStore: IdentityStore,
    endpointStore: EndpointStore,
): PairProbeResult = pairAndProbe(
    pairLink = pairLink,
    deviceLabel = deviceLabel,
    credentialStore = credentialStore,
    identityStore = identityStore,
    endpointStore = endpointStore,
    sessionOpener = ::openCertlessSession,
    localInterfaces = readLocalIPv4Interfaces(),
)

internal fun pairAndProbe(
    pairLink: String,
    deviceLabel: String,
    credentialStore: ClientCredentialStore,
    identityStore: IdentityStore,
    endpointStore: EndpointStore,
    sessionOpener: (DirectEndpoint, ByteArray) -> CertlessSession,
    localInterfaces: List<LocalIPv4Interface>,
): PairProbeResult {
    val link = parseDirectPairLink(pairLink)
    val ordered = orderCandidatesBySubnet(link.candidates, localInterfaces)
    val keyPair = generateP256KeyPair()
    val privateKeyPem = pem("PRIVATE KEY", keyPair.private.encoded)
    val csr = buildCsrPem(deviceLabel, keyPair)
    val body = PairRequest(csr, deviceLabel).toJson().toByteArray(Charsets.UTF_8)

    var lastError: Exception? = null
    var lastEndpoint: DirectEndpoint? = null
    var sawCaMismatch = false

    for (endpoint in ordered) {
        val pairSession = try {
            sessionOpener(endpoint, link.caFingerprintPrefix)
        } catch (e: Exception) {
            if (e is SSLException && e.message == PAIR_TLS_CA_PIN_MISMATCH) {
                sawCaMismatch = true
            }
            lastError = e
            lastEndpoint = endpoint
            continue
        }
        val pinned = pairSession.handshakePinned
        val pairHttp = try {
            pairSession.session.use { session ->
                session.request("POST", "/app/network/pair?token=" + link.nonce, mapOf("content-type" to "application/json"), body)
            }
        } catch (e: Exception) {
            lastError = e
            lastEndpoint = endpoint
            continue
        }

        when (classifyPairResponseStatus(pairHttp.status)) {
            DialDecision.SUCCEED -> {
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
                val home = PairedHome(
                    instanceId = resp.instanceId,
                    homeLabel = resp.homeLabel,
                    relayOrigin = null,
                    caChainFingerprint = "sha256:" + sha256Hex(caDer),
                    clientCertFingerprint = "sha256:" + sha256Hex(clientDer),
                    observerHandle = null,
                    deviceToken = null,
                    expiresAt = null,
                    state = IdentityState.PAIRED,
                )
                return persistOrReturnDirectPairResult(
                    home = home,
                    credential = credential,
                    endpoint = endpoint,
                    handshakePinned = pinned,
                    pairStatus = pairHttp.status,
                    credentialStore = credentialStore,
                    identityStore = identityStore,
                    endpointStore = endpointStore,
                    statusProbe = { statusEndpoint, statusCredential ->
                        openAuthenticatedClient(statusEndpoint, statusCredential).use { client ->
                            client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0))
                        }
                    },
                )
            }
            DialDecision.TERMINAL -> {
                throw IOException("pair failed HTTP " + pairHttp.status + ": " + pairHttp.bodyText())
            }
            DialDecision.ADVANCE -> {
                lastError = IOException("pair failed HTTP " + pairHttp.status + ": " + pairHttp.bodyText())
                lastEndpoint = endpoint
            }
        }
    }

    if (sawCaMismatch) {
        throw SSLException("scanned a pair link whose host did not match its CA pin")
    }
    val failure = lastError ?: IOException("all pair candidates exhausted")
    val endpoint = lastEndpoint
    throw directPairFailure(endpoint, failure)
}

internal fun directPairFailure(endpoint: DirectEndpoint?, failure: Exception): Exception =
    if (endpoint == null) failure else DirectPairEndpointException(endpoint.host, endpoint.port, failure)

internal fun persistOrReturnDirectPairResult(
    home: PairedHome,
    credential: ClientCredential,
    endpoint: DirectEndpoint,
    handshakePinned: Boolean,
    pairStatus: Int,
    credentialStore: ClientCredentialStore,
    identityStore: IdentityStore,
    endpointStore: EndpointStore,
    statusProbe: (DirectEndpoint, ClientCredential) -> HttpResponse,
): PairProbeResult {
    val prior = identityStore.load()
    if (prior?.instanceId == home.instanceId && prior.state == IdentityState.PAIRED) {
        return PairProbeResult(
            handshakePinned = handshakePinned,
            pairStatus = pairStatus,
            statusStatus = 200,
            statusBody = "",
            endpoint = endpointStore.load() ?: endpoint,
            connectionMode = DirectPairConnectionMode.ALREADY_CONNECTED,
        )
    }
    val connectionMode = if (prior?.instanceId == home.instanceId) {
        DirectPairConnectionMode.RECONNECTING
    } else {
        DirectPairConnectionMode.PAIRING
    }
    credentialStore.save(credential)
    identityStore.save(home)
    endpointStore.save(endpoint)
    val statusHttp = statusProbe(endpoint, credential)
    return PairProbeResult(
        handshakePinned = handshakePinned,
        pairStatus = pairStatus,
        statusStatus = statusHttp.status,
        statusBody = statusHttp.bodyText(),
        endpoint = endpoint,
        connectionMode = connectionMode,
    )
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

internal data class CertlessSession(val session: MuxSession, val handshakePinned: Boolean)

private fun openCertlessSession(endpoint: DirectEndpoint, caFingerprintPrefix: ByteArray): CertlessSession {
    val socket = connectedSslSocket(certlessFactory(), endpoint.host, endpoint.port)
    try {
        configureSocket(socket)
        socket.startHandshake()
        try {
            assertDirectCaPin(
                socket.session.peerCertificates.map { it.encoded },
                caFingerprintPrefix,
            )
        } catch (e: CaPinException) {
            throw SSLException(PAIR_TLS_CA_PIN_MISMATCH, e)
        }
        return CertlessSession(MuxSession(SocketByteDuplex(socket)), true)
    } catch (e: Exception) {
        socket.close()
        throw e
    }
}

private fun openAuthenticatedSession(endpoint: DirectEndpoint, credential: ClientCredential): MuxSession {
    val socket = connectedSslSocket(authenticatedFactory(credential), endpoint.host, endpoint.port)
    try {
        configureSocket(socket)
        socket.startHandshake()
        return MuxSession(SocketByteDuplex(socket))
    } catch (e: Exception) {
        socket.close()
        throw e
    }
}

private fun connectedSslSocket(factory: SSLSocketFactory, host: String, port: Int): SSLSocket {
    val plainSocket = Socket()
    try {
        plainSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        return factory.createSocket(plainSocket, host, port, true) as SSLSocket
    } catch (e: Exception) {
        plainSocket.close()
        throw e
    }
}

fun readLocalIPv4Interfaces(): List<LocalIPv4Interface> {
    return try {
        val out = mutableListOf<LocalIPv4Interface>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (!networkInterface.isUp || networkInterface.isLoopback) {
                continue
            }
            networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                val address = interfaceAddress.address
                if (address is Inet4Address) {
                    val hostAddress = address.hostAddress ?: return@forEach
                    if (!hostAddress.startsWith("127.") && !hostAddress.startsWith("169.254.")) {
                        out.add(LocalIPv4Interface(hostAddress, interfaceAddress.networkPrefixLength.toInt()))
                    }
                }
            }
        }
        out
    } catch (_: SocketException) {
        emptyList()
    }
}

private fun configureSocket(socket: SSLSocket) {
    socket.soTimeout = SOCKET_TIMEOUT_MS
    socket.enabledProtocols = requireTls13(socket.supportedProtocols)
}

private const val CONNECT_TIMEOUT_MS = 5000
private const val PAIR_TLS_CA_PIN_MISMATCH = "pair TLS peer chain did not match QR CA pin"
