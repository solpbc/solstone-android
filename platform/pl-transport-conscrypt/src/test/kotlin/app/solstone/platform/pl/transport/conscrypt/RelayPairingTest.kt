// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.caSpkiFp16
import app.solstone.core.crypto.CaPinException
import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.generateP256KeyPair
import app.solstone.core.crypto.hex
import app.solstone.core.crypto.jidFromCaPem
import app.solstone.core.crypto.pem
import app.solstone.core.crypto.pemToDer
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.parseProductionRelayOrigin
import java.io.IOException
import java.security.KeyPair
import java.security.Signature
import java.util.Base64
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayPairingTest {
    @Test
    fun nullRelayOriginUsesNormalizedDefaultEndpoint() {
        val endpoint = relayPairEndpoint(RelayPairLink(ByteArray(8), ByteArray(16), null))

        assertEquals("link.solstone.app", endpoint.host)
        assertEquals(443, endpoint.port)
    }

    @Test
    fun happyPathWithReadyBootstrapPersistsDirectlyWithoutEnroll() {
        val stores = Stores()
        val bootstrapToken = v2Jwt(INSTANCE_ID, 100, 2000000000)
        val relayAccessJson = """{"protocol_version":2,"status":"ready","relay_origin":"https://link.solstone.app","instance_id":"$INSTANCE_ID","device_token":"$bootstrapToken","expires_at":"2033-05-18T03:33:20Z"}"""
        val session = DynamicFakeSession(relayAccessJson = relayAccessJson)
        val poster = FakePoster(session)

        val result = pairOverRelay(
            link(),
            "glasses",
            poster,
            FakeDialer(session),
            stores.credentialStore,
            stores.identityStore,
            endpointStore = stores.endpointStore,
        )

        assertEquals(200, result.pairStatus)
        assertNull(result.enrollStatus)
        assertEquals("relay-home", result.homeLabel)
        assertEquals("link.solstone.app", result.relayHost)
        assertEquals(RelayPairConnectionMode.PAIRING, result.connectionMode)
        assertEquals(0, poster.enrollBodies.size)
        assertTrue(session.closed)

        val credential = assertNotNull(stores.credentialStore.load())
        assertContains(credential.clientCertPem, "BEGIN CERTIFICATE")
        val home = assertNotNull(stores.identityStore.load())
        assertEquals(INSTANCE_ID, home.instanceId)
        assertEquals("https://link.solstone.app", home.relayOrigin)
        assertEquals(bootstrapToken, home.deviceToken)
        assertEquals(DirectEndpoint("10.0.0.5", 7657), stores.endpointStore.load())
    }

    @Test
    fun happyPathOmittedBootstrapPerformsTransitionalEnroll() {
        val stores = Stores()
        val session = DynamicFakeSession(relayAccessJson = null)
        val poster = FakePoster(session)
        val result = pairOverRelay(
            link(),
            "glasses",
            poster,
            FakeDialer(session),
            stores.credentialStore,
            stores.identityStore,
            endpointStore = stores.endpointStore,
        )

        assertEquals(200, result.pairStatus)
        assertEquals(200, result.enrollStatus)
        assertEquals("relay-home", result.homeLabel)
        assertEquals("link.solstone.app", result.relayHost)
        assertEquals(RelayPairConnectionMode.PAIRING, result.connectionMode)
        assertEquals("/app/network/pair?token=0123456789abcdef", session.requests.single().path)
        val pairBody = parseJson(session.requests.single().body!!.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals("glasses", pairBody["device_label"])
        assertTrue((pairBody["csr"] as String).contains("BEGIN CERTIFICATE REQUEST"))
        val enrollBody = parseJson(poster.enrollBodies.single().toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals(setOf("instance_id", "home_attestation", "protocol_version"), enrollBody.keys)
        assertEquals(INSTANCE_ID, enrollBody["instance_id"])
        assertEquals("attestation.jwt", enrollBody["home_attestation"])
        assertEquals(2, (enrollBody["protocol_version"] as Number).toInt())
        assertTrue(poster.sessionClosedBeforeEnroll)

        val credential = assertNotNull(stores.credentialStore.load())
        assertContains(credential.clientCertPem, "BEGIN CERTIFICATE")
        val home = assertNotNull(stores.identityStore.load())
        assertEquals(INSTANCE_ID, home.instanceId)
        assertEquals("https://link.solstone.app", home.relayOrigin)
        val expectedToken = v2Jwt(INSTANCE_ID, 100, 2000000000)
        assertEquals(expectedToken, home.deviceToken)
    }

    @Test
    fun omittedBootstrapEnroll200WithGarbageTokenLeavesStoresWithPairingAndNullTokens() {
        val stores = Stores()
        val session = DynamicFakeSession(relayAccessJson = null)
        val poster = FakePoster(session, enrollStatus = 200, enrollResponseBody = """{"device_token":"garbage.invalid.token"}""")

        val result = pairOverRelay(
            link(),
            "device",
            poster,
            FakeDialer(session),
            stores.credentialStore,
            stores.identityStore,
            endpointStore = stores.endpointStore,
        )

        assertEquals(200, result.pairStatus)
        assertEquals(200, result.enrollStatus)
        val credential = assertNotNull(stores.credentialStore.load())
        assertContains(credential.clientCertPem, "BEGIN CERTIFICATE")
        val home = assertNotNull(stores.identityStore.load())
        assertEquals(INSTANCE_ID, home.instanceId)
        assertNull(home.relayOrigin)
        assertNull(home.deviceToken)
        assertNull(home.expiresAt)
        assertEquals(DirectEndpoint("10.0.0.5", 7657), stores.endpointStore.load())
    }

    @Test
    fun relayWebSocketRequestAndPairDialRequestIncludePortAndBracketedIpv6() {
        val originHostPort = parseProductionRelayOrigin("https://link.solstone.app:8443")!!
        val wsReq = relayWebSocketRequest(originHostPort, "/session/dial", "inst-1", "tok-1")
        assertEquals("https://link.solstone.app:8443/session/dial?instance=inst-1&token=tok-1", wsReq.url.toString())
        assertEquals(8443, wsReq.url.port)
        assertEquals("spl", wsReq.header("User-Agent"))

        val originIpv6 = parseProductionRelayOrigin("https://[2001:db8::1]:8443")!!
        val wsIpv6Req = relayWebSocketRequest(originIpv6, "/session/dial", "inst-1", "tok-1")
        assertEquals("https://[2001:db8::1]:8443/session/dial?instance=inst-1&token=tok-1", wsIpv6Req.url.toString())
        assertEquals(8443, wsIpv6Req.url.port)
        assertEquals("spl", wsIpv6Req.header("User-Agent"))

        val pairDialReq = relayPairDialRequest(originHostPort, byteArrayOf(0x01, 0x02, 0x03))
        assertEquals("https://link.solstone.app:8443/session/pair-dial", pairDialReq.url.toString())
        assertEquals(8443, pairDialReq.url.port)
        assertEquals("spl", pairDialReq.header("User-Agent"))
        assertEquals("010203", pairDialReq.header("Sec-Pair-Key"))

        val pairDialIpv6Req = relayPairDialRequest(originIpv6, byteArrayOf(0x01, 0x02, 0x03))
        assertEquals("https://[2001:db8::1]:8443/session/pair-dial", pairDialIpv6Req.url.toString())
        assertEquals(8443, pairDialIpv6Req.url.port)
        assertEquals("spl", pairDialIpv6Req.header("User-Agent"))
    }

    @Test
    fun omittedBootstrapEnrollFailureKeepsPairingInStores() {
        val stores = Stores()
        val session = DynamicFakeSession(relayAccessJson = null)
        val poster = FakePoster(session, enrollStatus = 503)

        val result = pairOverRelay(
            link(),
            "device",
            poster,
            FakeDialer(session),
            stores.credentialStore,
            stores.identityStore,
            endpointStore = stores.endpointStore,
        )

        assertEquals(200, result.pairStatus)
        assertEquals(503, result.enrollStatus)
        val credential = assertNotNull(stores.credentialStore.load())
        assertContains(credential.clientCertPem, "BEGIN CERTIFICATE")
        val home = assertNotNull(stores.identityStore.load())
        assertEquals(INSTANCE_ID, home.instanceId)
        assertNull(home.deviceToken)
    }

    @Test
    fun clientCertificatePublicKeyMismatchThrowsAndLeavesStoresEmpty() {
        val stores = Stores()
        val session = DynamicFakeSession(mismatchPublicKey = true)

        val error = assertFailsWith<IOException> {
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }

        assertEquals("pair response client certificate key mismatch", error.message)
        assertNull(stores.credentialStore.load())
        assertNull(stores.identityStore.load())
    }

    @Test
    fun samePairedInstanceWithInvalidBootstrapThrowsBootstrapInvalid() {
        val prior = pairedHome(label = "existing-home")
        val stores = Stores(prior)
        val session = DynamicFakeSession(relayAccessJson = """{"invalid":"shape"}""")

        val error = assertFailsWith<IOException> {
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore, mutator = stores.mutator)
        }

        assertEquals("relay pair bootstrap invalid", error.message)
        assertEquals(prior, stores.identityStore.load())
    }

    @Test
    fun samePairedInstanceWithReadyBootstrapMutatesAndReturnsAlreadyConnected() {
        val prior = pairedHome(label = "existing-home")
        val stores = Stores(prior)
        val bootstrapToken = v2Jwt(INSTANCE_ID, 100, 2000000000)
        val relayAccessJson = """{"protocol_version":2,"status":"ready","relay_origin":"https://link.solstone.app","instance_id":"$INSTANCE_ID","device_token":"$bootstrapToken","expires_at":"2033-05-18T03:33:20Z"}"""
        val session = DynamicFakeSession(relayAccessJson = relayAccessJson)
        val poster = FakePoster(session)

        val result = pairOverRelay(
            link(),
            "device",
            poster,
            FakeDialer(session),
            stores.credentialStore,
            stores.identityStore,
            mutator = stores.mutator,
        )

        assertEquals(RelayPairConnectionMode.ALREADY_CONNECTED, result.connectionMode)
        assertNull(result.enrollStatus)
        assertEquals("existing-home", result.homeLabel)
        assertTrue(session.closed)
        assertEquals(0, poster.enrollBodies.size)
        val updatedHome = assertNotNull(stores.identityStore.load())
        assertEquals("https://link.solstone.app", updatedHome.relayOrigin)
        assertEquals(bootstrapToken, updatedHome.deviceToken)
    }

    @Test
    fun samePairedInstanceWithoutBootstrapReturnsAlreadyConnected() {
        val prior = pairedHome(label = "existing-home")
        val stores = Stores(prior)
        val session = DynamicFakeSession(relayAccessJson = null)
        val poster = FakePoster(session)

        val result = pairOverRelay(
            link(),
            "device",
            poster,
            FakeDialer(session),
            stores.credentialStore,
            stores.identityStore,
        )

        assertEquals(RelayPairConnectionMode.ALREADY_CONNECTED, result.connectionMode)
        assertNull(result.enrollStatus)
        assertEquals("existing-home", result.homeLabel)
        assertTrue(session.closed)
        assertEquals(0, poster.enrollBodies.size)
        assertEquals(prior, stores.identityStore.load())
    }

    @Test
    fun prePersistFailuresLeaveStoresEmpty() {
        assertNoPersist("pair non-200") { stores ->
            val session = DynamicFakeSession(customStatus = 503)
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("pin mismatch") { stores ->
            val session = DynamicFakeSession()
            val wrongPin = CA_FP.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
            pairOverRelay(link(caFp = wrongPin), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("instance mismatch") { stores ->
            val session = DynamicFakeSession(customInstanceId = "00000000-0000-0000-0000-000000000000")
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("fingerprint mismatch") { stores ->
            val session = DynamicFakeSession(customFingerprint = "sha256:nope")
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("window closed") { stores ->
            pairOverRelay(
                link(),
                "device",
                FakePoster(),
                FakeDialer(openException = RelayPairWindowUnavailableException(401)),
                stores.credentialStore,
                stores.identityStore,
            )
        }
    }

    @Test
    fun mapsPairDial401ToWindowClosedMessage() {
        val stores = Stores()

        val error = assertFailsWith<RelayPairWindowClosedException> {
            pairOverRelay(
                link(),
                "device",
                FakePoster(),
                FakeDialer(openException = RelayPairWindowUnavailableException(401)),
                stores.credentialStore,
                stores.identityStore,
            )
        }

        assertEquals(
            "The pairing window is closed, expired, or was already used. Ask for a fresh pair link and try again.",
            error.message,
        )
        assertNull(stores.credentialStore.load())
        assertNull(stores.identityStore.load())
    }

    @Test
    fun non401PairDialUpgradeFailureIsNotMappedToWindowClosedMessage() {
        val stores = Stores()

        val error = assertFailsWith<RelayPairWindowUnavailableException> {
            pairOverRelay(
                link(),
                "device",
                FakePoster(),
                FakeDialer(openException = RelayPairWindowUnavailableException(500)),
                stores.credentialStore,
                stores.identityStore,
            )
        }

        assertEquals(500, error.statusCode)
        assertNotEquals(
            "The pairing window is closed, expired, or was already used. Ask for a fresh pair link and try again.",
            error.message,
        )
        assertNull(stores.credentialStore.load())
        assertNull(stores.identityStore.load())
    }

    @Test
    fun matchingCaAndSignedLeafSendsPairRequest() {
        val stores = Stores()
        val session = DynamicFakeSession()

        pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)

        assertEquals("/app/network/pair?token=0123456789abcdef", session.requests.single().path)
    }

    @Test
    fun noMatchingCaAbortsBeforeSendingS() {
        val stores = Stores()
        val session = DynamicFakeSession(
            customPeerChain = listOf(certificateFromPem(TEST_SERVER_LEAF_PEM).encoded),
        )

        assertFailsWith<CaPinException> {
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }

        assertTrue(session.requests.isEmpty())
        assertTrue(session.closed)
    }

    @Test
    fun emptyChainAbortsBeforeSendingS() {
        val stores = Stores()
        val session = DynamicFakeSession(customPeerChain = emptyList())

        assertFailsWith<CaPinException> {
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }

        assertTrue(session.requests.isEmpty())
        assertTrue(session.closed)
    }

    @Test
    fun wrongLeafAbortsBeforeSendingS() {
        val stores = Stores()
        val session = DynamicFakeSession(
            customPeerLeaf = certificateFromPem(TEST_RELAY_UNRELATED_PEM).encoded,
            customPeerChain = listOf(
                certificateFromPem(TEST_RELAY_UNRELATED_PEM).encoded,
                certificateFromPem(TEST_CA_PEM).encoded,
            ),
        )

        assertFailsWith<CaPinException> {
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }

        assertTrue(session.requests.isEmpty())
        assertTrue(session.closed)
    }

    private fun assertNoPersist(label: String, block: (Stores) -> Unit) {
        val stores = Stores()
        assertFailsWith<Exception>(label) { block(stores) }
        assertNull(stores.credentialStore.load(), label)
        assertNull(stores.identityStore.load(), label)
    }

    private fun link(
        caFp: ByteArray = CA_FP,
        relayOrigin: String? = null,
    ): RelayPairLink =
        RelayPairLink(hexBytes("0123456789abcdef"), caFp, relayOrigin)

    private class FakePoster(
        private val session: DynamicFakeSession? = null,
        private val enrollStatus: Int = 200,
        private val enrollResponseBody: String? = null,
    ) : HttpsPoster {
        val enrollBodies = mutableListOf<ByteArray>()
        var sessionClosedBeforeEnroll = false

        override fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse {
            assertEquals("application/json", headers["content-type"])
            return when {
                url.endsWith("/enroll/device") -> {
                    enrollBodies += body
                    sessionClosedBeforeEnroll = session?.closed == true
                    val validToken = v2Jwt(INSTANCE_ID, 100, 2000000000)
                    val responseText = enrollResponseBody ?: """{"device_token":"$validToken","expires_at":"2033-05-18T03:33:20Z"}"""
                    HttpResponse(enrollStatus, emptyMap(), responseText.toByteArray())
                }
                else -> error("unexpected URL $url")
            }
        }
    }

    private class FakeDialer(
        private val session: DynamicFakeSession = DynamicFakeSession(),
        private val openException: Exception? = null,
    ) : RelayPairDialer {
        override fun open(host: String, port: Int, rk: ByteArray): RelayDialSession {
            openException?.let { throw it }
            assertEquals("link.solstone.app", host)
            assertEquals(443, port)
            assertEquals("e34481a4cde647ba9c9fb29a59e18271", hex(rk))
            return session
        }
    }

    private class DynamicFakeSession(
        private val relayAccessJson: String? = null,
        private val mismatchPublicKey: Boolean = false,
        private val customStatus: Int = 200,
        private val customInstanceId: String? = null,
        private val customFingerprint: String? = null,
        private val customPeerLeaf: ByteArray? = null,
        private val customPeerChain: List<ByteArray>? = null,
    ) : RelayDialSession {
        val requests = mutableListOf<RequestRecord>()
        var closed = false

        override val peerLeafCertificateDer: ByteArray?
            get() = customPeerLeaf ?: certificateFromPem(TEST_SERVER_LEAF_PEM).encoded

        override val peerCertificateChainDer: List<ByteArray>?
            get() = customPeerChain ?: listOf(
                certificateFromPem(TEST_SERVER_LEAF_PEM).encoded,
                certificateFromPem(TEST_CA_PEM).encoded,
            )

        override val client: PlHttpClient = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
                maxResponseBytes: Int,
            ): HttpResponse {
                requests += RequestRecord(method, path, headers, body)
                if (customStatus != 200) {
                    return HttpResponse(customStatus, emptyMap(), "error".toByteArray())
                }
                val bodyStr = body?.toString(Charsets.UTF_8).orEmpty()
                val parsed = parseJson(bodyStr) as? Map<*, *>
                val csrPem = parsed?.get("csr") as? String
                val spkiBytes = if (mismatchPublicKey) {
                    generateP256KeyPair().public.encoded
                } else if (csrPem != null) {
                    extractPublicKeyFromCsrPem(csrPem)
                } else {
                    generateP256KeyPair().public.encoded
                }
                val clientCertPem = issueLeafCertPem(spkiBytes, TEST_CA_KEY_PAIR)
                val certDer = certificateFromPem(clientCertPem).encoded
                val fingerprint = customFingerprint ?: ("sha256:" + sha256Hex(certDer))
                val instanceId = customInstanceId ?: INSTANCE_ID

                val responseJson = """
                {
                  "ca_chain":[${app.solstone.core.pl.toJson(TEST_CA_PEM)}],
                  "client_cert":${app.solstone.core.pl.toJson(clientCertPem)},
                  "instance_id":"$instanceId",
                  "home_label":"relay-home",
                  "home_attestation":"attestation.jwt",
                  "fingerprint":"$fingerprint",
                  "local_endpoints":[{"ip":"10.0.0.5","port":7657}]${if (relayAccessJson != null) ",\n  \"relay_access\":$relayAccessJson" else ""}
                }
                """.trimIndent()
                return HttpResponse(200, emptyMap(), responseJson.toByteArray(Charsets.UTF_8))
            }
        }

        override fun close() {
            closed = true
        }
    }

    private data class RequestRecord(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
    )

    private class Stores(home: PairedHome? = null) {
        val credentialStore = FakeCredentialStore()
        val identityStore = FakeIdentityStore(home)
        val endpointStore = FakeEndpointStore()
        val mutator = object : IdentityMutator {
            private var accessGen = 1L
            override fun current(): PairedHome? = identityStore.load()
            override fun currentPairingGeneration(): PairingGeneration? {
                val h = identityStore.load() ?: return null
                return PairingGeneration(h.instanceId, h.clientCertFingerprint)
            }
            override fun currentAccessMutationGen(): Long = accessGen
            override fun isRelayLiveEligible(): Boolean = true
            override fun disableRelayLive() {}
            override fun installNewPairing(home: PairedHome): Boolean {
                identityStore.save(home)
                return true
            }
            override fun mutate(
                expectedPairing: PairingGeneration,
                expectedAccessMutationGen: Long,
                transform: (PairedHome) -> PairedHome,
            ): AccessMutationResult {
                val h = identityStore.load() ?: return AccessMutationResult.Conflict("missing home")
                val next = transform(h)
                identityStore.save(next)
                accessGen++
                return AccessMutationResult.Applied(next, accessGen)
            }
        }
    }

    private class FakeCredentialStore : ClientCredentialStore {
        private var credential: ClientCredential? = null
        var saves = 0
        override fun save(credential: ClientCredential) {
            saves += 1
            this.credential = credential
        }
        override fun load(): ClientCredential? = credential
        override fun clear() {
            credential = null
        }
    }

    private class FakeIdentityStore(private var home: PairedHome?) : IdentityStore {
        var saves = 0
        override fun save(home: PairedHome) {
            saves += 1
            this.home = home
        }
        override fun load(): PairedHome? = home
        override fun clear() {
            home = null
        }
    }

    private class FakeEndpointStore : EndpointStore {
        private var ep: DirectEndpoint? = null
        override fun save(endpoint: DirectEndpoint) {
            this.ep = endpoint
        }
        override fun load(): DirectEndpoint? = ep
        override fun clear() {
            ep = null
        }
    }

    companion object {
        private const val ECDSA_WITH_SHA256_OID_HEX = "06082a8648ce3d040302"
        private val ECDSA_OID_BYTES = hexBytes(ECDSA_WITH_SHA256_OID_HEX)

        val TEST_CA_KEY_PAIR: KeyPair = generateP256KeyPair()
        val TEST_CA_PEM: String = issueCaCertPem(TEST_CA_KEY_PAIR)
        val CA_FP: ByteArray = caSpkiFp16(TEST_CA_PEM)
        val INSTANCE_ID: String = jidFromCaPem(TEST_CA_PEM)

        val TEST_SERVER_KEY_PAIR: KeyPair = generateP256KeyPair()
        val TEST_SERVER_LEAF_PEM: String = issueLeafCertPem(TEST_SERVER_KEY_PAIR.public.encoded, TEST_CA_KEY_PAIR, "relay-server")

        fun issueCaCertPem(caKeyPair: KeyPair, cn: String = "solstone-test-ca"): String {
            val tbs = der(
                0x30,
                concat(
                    der(0xa0, der(0x02, byteArrayOf(0x02))),
                    der(0x02, byteArrayOf(0x01)),
                    der(0x30, ECDSA_OID_BYTES),
                    X500Principal("CN=$cn").encoded,
                    der(0x30, concat(der(0x17, "260101000000Z".toByteArray()), der(0x17, "360101000000Z".toByteArray()))),
                    X500Principal("CN=$cn").encoded,
                    caKeyPair.public.encoded,
                    der(0xa3, der(0x30, der(0x30, concat(
                        byteArrayOf(0x06, 0x03, 0x55, 0x1d, 0x13),
                        der(0x01, byteArrayOf(0xff.toByte())),
                        der(0x04, der(0x30, der(0x01, byteArrayOf(0xff.toByte()))))
                    )))),
                ),
            )
            val sig = Signature.getInstance("SHA256withECDSA").apply {
                initSign(caKeyPair.private)
                update(tbs)
            }.sign()
            val certDer = der(0x30, concat(tbs, der(0x30, ECDSA_OID_BYTES), der(0x03, concat(byteArrayOf(0x00), sig))))
            return pem("CERTIFICATE", certDer)
        }

        fun issueLeafCertPem(spkiDer: ByteArray, caKeyPair: KeyPair, cn: String = "relay-leaf"): String {
            val tbs = der(
                0x30,
                concat(
                    der(0xa0, der(0x02, byteArrayOf(0x02))),
                    der(0x02, byteArrayOf(0x01)),
                    der(0x30, ECDSA_OID_BYTES),
                    X500Principal("CN=solstone-test-ca").encoded,
                    der(0x30, concat(der(0x17, "260101000000Z".toByteArray()), der(0x17, "360101000000Z".toByteArray()))),
                    X500Principal("CN=$cn").encoded,
                    spkiDer,
                ),
            )
            val sig = Signature.getInstance("SHA256withECDSA").apply {
                initSign(caKeyPair.private)
                update(tbs)
            }.sign()
            val certDer = der(0x30, concat(tbs, der(0x30, ECDSA_OID_BYTES), der(0x03, concat(byteArrayOf(0x00), sig))))
            return pem("CERTIFICATE", certDer)
        }

        fun extractPublicKeyFromCsrPem(csrPem: String): ByteArray {
            val der = pemToDer(csrPem, "CERTIFICATE REQUEST")
            val reader = DerTlvReader(der)
            val root = reader.readTlv()
            val rootChildren = DerTlvReader(root.content).readAll()
            val cri = rootChildren[0]
            val criChildren = DerTlvReader(cri.content).readAll()
            val spki = criChildren[2]
            return spki.fullBytes
        }

        fun v2Jwt(instanceId: String, iat: Long, exp: Long): String {
            val payload = """{"iss":"https://link.solstone.app","sub":"instance:$instanceId","aud":"spl-relay","scope":"session.dial","ver":2,"instance_id":"$instanceId","iat":$iat,"exp":$exp,"jti":"test-jti"}"""
            val enc = Base64.getUrlEncoder().withoutPadding()
            return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString(payload.toByteArray())}.sig"
        }
    }
}

private fun der(tag: Int, content: ByteArray): ByteArray {
    val lenBytes = if (content.size < 128) {
        byteArrayOf(content.size.toByte())
    } else if (content.size < 256) {
        byteArrayOf(0x81.toByte(), content.size.toByte())
    } else {
        byteArrayOf(0x82.toByte(), (content.size shr 8).toByte(), (content.size and 0xff).toByte())
    }
    val out = ByteArray(1 + lenBytes.size + content.size)
    out[0] = tag.toByte()
    System.arraycopy(lenBytes, 0, out, 1, lenBytes.size)
    System.arraycopy(content, 0, out, 1 + lenBytes.size, content.size)
    return out
}

private fun concat(vararg parts: ByteArray): ByteArray {
    var total = 0
    for (p in parts) total += p.size
    val out = ByteArray(total)
    var offset = 0
    for (p in parts) {
        System.arraycopy(p, 0, out, offset, p.size)
        offset += p.size
    }
    return out
}

private class DerTlv(val tag: Int, val fullBytes: ByteArray, val content: ByteArray)

private class DerTlvReader(private val bytes: ByteArray) {
    private var index = 0

    fun readAll(): List<DerTlv> {
        val out = ArrayList<DerTlv>()
        while (index < bytes.size) {
            out += readTlv()
        }
        return out
    }

    fun readTlv(): DerTlv {
        val start = index
        val tag = bytes[index++].toInt() and 0xff
        var length = bytes[index++].toInt() and 0xff
        if ((length and 0x80) != 0) {
            val count = length and 0x7f
            length = 0
            repeat(count) {
                length = (length shl 8) or (bytes[index++].toInt() and 0xff)
            }
        }
        val contentStart = index
        index += length
        return DerTlv(tag, bytes.copyOfRange(start, index), bytes.copyOfRange(contentStart, index))
    }
}

private fun pairedHome(
    label: String = "relay-home",
    state: IdentityState = IdentityState.PAIRED,
): PairedHome =
    PairedHome(
        instanceId = RelayPairingTest.INSTANCE_ID,
        homeLabel = label,
        relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:ca",
        clientCertFingerprint = "sha256:client",
        observerHandle = null,
        deviceToken = "old-token",
        expiresAt = null,
        state = state,
    )

private fun hexBytes(value: String): ByteArray {
    val out = ByteArray(value.length / 2)
    for (index in out.indices) {
        out[index] = value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    return out
}

private const val TEST_RELAY_UNRELATED_PEM = """-----BEGIN CERTIFICATE-----
MIIBfTCCASOgAwIBAgIUNEpHEWWe11eXwZt02t864KjecoIwCgYIKoZIzj0EAwIw
FDESMBAGA1UEAwwJdW5yZWxhdGVkMB4XDTI2MDYyNjA2NTY1OVoXDTM2MDYyMzA2
NTY1OVowFDESMBAGA1UEAwwJdW5yZWxhdGVkMFkwEwYHKoZIzj0CAQYIKoZIzj0D
AQcDQgAE4reGbY4kAE3L6wmSS+a4RMYllEgptK61VYmNWGlsv/PnspbdOdzqQV9s
xvm6Uz9PYK3V4m9ZipOsgauzk2JUg6NTMFEwHQYDVR0OBBYEFN2k2e7axl/ov3P1
KsV7tkzP7/IXMB8GA1UdIwQYMBaAFN2k2e7axl/ov3P1KsV7tkzP7/IXMA8GA1Ud
EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIhAJEYjZlTip4Pcp60CUS+WYRB
a0nECXUP0fXOHAMWG6ZTAiB2VYdwJmKWrWzgLamJ6ZJU604ItE6KJ4rsYrsO+wVd
Gw==
-----END CERTIFICATE-----
"""
