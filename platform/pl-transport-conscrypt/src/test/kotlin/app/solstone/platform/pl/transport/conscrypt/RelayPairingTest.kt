// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.caSpkiFp16
import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.hex
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.parseJson
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
    fun happyPathPersistsAfterEnrollAndClosesSessionBeforeEnroll() {
        val stores = Stores()
        val session = FakeSession(
            HttpResponse(200, emptyMap(), pairResponse().toByteArray(Charsets.UTF_8)),
            certificateFromPem(TEST_RELAY_LEAF_PEM).encoded,
        )
        val poster = FakePoster(session)
        val result = pairOverRelay(
            link(),
            "glasses",
            poster,
            FakeDialer(session),
            stores.credentialStore,
            stores.identityStore,
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
        assertEquals(setOf("instance_id", "home_attestation"), enrollBody.keys)
        assertEquals(INSTANCE_ID, enrollBody["instance_id"])
        assertEquals("attestation.jwt", enrollBody["home_attestation"])
        assertTrue(poster.sessionClosedBeforeEnroll)

        val credential = assertNotNull(stores.credentialStore.load())
        assertContains(credential.clientCertPem, "BEGIN CERTIFICATE")
        val home = assertNotNull(stores.identityStore.load())
        assertEquals(INSTANCE_ID, home.instanceId)
        assertEquals("https://link.solstone.app", home.relayOrigin)
        assertEquals("mock-device-token", home.deviceToken)
        assertEquals("sha256:" + sha256Hex(certificateFromPem(TEST_RELAY_LEAF_PEM).encoded), home.clientCertFingerprint)
    }

    @Test
    fun prePersistFailuresLeaveStoresEmpty() {
        assertNoPersist("pair non-200") { stores ->
            val session = FakeSession(HttpResponse(503, emptyMap(), "no".toByteArray()), certificateFromPem(TEST_RELAY_LEAF_PEM).encoded)
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("pin mismatch") { stores ->
            val session = FakeSession(HttpResponse(200, emptyMap(), pairResponse().toByteArray()), certificateFromPem(TEST_RELAY_LEAF_PEM).encoded)
            val wrongPin = caSpkiFp16(TEST_RELAY_CA_PEM).also { it[0] = (it[0].toInt() xor 0xff).toByte() }
            pairOverRelay(link(caFp = wrongPin), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("instance mismatch") { stores ->
            val session = FakeSession(
                HttpResponse(200, emptyMap(), pairResponse(instanceId = "00000000-0000-0000-0000-000000000000").toByteArray()),
                certificateFromPem(TEST_RELAY_LEAF_PEM).encoded,
            )
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("fingerprint mismatch") { stores ->
            val session = FakeSession(
                HttpResponse(200, emptyMap(), pairResponse(fingerprint = "sha256:nope").toByteArray()),
                certificateFromPem(TEST_RELAY_LEAF_PEM).encoded,
            )
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("enroll non-200") { stores ->
            val session = FakeSession(HttpResponse(200, emptyMap(), pairResponse().toByteArray()), certificateFromPem(TEST_RELAY_LEAF_PEM).encoded)
            pairOverRelay(link(), "device", FakePoster(session, enrollStatus = 503), FakeDialer(session), stores.credentialStore, stores.identityStore)
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
    fun samePairedInstanceReturnsAlreadyConnectedWithoutEnrollOrPersist() {
        val prior = pairedHome(label = "existing-home")
        val stores = Stores(prior)
        val session = FakeSession(
            HttpResponse(200, emptyMap(), pairResponse(homeLabel = "relay-home").toByteArray(Charsets.UTF_8)),
            certificateFromPem(TEST_RELAY_LEAF_PEM).encoded,
        )
        val poster = FakePoster(session)

        val result = pairOverRelay(link(), "device", poster, FakeDialer(session), stores.credentialStore, stores.identityStore)

        assertEquals(RelayPairConnectionMode.ALREADY_CONNECTED, result.connectionMode)
        assertEquals(200, result.enrollStatus)
        assertEquals("existing-home", result.homeLabel)
        assertTrue(session.closed)
        assertEquals(0, poster.enrollBodies.size)
        assertEquals(0, stores.credentialStore.saves)
        assertEquals(0, stores.identityStore.saves)
        assertEquals(prior, stores.identityStore.load())
    }

    @Test
    fun sameNonPairedInstancePersistsAndMarksReconnecting() {
        val stores = Stores(pairedHome(state = IdentityState.REVOKED))
        val session = FakeSession(
            HttpResponse(200, emptyMap(), pairResponse().toByteArray(Charsets.UTF_8)),
            certificateFromPem(TEST_RELAY_LEAF_PEM).encoded,
        )

        val result = pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)

        assertEquals(RelayPairConnectionMode.RECONNECTING, result.connectionMode)
        assertEquals(1, stores.credentialStore.saves)
        assertEquals(1, stores.identityStore.saves)
        assertEquals(IdentityState.PAIRED, stores.identityStore.load()?.state)
    }

    @Test
    fun noPriorInstancePersistsAndMarksPairing() {
        val stores = Stores()
        val session = FakeSession(
            HttpResponse(200, emptyMap(), pairResponse().toByteArray(Charsets.UTF_8)),
            certificateFromPem(TEST_RELAY_LEAF_PEM).encoded,
        )

        val result = pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)

        assertEquals(RelayPairConnectionMode.PAIRING, result.connectionMode)
        assertEquals(1, stores.credentialStore.saves)
        assertEquals(1, stores.identityStore.saves)
    }

    private fun assertNoPersist(label: String, block: (Stores) -> Unit) {
        val stores = Stores()
        assertFailsWith<Exception>(label) { block(stores) }
        assertNull(stores.credentialStore.load(), label)
        assertNull(stores.identityStore.load(), label)
    }

    private fun link(
        caFp: ByteArray = caSpkiFp16(TEST_RELAY_CA_PEM),
        relayOrigin: String? = null,
    ): RelayPairLink =
        RelayPairLink(hexBytes("0123456789abcdef"), caFp, relayOrigin)

    private fun pairResponse(
        instanceId: String = INSTANCE_ID,
        homeLabel: String = "relay-home",
        fingerprint: String = "sha256:" + sha256Hex(certificateFromPem(TEST_RELAY_LEAF_PEM).encoded),
    ): String =
        """
        {
          "ca_chain":[${jsonString(TEST_RELAY_CA_PEM)}],
          "client_cert":${jsonString(TEST_RELAY_LEAF_PEM)},
          "instance_id":"$instanceId",
          "home_label":"$homeLabel",
          "home_attestation":"attestation.jwt",
          "fingerprint":"$fingerprint"
        }
        """.trimIndent()

    private fun jsonString(value: String): String = app.solstone.core.pl.toJson(value)

    private class FakePoster(
        private val session: FakeSession? = null,
        private val enrollStatus: Int = 200,
    ) : HttpsPoster {
        val enrollBodies = mutableListOf<ByteArray>()
        var sessionClosedBeforeEnroll = false

        override fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse {
            assertEquals("application/json", headers["content-type"])
            return when {
                url.endsWith("/enroll/device") -> {
                    enrollBodies += body
                    sessionClosedBeforeEnroll = session?.closed == true
                    HttpResponse(enrollStatus, emptyMap(), """{"device_token":"mock-device-token"}""".toByteArray())
                }
                else -> error("unexpected URL $url")
            }
        }
    }

    private class FakeDialer(
        private val session: FakeSession = FakeSession(),
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

    private class FakeSession(
        private val response: HttpResponse = HttpResponse(200, emptyMap(), pairResponseStatic().toByteArray()),
        override val peerLeafCertificateDer: ByteArray? = certificateFromPem(TEST_RELAY_LEAF_PEM).encoded,
    ) : RelayDialSession {
        val requests = mutableListOf<RequestRecord>()
        var closed = false
        override val client: PlHttpClient = object : PlHttpClient {
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
                requests += RequestRecord(method, path, headers, body)
                return response
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

    companion object {
        const val INSTANCE_ID = "2c04a888-b4bc-842e-98eb-3954d2460d47"

        fun pairResponseStatic(): String =
            """
            {
              "ca_chain":[${app.solstone.core.pl.toJson(TEST_RELAY_CA_PEM)}],
              "client_cert":${app.solstone.core.pl.toJson(TEST_RELAY_LEAF_PEM)},
              "instance_id":"$INSTANCE_ID",
              "home_label":"relay-home",
              "home_attestation":"attestation.jwt",
              "fingerprint":"sha256:${sha256Hex(certificateFromPem(TEST_RELAY_LEAF_PEM).encoded)}"
            }
            """.trimIndent()
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

// Test-only ephemeral certificates generated with openssl; not operational secrets.
private const val TEST_RELAY_CA_PEM = """-----BEGIN CERTIFICATE-----
MIIBlzCCAT2gAwIBAgIUPPGWUZjdtzsgVEHE+ZtQg5pKb9EwCgYIKoZIzj0EAwIw
ITEfMB0GA1UEAwwWc29sc3RvbmUtdGVzdC1yZWxheS1jYTAeFw0yNjA2MjYwNjU2
NTlaFw0zNjA2MjMwNjU2NTlaMCExHzAdBgNVBAMMFnNvbHN0b25lLXRlc3QtcmVs
YXktY2EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQDxnjvJGXgJKz1k6hS+OCN
o8Z8Sau6KmLagTIlXdP1yS9vrFOSJmE3ds6qBiMS+mmmgPEMVLXW7YWnPlx25sIx
o1MwUTAdBgNVHQ4EFgQU5JS7pR98gG5FFWBaFQG1CyU+HqgwHwYDVR0jBBgwFoAU
5JS7pR98gG5FFWBaFQG1CyU+HqgwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQD
AgNIADBFAiBAi7BjxrJ8n15io2V8KADdUBDBAntAkEcSxaOeLSULdgIhAKN9CYVy
NtBHSCAhLQyKBI0u6Prh4F9sXuD0c1GST5cL
-----END CERTIFICATE-----
"""

private const val TEST_RELAY_LEAF_PEM = """-----BEGIN CERTIFICATE-----
MIIBeTCCASCgAwIBAgIUMglsPRCiAWU5lfqkbDyAcT584bcwCgYIKoZIzj0EAwIw
ITEfMB0GA1UEAwwWc29sc3RvbmUtdGVzdC1yZWxheS1jYTAeFw0yNjA2MjYwNjU2
NTlaFw0zNjA2MjMwNjU2NTlaMBUxEzARBgNVBAMMCnJlbGF5LWxlYWYwWTATBgcq
hkjOPQIBBggqhkjOPQMBBwNCAAQb0GVwcFN5cygcjoyh9PlmgbsT7+gwtK2zx0XQ
hLYlDiZDvtKVl8CsEsIApDOeFKJN1MtSEMSf4kPFPq2V4h9co0IwQDAdBgNVHQ4E
FgQUVMag2GDGNZBZyJQxAPY+GyNNxy0wHwYDVR0jBBgwFoAU5JS7pR98gG5FFWBa
FQG1CyU+HqgwCgYIKoZIzj0EAwIDRwAwRAIgIMOGzfr8PMdgd8GpuqSEAW3TZXe9
Vym9fT1BLkht+X0CIH3KLzz0foTyo+huJpyZpDUHbT3beeeWFhGhRkv9DjDv
-----END CERTIFICATE-----
"""
