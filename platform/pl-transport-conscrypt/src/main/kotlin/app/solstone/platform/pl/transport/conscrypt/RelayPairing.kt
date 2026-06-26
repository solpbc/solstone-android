// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.assertCaPin
import app.solstone.core.crypto.buildCsrPem
import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.generateP256KeyPair
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
}

fun interface RelayPairDialer {
    fun open(host: String, port: Int, instanceId: String, pairTicket: String): RelayDialSession
}

class OkHttpRelayPairDialer(private val client: OkHttpClient = OkHttpClient()) : RelayPairDialer {
    override fun open(host: String, port: Int, instanceId: String, pairTicket: String): RelayDialSession {
        val request = relayWebSocketRequest(host, "/session/pair-dial", instanceId, pairTicket)
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
)

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
    val ticketBody = toJson(mapOf("instance_id" to link.instanceId, "totp" to link.totp)).toByteArray(Charsets.UTF_8)
    val ticketHttp = httpsPoster.post(
        "$relayOrigin/session/pair-ticket?instance=${link.instanceId}",
        ticketBody,
        JSON_HEADERS,
    )
    if (ticketHttp.status != 200) {
        throw IOException("relay pair ticket failed HTTP ${ticketHttp.status}: ${ticketHttp.bodyText()}")
    }
    val pairTicket = requiredJsonString(ticketHttp.bodyText(), "pair_ticket")

    val keyPair = generateP256KeyPair()
    val privateKeyPem = pem("PRIVATE KEY", keyPair.private.encoded)
    val csr = buildCsrPem(deviceLabel, keyPair)
    val pairBody = PairRequest(csr, deviceLabel).toJson().toByteArray(Charsets.UTF_8)
    val pairResponse: PairResponse
    val pairStatus: Int
    val session = relayPairDialer.open(relayHost, 443, link.instanceId, pairTicket)
    try {
        val pairHttp = session.client.request(
            "POST",
            "/app/network/pair?token=${link.nonce}",
            JSON_HEADERS,
            pairBody,
        )
        pairStatus = pairHttp.status
        if (pairHttp.status != 200) {
            throw IOException("relay inner pair failed HTTP ${pairHttp.status}: ${pairHttp.bodyText()}")
        }
        pairResponse = PairResponse.fromJson(pairHttp.bodyText())
        if (link.caFpTag != 0x01) {
            throw IOException("unsupported relay CA fingerprint tag: ${link.caFpTag}")
        }
        assertCaPin(pairResponse.caChain.first(), link.caFpPrefix, session.peerLeafCertificateDer)
        if (pairResponse.instanceId != link.instanceId) {
            throw IOException("relay pair response instance_id mismatch")
        }
        val clientDer = certificateFromPem(pairResponse.clientCert).encoded
        if ("sha256:" + sha256Hex(clientDer) != pairResponse.fingerprint) {
            throw IOException("relay pair response client fingerprint mismatch")
        }
    } finally {
        session.close()
    }

    val enrollBody = toJson(
        mapOf(
            "instance_id" to link.instanceId,
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
    )
}

fun openRelaySyncClient(
    relayOrigin: String,
    instanceId: String,
    deviceToken: String,
    credential: ClientCredential,
): ConscryptPlHttpClient {
    val host = URL(normalizeRelayOrigin(relayOrigin)).host
    val request = relayWebSocketRequest(host, "/session/dial", instanceId, deviceToken)
    return openRelayClient(host, 443, OkHttpTunnelOpener(OkHttpClient(), request), RelayTlsMode.Authenticated(credential)).client
}

private fun relayWebSocketRequest(host: String, path: String, instanceId: String, token: String): Request =
    Request.Builder()
        .url("wss://$host$path?instance=$instanceId&token=$token")
        .build()

private fun normalizeRelayOrigin(relayOrigin: String): String = relayOrigin.trimEnd('/')

private fun requiredJsonString(text: String, key: String): String {
    val root = parseJson(text) as? Map<*, *> ?: throw IllegalArgumentException("JSON response must be an object")
    return root[key] as? String ?: throw IllegalArgumentException("JSON response missing $key")
}

private class RelayPlClientDialSession(private val relayClient: RelayPlClient) : RelayDialSession {
    override val client: PlHttpClient = relayClient.client
    override val peerLeafCertificateDer: ByteArray? = relayClient.peerLeafCertificateDer

    override fun close() {
        relayClient.close()
    }
}

private val JSON_HEADERS = mapOf("content-type" to "application/json")
private const val DEFAULT_RELAY_ORIGIN = "https://link.solstone.app"
