// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.assertCaPin
import app.solstone.core.crypto.buildCsrPem
import app.solstone.core.crypto.CaPinException
import app.solstone.core.crypto.caSpkiFp16
import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.deriveRk
import app.solstone.core.crypto.generateP256KeyPair
import app.solstone.core.crypto.hex
import app.solstone.core.crypto.jidFromSpki
import app.solstone.core.crypto.pem
import app.solstone.core.crypto.pemToDer
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PairRequest
import app.solstone.core.pl.PairResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.Closeable
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

fun interface HttpsPoster {
    fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse
}

class OkHttpHttpsPoster(private val client: OkHttpClient = OkHttpClient()) : HttpsPoster {
    override fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse {
        val requestBody = body.toRequestBody((headers["content-type"] ?: "application/json").toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .also { builder -> headers.forEach { (name, value) -> builder.header(name, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            return HttpResponse(
                response.code,
                response.headers.names().associateWith { name -> response.header(name).orEmpty() },
                response.body?.bytes() ?: ByteArray(0),
            )
        }
    }
}

interface RelayDialSession : Closeable {
    val client: PlHttpClient
    val peerLeafCertificateDer: ByteArray?
    val peerCertificateChainDer: List<ByteArray>?
}

fun interface RelayPairDialer {
    fun open(host: String, port: Int, rk: ByteArray): RelayDialSession
}

class OkHttpRelayPairDialer(private val client: OkHttpClient = OkHttpClient()) : RelayPairDialer {
    override fun open(host: String, port: Int, rk: ByteArray): RelayDialSession {
        val request = relayPairDialRequest(host, rk)
        val relayClient = openRelayClient(host, port, OkHttpTunnelOpener(client, request), RelayTlsMode.Certless)
        return RelayPlClientDialSession(relayClient)
    }
}

fun defaultHttpsPoster(): HttpsPoster = OkHttpHttpsPoster()

fun defaultRelayPairDialer(): RelayPairDialer = OkHttpRelayPairDialer()

data class RelayPairResult(
    val handshakePinned: Boolean,
    val pairStatus: Int,
    val enrollStatus: Int,
    val homeLabel: String,
    val relayOrigin: String,
    val relayHost: String,
    val connectionMode: RelayPairConnectionMode = RelayPairConnectionMode.PAIRING,
)

enum class RelayPairConnectionMode { PAIRING, ALREADY_CONNECTED, RECONNECTING }

class RelayPairWindowClosedException(cause: Throwable? = null) :
    IOException("The pairing window is closed, expired, or was already used. Ask for a fresh pair link and try again.", cause)

fun pairOverRelay(
    link: RelayPairLink,
    deviceLabel: String,
    httpsPoster: HttpsPoster,
    relayPairDialer: RelayPairDialer,
    credentialStore: ClientCredentialStore,
    identityStore: IdentityStore,
): RelayPairResult {
    val relayOrigin = normalizeRelayOrigin(link.relayOrigin ?: DEFAULT_RELAY_ORIGIN)
    val relayHost = URL(relayOrigin).host
    val rk = deriveRk(link.s)

    val keyPair = generateP256KeyPair()
    val privateKeyPem = pem("PRIVATE KEY", keyPair.private.encoded)
    val csr = buildCsrPem(deviceLabel, keyPair)
    val pairBody = PairRequest(csr, deviceLabel).toJson().toByteArray(Charsets.UTF_8)
    val pairResponse: PairResponse
    val pairStatus: Int
    val session = try {
        relayPairDialer.open(relayHost, 443, rk)
    } catch (e: RelayPairWindowUnavailableException) {
        if (e.statusCode == 401) {
            throw RelayPairWindowClosedException(e)
        }
        throw e
    }
    try {
        pinInnerPeerBeforeSend(session.peerCertificateChainDer, session.peerLeafCertificateDer, link.caFpSpki)
        val pairHttp = session.client.request(
            "POST",
            "/app/network/pair?token=${hex(link.s)}",
            JSON_HEADERS,
            pairBody,
        )
        pairStatus = pairHttp.status
        if (pairHttp.status != 200) {
            throw IOException("relay inner pair failed HTTP ${pairHttp.status}: ${pairHttp.bodyText()}")
        }
        pairResponse = PairResponse.fromJson(pairHttp.bodyText())
        assertCaPin(pairResponse.caChain.first(), link.caFpSpki, session.peerLeafCertificateDer)
        val expectedJid = jidFromSpki(pairResponse.caChain.first())
        if (pairResponse.instanceId != expectedJid) {
            throw IOException("relay pair response instance_id did not match pinned CA identity")
        }
        val clientDer = certificateFromPem(pairResponse.clientCert).encoded
        if ("sha256:" + sha256Hex(clientDer) != pairResponse.fingerprint) {
            throw IOException("relay pair response client fingerprint mismatch")
        }
    } finally {
        session.close()
    }

    val prior = identityStore.load()
    if (prior?.instanceId == pairResponse.instanceId && prior.state == IdentityState.PAIRED) {
        return RelayPairResult(
            handshakePinned = true,
            pairStatus = pairStatus,
            enrollStatus = 200,
            homeLabel = prior.homeLabel,
            relayOrigin = relayOrigin,
            relayHost = relayHost,
            connectionMode = RelayPairConnectionMode.ALREADY_CONNECTED,
        )
    }
    val connectionMode = if (prior?.instanceId == pairResponse.instanceId) {
        RelayPairConnectionMode.RECONNECTING
    } else {
        RelayPairConnectionMode.PAIRING
    }

    val enrollBody = toJson(
        mapOf(
            "instance_id" to pairResponse.instanceId,
            // Python client.py:583 includes client_cert; TS SOT omits it, resolved against live relay in VPE-direct.
            "home_attestation" to pairResponse.homeAttestation,
        ),
    ).toByteArray(Charsets.UTF_8)
    val enrollHttp = httpsPoster.post("$relayOrigin/enroll/device", enrollBody, JSON_HEADERS)
    if (enrollHttp.status != 200) {
        throw IOException("relay enroll failed HTTP ${enrollHttp.status}: ${enrollHttp.bodyText()}")
    }
    val deviceToken = requiredJsonString(enrollHttp.bodyText(), "device_token")
    val clientDer = certificateFromPem(pairResponse.clientCert).encoded
    val caDer = pemToDer(pairResponse.caChain.first(), "CERTIFICATE")

    credentialStore.save(ClientCredential(privateKeyPem, pairResponse.clientCert, pairResponse.caChain))
    identityStore.save(
        PairedHome(
            instanceId = pairResponse.instanceId,
            homeLabel = pairResponse.homeLabel,
            relayOrigin = relayOrigin,
            caChainFingerprint = "sha256:" + sha256Hex(caDer),
            clientCertFingerprint = "sha256:" + sha256Hex(clientDer),
            observerHandle = null,
            deviceToken = deviceToken,
            expiresAt = null,
            state = IdentityState.PAIRED,
        ),
    )

    return RelayPairResult(
        handshakePinned = true,
        pairStatus = pairStatus,
        enrollStatus = enrollHttp.status,
        homeLabel = pairResponse.homeLabel,
        relayOrigin = relayOrigin,
        relayHost = relayHost,
        connectionMode = connectionMode,
    )
}

private const val RELAY_SYNC_WAITING_TIMEOUT_MS = 30_000L

fun openRelaySyncClient(
    relayOrigin: String,
    instanceId: String,
    deviceToken: String,
    credential: ClientCredential,
): ConscryptPlHttpClient {
    val host = URL(normalizeRelayOrigin(relayOrigin)).host
    val request = relayWebSocketRequest(host, "/session/dial", instanceId, deviceToken)
    return openRelayClient(
        host,
        443,
        OkHttpTunnelOpener(
            OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
            request,
            RELAY_SYNC_WAITING_TIMEOUT_MS,
        ),
        RelayTlsMode.Authenticated(credential),
    ).client
}

private fun relayWebSocketRequest(host: String, path: String, instanceId: String, token: String): Request =
    Request.Builder()
        .url("wss://$host$path?instance=$instanceId&token=$token")
        .build()

private fun relayPairDialRequest(host: String, rk: ByteArray): Request =
    Request.Builder()
        .url("wss://$host/session/pair-dial")
        .header("Sec-Pair-Key", hex(rk))
        .build()

internal fun normalizeRelayOrigin(relayOrigin: String): String = relayOrigin.trimEnd('/')

private fun requiredJsonString(text: String, key: String): String {
    val root = parseJson(text) as? Map<*, *> ?: throw IllegalArgumentException("JSON response must be an object")
    return root[key] as? String ?: throw IllegalArgumentException("JSON response missing $key")
}

private class RelayPlClientDialSession(private val relayClient: RelayPlClient) : RelayDialSession {
    override val client: PlHttpClient = relayClient.client
    override val peerLeafCertificateDer: ByteArray? = relayClient.peerLeafCertificateDer
    override val peerCertificateChainDer: List<ByteArray>? = relayClient.peerCertificateChainDer

    override fun close() {
        relayClient.close()
    }
}

private fun pinInnerPeerBeforeSend(
    chainDer: List<ByteArray>?,
    leafDer: ByteArray?,
    caFpSpki: ByteArray,
) {
    if (chainDer.isNullOrEmpty()) {
        throw CaPinException("relay inner TLS presented no peer certificate chain")
    }
    val matchedCaPem = chainDer
        .map { pem("CERTIFICATE", it) }
        .firstOrNull { caSpkiFp16(it).contentEquals(caFpSpki) }
        ?: throw CaPinException("relay inner TLS chain contained no CA matching the QR pin")
    assertCaPin(matchedCaPem, caFpSpki, leafDer)
}

internal val JSON_HEADERS = mapOf("content-type" to "application/json")
private const val DEFAULT_RELAY_ORIGIN = "https://link.solstone.app"
