// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.caSpkiFp16
import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.parseJson
import java.io.Closeable
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals("/app/network/pair?token=0123456789abcdef0123456789abcdef", session.requests.single().path)
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
        assertNoPersist("ticket non-200") { stores ->
            pairOverRelay(link(), "device", FakePoster(ticketStatus = 503), FakeDialer(), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("pair non-200") { stores ->
            val session = FakeSession(HttpResponse(503, emptyMap(), "no".toByteArray()), certificateFromPem(TEST_RELAY_LEAF_PEM).encoded)
            pairOverRelay(link(), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
        }
        assertNoPersist("ca tag") { stores ->
            val session = FakeSession(HttpResponse(200, emptyMap(), pairResponse().toByteArray()), certificateFromPem(TEST_RELAY_LEAF_PEM).encoded)
            pairOverRelay(link(caFpTag = 2), "device", FakePoster(session), FakeDialer(session), stores.credentialStore, stores.identityStore)
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
    }

    private fun assertNoPersist(label: String, block: (Stores) -> Unit) {
        val stores = Stores()
        assertFailsWith<Exception>(label) { block(stores) }
        assertNull(stores.credentialStore.load(), label)
        assertNull(stores.identityStore.load(), label)
    }

    private fun link(
        caFpTag: Int = 1,
        caFp: ByteArray = caSpkiFp16(TEST_RELAY_CA_PEM),
        relayOrigin: String? = null,
    ): RelayPairLink =
        RelayPairLink(INSTANCE_ID, "123456", "0123456789abcdef0123456789abcdef", caFpTag, caFp, relayOrigin)

    private fun pairResponse(
        instanceId: String = INSTANCE_ID,
        fingerprint: String = "sha256:" + sha256Hex(certificateFromPem(TEST_RELAY_LEAF_PEM).encoded),
    ): String =
        """
        {
          "ca_chain":[${jsonString(TEST_RELAY_CA_PEM)}],
          "client_cert":${jsonString(TEST_RELAY_LEAF_PEM)},
          "instance_id":"$instanceId",
          "home_label":"relay-home",
          "home_attestation":"attestation.jwt",
          "fingerprint":"$fingerprint"
        }
        """.trimIndent()

    private fun jsonString(value: String): String = app.solstone.core.pl.toJson(value)

    private class FakePoster(
        private val session: FakeSession? = null,
        private val ticketStatus: Int = 200,
        private val enrollStatus: Int = 200,
    ) : HttpsPoster {
        val enrollBodies = mutableListOf<ByteArray>()
        var sessionClosedBeforeEnroll = false

        override fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse {
            assertEquals("application/json", headers["content-type"])
            return when {
                url.contains("/session/pair-ticket") -> HttpResponse(ticketStatus, emptyMap(), """{"pair_ticket":"ticket-1"}""".toByteArray())
                url.endsWith("/enroll/device") -> {
                    enrollBodies += body
                    sessionClosedBeforeEnroll = session?.closed == true
                    HttpResponse(enrollStatus, emptyMap(), """{"device_token":"mock-device-token"}""".toByteArray())
                }
                else -> error("unexpected URL $url")
            }
        }
    }

    private class FakeDialer(private val session: FakeSession = FakeSession()) : RelayPairDialer {
        override fun open(host: String, port: Int, instanceId: String, pairTicket: String): RelayDialSession {
            assertEquals("link.solstone.app", host)
            assertEquals(443, port)
            assertEquals(INSTANCE_ID, instanceId)
            assertEquals("ticket-1", pairTicket)
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

    private class Stores {
        val credentialStore = object : ClientCredentialStore {
            private var credential: ClientCredential? = null
            override fun save(credential: ClientCredential) {
                this.credential = credential
            }
            override fun load(): ClientCredential? = credential
            override fun clear() {
                credential = null
            }
        }
        val identityStore = object : IdentityStore {
            private var home: PairedHome? = null
            override fun save(home: PairedHome) {
                this.home = home
            }
            override fun load(): PairedHome? = home
            override fun clear() {
                home = null
            }
        }
    }

    private companion object {
        const val INSTANCE_ID = "12345678-1234-5678-1234-567812345678"

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
