// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.CaPinException
import app.solstone.core.crypto.assertCaPin
import app.solstone.core.crypto.buildCsrPem
import app.solstone.core.crypto.caSpkiFp16
import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.deriveRk
import app.solstone.core.crypto.generateP256KeyPair
import app.solstone.core.crypto.hex
import app.solstone.core.crypto.jidFromCaPem
import app.solstone.core.crypto.pem
import app.solstone.core.crypto.pemToDer
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PairRelayAccess
import app.solstone.core.pl.PairRequest
import app.solstone.core.pl.PairResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.PlStreamObserver
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.core.pl.RelayAccessResponse
import app.solstone.core.pl.RelayDialObserver
import app.solstone.core.pl.RelayOrigin
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.decodePairRelayAccess
import app.solstone.core.pl.isRelayTokenUsableNow
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.parseProductionRelayOrigin
import app.solstone.core.pl.parseRelayAccessJwtV2
import app.solstone.core.pl.parseRelayTokenReplacement
import app.solstone.core.pl.parseRelayAccessMap
import app.solstone.core.pl.parseRfc3339ToEpochSeconds
import app.solstone.core.pl.supportedDirectDialEndpoint
import app.solstone.core.pl.toJson
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

const val SPL_USER_AGENT = "spl"
const val MAX_CONTROL_RESPONSE_BYTES = 64 * 1024

fun interface HttpsPoster {
    fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse
}

private fun defaultOkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", SPL_USER_AGENT)
                    .build(),
            )
        }
        .build()

class OkHttpHttpsPoster(private val client: OkHttpClient = defaultOkHttpClient()) : HttpsPoster {
    override fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse {
        try {
            val requestBody = body.toRequestBody((headers["content-type"] ?: "application/json").toMediaType())
            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
            headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            requestBuilder.header("User-Agent", SPL_USER_AGENT)
            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    throw IOException("relay control redirect refused")
                }
                val responseBody = response.body
                val responseBytes = if (responseBody != null) {
                    val stream = responseBody.byteStream()
                    val buffer = ByteArrayOutputStream()
                    val temp = ByteArray(4096)
                    var totalRead = 0
                    while (true) {
                        val read = stream.read(temp)
                        if (read == -1) break
                        totalRead += read
                        if (totalRead > MAX_CONTROL_RESPONSE_BYTES) {
                            throw IOException("relay control response too large")
                        }
                        buffer.write(temp, 0, read)
                    }
                    buffer.toByteArray()
                } else {
                    ByteArray(0)
                }
                return HttpResponse(
                    response.code,
                    response.headers.names().associateWith { name -> response.header(name).orEmpty() },
                    responseBytes,
                )
            }
        } catch (e: IOException) {
            val msg = e.message
            if (msg == "relay control redirect refused" || msg == "relay control response too large") {
                throw e
            }
            if (e is SocketTimeoutException || e is InterruptedIOException) {
                throw IOException("relay control timeout", e)
            }
            throw IOException("relay control request failed", e)
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

private fun defaultDialerOkHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", SPL_USER_AGENT)
                    .build(),
            )
        }
        .build()

class OkHttpRelayPairDialer(
    private val client: OkHttpClient = defaultDialerOkHttpClient(),
    private val streamObserver: PlStreamObserver? = null,
    dialObserver: RelayDialObserver? = null,
) : RelayPairDialer {
    private val dialObs = dialObserver

    override fun open(host: String, port: Int, rk: ByteArray): RelayDialSession {
        val bracketedHost = if (host.contains(':')) "[$host]" else host
        val wssBase = if (port == 443) "wss://$bracketedHost" else "wss://$bracketedHost:$port"
        val request = relayPairDialRequest(wssBase, rk)
        val relayClient = openRelayClient(
            host,
            port,
            OkHttpTunnelOpener(client, request),
            RelayTlsMode.Certless,
            streamObserver,
            dialObs,
        )
        return RelayPlClientDialSession(relayClient)
    }
}

fun defaultHttpsPoster(): HttpsPoster = OkHttpHttpsPoster()

fun defaultRelayPairDialer(
    streamObserver: PlStreamObserver? = null,
    dialObserver: RelayDialObserver? = null,
): RelayPairDialer = OkHttpRelayPairDialer(
    streamObserver = streamObserver,
    dialObserver = dialObserver,
)

data class RelayPairResult(
    val handshakePinned: Boolean,
    val pairStatus: Int,
    val enrollStatus: Int?,
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
    journalVersionStore: JournalVersionStore? = null,
    coordinator: JournalVersionRefreshCoordinator? = null,
    mutator: IdentityMutator? = null,
    relayAccessCoordinator: RelayAccessRefreshCoordinator? = null,
    endpointStore: EndpointStore? = null,
): RelayPairResult {
    val origin = parseProductionRelayOrigin(link.relayOrigin ?: DEFAULT_RELAY_ORIGIN)
        ?: throw IOException("relay origin invalid")
    val relayOrigin = origin.httpsBase
    val relayHost = origin.host
    val rk = deriveRk(link.s)

    val keyPair = generateP256KeyPair()
    val privateKeyPem = pem("PRIVATE KEY", keyPair.private.encoded)
    val csr = buildCsrPem(deviceLabel, keyPair)
    val pairBody = PairRequest(csr, deviceLabel).toJson().toByteArray(Charsets.UTF_8)
    val pairResponse: PairResponse
    val pairStatus: Int
    val session = try {
        relayPairDialer.open(origin.host, origin.effectivePort, rk)
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
            throw IOException("relay inner pair failed")
        }
        pairResponse = PairResponse.fromJson(pairHttp.bodyText())
        assertCaPin(pairResponse.caChain.first(), link.caFpSpki, session.peerLeafCertificateDer)
        val expectedJid = jidFromCaPem(pairResponse.caChain.first())
        if (pairResponse.instanceId != expectedJid) {
            throw IOException("relay pair response instance_id did not match pinned CA identity")
        }
        val clientCertObj = certificateFromPem(pairResponse.clientCert)
        val clientDer = clientCertObj.encoded
        if ("sha256:" + sha256Hex(clientDer) != pairResponse.fingerprint) {
            throw IOException("relay pair response client fingerprint mismatch")
        }
        if (!clientCertObj.publicKey.encoded.contentEquals(keyPair.public.encoded)) {
            throw IOException("pair response client certificate key mismatch")
        }
    } finally {
        session.close()
    }

    val relayAccessDecoded = if (pairResponse.relayAccess is PairRelayAccess.Omitted) {
        null
    } else {
        val decoded = decodePairRelayAccess(pairResponse.relayAccess, pairResponse.instanceId, origin)
        if (decoded !is RelayAccessResponse.Ready) {
            throw IOException("relay pair bootstrap invalid")
        }
        decoded
    }

    val prior = if (mutator != null) mutator.current() else identityStore.load()
    if (prior?.instanceId == pairResponse.instanceId && prior.state == IdentityState.PAIRED) {
        if (relayAccessDecoded != null) {
            publishRelayBootstrap(prior, relayAccessDecoded.relayOrigin, relayAccessDecoded.deviceToken, relayAccessDecoded.expiresAt, identityStore, mutator)
        }
        val cred = credentialStore.load()
        val currentHome = if (mutator != null) mutator.current() else identityStore.load()
        val curOrigin = currentHome?.relayOrigin
        val curToken = currentHome?.deviceToken
        if (cred != null && curToken != null && curOrigin != null) {
            coordinator?.onUsableConnection(prior.instanceId, prior.caChainFingerprint, prior.clientCertFingerprint) {
                openRelaySyncClient(curOrigin, prior.instanceId, curToken, cred)
            }
            relayAccessCoordinator?.onUsableConnection(prior.instanceId, prior.caChainFingerprint, prior.clientCertFingerprint) {
                openRelaySyncClient(curOrigin, prior.instanceId, curToken, cred)
            }
        }
        return RelayPairResult(
            handshakePinned = true,
            pairStatus = pairStatus,
            enrollStatus = null,
            homeLabel = prior.homeLabel,
            relayOrigin = relayOrigin,
            relayHost = relayHost,
            connectionMode = RelayPairConnectionMode.ALREADY_CONNECTED,
        )
    }

    val connectionMode = if (prior?.instanceId == pairResponse.instanceId) {
        coordinator?.onPairingChanged()
        relayAccessCoordinator?.onPairingChanged()
        RelayPairConnectionMode.RECONNECTING
    } else {
        coordinator?.onIdentityChanged() ?: journalVersionStore?.clear()
        relayAccessCoordinator?.onIdentityChanged()
        RelayPairConnectionMode.PAIRING
    }

    val clientDer = certificateFromPem(pairResponse.clientCert).encoded
    val caDer = pemToDer(pairResponse.caChain.first(), "CERTIFICATE")
    val credential = ClientCredential(privateKeyPem, pairResponse.clientCert, pairResponse.caChain)
    fun saveLocalEndpoints() {
        val firstAdmitted = pairResponse.localEndpoints.firstNotNullOfOrNull { ep ->
            val ip = ep["ip"] as? String ?: return@firstNotNullOfOrNull null
            val port = (ep["port"] as? Number)?.toInt() ?: 0
            supportedDirectDialEndpoint(ip, port)
        }
        if (firstAdmitted != null) {
            endpointStore?.save(firstAdmitted)
        } else {
            endpointStore?.clear()
        }
    }

    if (relayAccessDecoded != null) {
        val home = PairedHome(
            instanceId = pairResponse.instanceId,
            homeLabel = pairResponse.homeLabel,
            relayOrigin = relayAccessDecoded.relayOrigin,
            caChainFingerprint = "sha256:" + sha256Hex(caDer),
            clientCertFingerprint = "sha256:" + sha256Hex(clientDer),
            observerHandle = null,
            deviceToken = relayAccessDecoded.deviceToken,
            expiresAt = relayAccessDecoded.expiresAt,
            state = IdentityState.PAIRED,
        )
        publishPairing(home, credential, credentialStore, identityStore, mutator)
        saveLocalEndpoints()

        coordinator?.onUsableConnection(home.instanceId, home.caChainFingerprint, home.clientCertFingerprint) {
            openRelaySyncClient(relayAccessDecoded.relayOrigin, pairResponse.instanceId, relayAccessDecoded.deviceToken, credential)
        }
        relayAccessCoordinator?.onUsableConnection(home.instanceId, home.caChainFingerprint, home.clientCertFingerprint) {
            openRelaySyncClient(relayAccessDecoded.relayOrigin, pairResponse.instanceId, relayAccessDecoded.deviceToken, credential)
        }

        return RelayPairResult(
            handshakePinned = true,
            pairStatus = pairStatus,
            enrollStatus = null,
            homeLabel = pairResponse.homeLabel,
            relayOrigin = relayOrigin,
            relayHost = relayHost,
            connectionMode = connectionMode,
        )
    }

    // Omitted relay_access: publish first, then transitional enroll
    val homeInitial = PairedHome(
        instanceId = pairResponse.instanceId,
        homeLabel = pairResponse.homeLabel,
        relayOrigin = null,
        caChainFingerprint = "sha256:" + sha256Hex(caDer),
        clientCertFingerprint = "sha256:" + sha256Hex(clientDer),
        observerHandle = null,
        deviceToken = null,
        expiresAt = null,
        state = IdentityState.PAIRED,
    )
    publishPairing(homeInitial, credential, credentialStore, identityStore, mutator)
    saveLocalEndpoints()

    val enrollAccess = mutator?.accessSnapshot()
    var enrollStatus: Int? = null
    try {
        val enrollBody = toJson(
            mapOf(
                "instance_id" to pairResponse.instanceId,
                "home_attestation" to pairResponse.homeAttestation,
                "protocol_version" to 2,
            ),
        ).toByteArray(Charsets.UTF_8)
        val enrollHttp = httpsPoster.post("${origin.httpsBase}/enroll/device", enrollBody, JSON_HEADERS)
        enrollStatus = enrollHttp.status
        if (enrollHttp.status == 200) {
            val root = runCatching { parseJson(enrollHttp.bodyText()) as? Map<*, *> }.getOrNull()
            if (root != null) {
                val parsedAccess: Pair<String, String?>? = run {
                    val status = root["status"] as? String
                    if (status != null) {
                        when (val access = parseRelayAccessMap(root, pairResponse.instanceId)) {
                            is RelayAccessResponse.Ready -> {
                                val parsedOrigin = parseProductionRelayOrigin(access.relayOrigin)
                                if (parsedOrigin != null &&
                                    parsedOrigin.scheme == origin.scheme &&
                                    parsedOrigin.host == origin.host &&
                                    parsedOrigin.effectivePort == origin.effectivePort
                                ) {
                                    Pair(access.deviceToken, access.expiresAt)
                                } else null
                            }
                            else -> null
                        }
                    } else {
                        val replacement = parseRelayTokenReplacement(root, pairResponse.instanceId, false, System.currentTimeMillis())
                            ?: return@run null
                        Pair(replacement.token, replacement.expiresAt)
                    }
                }

                if (parsedAccess != null) {
                    val (deviceToken, expiresAt) = parsedAccess
                    val updatedHome = homeInitial.copy(
                        relayOrigin = origin.httpsBase,
                        deviceToken = deviceToken,
                        expiresAt = expiresAt,
                    )
                    if (mutator != null) {
                        val expected = enrollAccess ?: return RelayPairResult(true, pairStatus, enrollStatus, pairResponse.homeLabel, relayOrigin, relayHost, connectionMode)
                        val result = mutator.mutateIfCurrent(expected) { p ->
                            p.copy(relayOrigin = updatedHome.relayOrigin, deviceToken = updatedHome.deviceToken, expiresAt = updatedHome.expiresAt)
                        }
                        if (result !is app.solstone.core.identity.AccessMutationResult.Applied) throw IOException("relay control request failed")
                    } else {
                        if (identityStore.load() != homeInitial) throw IOException("relay control request failed")
                        identityStore.save(updatedHome)
                    }

                    coordinator?.onUsableConnection(updatedHome.instanceId, updatedHome.caChainFingerprint, updatedHome.clientCertFingerprint) {
                        openRelaySyncClient(origin.httpsBase, pairResponse.instanceId, deviceToken, credential)
                    }
                    relayAccessCoordinator?.onUsableConnection(updatedHome.instanceId, updatedHome.caChainFingerprint, updatedHome.clientCertFingerprint) {
                        openRelaySyncClient(origin.httpsBase, pairResponse.instanceId, deviceToken, credential)
                    }
                }
            }
        }
    } catch (_: Exception) {
        // Transitional enroll failed: stores keep new pairing with null relay token
    }

    return RelayPairResult(
        handshakePinned = true,
        pairStatus = pairStatus,
        enrollStatus = enrollStatus,
        homeLabel = pairResponse.homeLabel,
        relayOrigin = relayOrigin,
        relayHost = relayHost,
        connectionMode = connectionMode,
    )
}

fun relayPairEndpoint(link: RelayPairLink): DirectEndpoint {
    val origin = parseProductionRelayOrigin(link.relayOrigin ?: DEFAULT_RELAY_ORIGIN)
        ?: throw java.io.IOException("relay origin invalid")
    return DirectEndpoint(origin.host, origin.effectivePort)
}

private const val RELAY_SYNC_WAITING_TIMEOUT_MS = 30_000L

fun openRelaySyncClient(
    relayOrigin: String,
    instanceId: String,
    deviceToken: String,
    credential: ClientCredential,
    streamObserver: PlStreamObserver? = null,
    dialObserver: RelayDialObserver? = null,
): ConscryptPlHttpClient {
    val origin = parseProductionRelayOrigin(relayOrigin) ?: error("invalid relay origin: $relayOrigin")
    val request = relayWebSocketRequest(origin, "/session/dial", instanceId, deviceToken)
    return openRelayClient(
        origin.host,
        origin.effectivePort,
        OkHttpTunnelOpener(
            OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", SPL_USER_AGENT)
                            .build(),
                    )
                }
                .build(),
            request,
            RELAY_SYNC_WAITING_TIMEOUT_MS,
        ),
        RelayTlsMode.Authenticated(credential),
        streamObserver,
        dialObserver,
    ).client
}

internal fun relayWebSocketRequest(origin: RelayOrigin, path: String, instanceId: String, token: String): Request =
    relayWebSocketRequest(origin.wssBase, path, instanceId, token)

internal fun relayWebSocketRequest(wssBase: String, path: String, instanceId: String, token: String): Request {
    val cleanPath = if (path.startsWith("/")) path else "/$path"
    return Request.Builder()
        .url("$wssBase$cleanPath?instance=$instanceId&token=$token")
        .header("User-Agent", SPL_USER_AGENT)
        .build()
}

internal fun relayPairDialRequest(origin: RelayOrigin, rk: ByteArray): Request =
    relayPairDialRequest(origin.wssBase, rk)

internal fun relayPairDialRequest(wssBase: String, rk: ByteArray): Request {
    return Request.Builder()
        .url("$wssBase/session/pair-dial")
        .header("User-Agent", SPL_USER_AGENT)
        .header("Sec-Pair-Key", hex(rk))
        .build()
}

internal fun normalizeRelayOrigin(relayOrigin: String): String = relayOrigin.trimEnd('/')

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
internal const val DEFAULT_RELAY_ORIGIN = "https://link.solstone.app"

