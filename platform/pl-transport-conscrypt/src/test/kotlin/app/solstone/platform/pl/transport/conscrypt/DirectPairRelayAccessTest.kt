// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.crypto.certificateFromPem
import app.solstone.core.crypto.sha256
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.ByteDuplex
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.FLAG_CLOSE
import app.solstone.core.pl.FLAG_DATA
import app.solstone.core.pl.FLAG_OPEN
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.encodeFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DirectPairRelayAccessTest {
    @Test
    fun directPairWithReadyBootstrapAttachesRelayOriginAndDeviceToken() {
        val credStore = FakeCredentialStore()
        val identStore = FakeIdentityStore()
        val endpStore = FakeEndpointStore()
        val bootstrapToken = v2Jwt("test-instance", 100, 2000000000)
        val responseJson = pairResponseWithRelayAccess(
            relayAccessJson = """{"protocol_version":2,"status":"ready","relay_origin":"https://link.solstone.app","instance_id":"test-instance","device_token":"$bootstrapToken","expires_at":"2033-05-18T03:33:20Z"}""",
        )

        val result = pairAndProbe(
            pairLink = validPairLink(),
            deviceLabel = "test phone",
            credentialStore = credStore,
            identityStore = identStore,
            endpointStore = endpStore,
            sessionOpener = { _, _ -> CertlessSession(app.solstone.core.pl.MuxSession(responseDuplex(200, responseJson)), true) },
            localInterfaces = emptyList(),
            materialFactory = { DirectPairMaterial("KEY", leafPublicKey(), "CSR".toByteArray()) },
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(200, result.pairStatus)
        assertEquals(DirectPairConnectionMode.PAIRING, result.connectionMode)
        val home = assertNotNull(identStore.load())
        assertEquals("https://link.solstone.app", home.relayOrigin)
        assertEquals(bootstrapToken, home.deviceToken)
    }

    @Test
    fun directPairWithOmittedBootstrapSucceedsWithNullRelayFields() {
        val credStore = FakeCredentialStore()
        val identStore = FakeIdentityStore()
        val endpStore = FakeEndpointStore()
        val responseJson = pairResponseWithRelayAccess(relayAccessJson = null)

        val result = pairAndProbe(
            pairLink = validPairLink(),
            deviceLabel = "test phone",
            credentialStore = credStore,
            identityStore = identStore,
            endpointStore = endpStore,
            sessionOpener = { _, _ -> CertlessSession(app.solstone.core.pl.MuxSession(responseDuplex(200, responseJson)), true) },
            localInterfaces = emptyList(),
            materialFactory = { DirectPairMaterial("KEY", leafPublicKey(), "CSR".toByteArray()) },
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(200, result.pairStatus)
        val home = assertNotNull(identStore.load())
        assertNull(home.relayOrigin)
        assertNull(home.deviceToken)
    }

    @Test
    fun directPairWithPresentJsonNullDoesNotFailPairing() {
        val credStore = FakeCredentialStore()
        val identStore = FakeIdentityStore()
        val endpStore = FakeEndpointStore()
        val responseJson = pairResponseWithRelayAccess(relayAccessJson = "null")

        val result = pairAndProbe(
            pairLink = validPairLink(),
            deviceLabel = "test phone",
            credentialStore = credStore,
            identityStore = identStore,
            endpointStore = endpStore,
            sessionOpener = { _, _ -> CertlessSession(app.solstone.core.pl.MuxSession(responseDuplex(200, responseJson)), true) },
            localInterfaces = emptyList(),
            materialFactory = { DirectPairMaterial("KEY", leafPublicKey(), "CSR".toByteArray()) },
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(200, result.pairStatus)
        val home = assertNotNull(identStore.load())
        assertNull(home.relayOrigin)
        assertNull(home.deviceToken)
    }

    @Test
    fun directPairWithUnknownStatusOrVersionDoesNotFailPairing() {
        val credStore = FakeCredentialStore()
        val identStore = FakeIdentityStore()
        val endpStore = FakeEndpointStore()
        val responseJson = pairResponseWithRelayAccess(
            relayAccessJson = """{"protocol_version":99,"status":"unknown_status","relay_origin":"https://link.solstone.app","instance_id":"test-instance","device_token":"tok"}""",
        )

        val result = pairAndProbe(
            pairLink = validPairLink(),
            deviceLabel = "test phone",
            credentialStore = credStore,
            identityStore = identStore,
            endpointStore = endpStore,
            sessionOpener = { _, _ -> CertlessSession(app.solstone.core.pl.MuxSession(responseDuplex(200, responseJson)), true) },
            localInterfaces = emptyList(),
            materialFactory = { DirectPairMaterial("KEY", leafPublicKey(), "CSR".toByteArray()) },
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(200, result.pairStatus)
        val home = assertNotNull(identStore.load())
        assertNull(home.relayOrigin)
        assertNull(home.deviceToken)
    }

    @Test
    fun directPairWithExpiredReadyJwtDoesNotFailPairing() {
        val credStore = FakeCredentialStore()
        val identStore = FakeIdentityStore()
        val endpStore = FakeEndpointStore()
        val expiredToken = v2Jwt("test-instance", 100, 200)
        val responseJson = pairResponseWithRelayAccess(
            relayAccessJson = """{"protocol_version":2,"status":"ready","relay_origin":"https://link.solstone.app","instance_id":"test-instance","device_token":"$expiredToken","expires_at":"1970-01-01T00:03:20Z"}""",
        )

        val result = pairAndProbe(
            pairLink = validPairLink(),
            deviceLabel = "test phone",
            credentialStore = credStore,
            identityStore = identStore,
            endpointStore = endpStore,
            sessionOpener = { _, _ -> CertlessSession(app.solstone.core.pl.MuxSession(responseDuplex(200, responseJson)), true) },
            localInterfaces = emptyList(),
            materialFactory = { DirectPairMaterial("KEY", leafPublicKey(), "CSR".toByteArray()) },
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(200, result.pairStatus)
        val home = assertNotNull(identStore.load())
        assertNull(home.relayOrigin)
        assertNull(home.deviceToken)
    }

    @Test
    fun directPairWithNonDefaultProductionOriginAttachesOriginAndToken() {
        val credStore = FakeCredentialStore()
        val identStore = FakeIdentityStore()
        val endpStore = FakeEndpointStore()
        val bootstrapToken = v2Jwt("test-instance", 100, 2000000000)
        val responseJson = pairResponseWithRelayAccess(
            relayAccessJson = """{"protocol_version":2,"status":"ready","relay_origin":"https://custom-relay.solstone.app:8443","instance_id":"test-instance","device_token":"$bootstrapToken","expires_at":"2033-05-18T03:33:20Z"}""",
        )

        val result = pairAndProbe(
            pairLink = validPairLink(),
            deviceLabel = "test phone",
            credentialStore = credStore,
            identityStore = identStore,
            endpointStore = endpStore,
            sessionOpener = { _, _ -> CertlessSession(app.solstone.core.pl.MuxSession(responseDuplex(200, responseJson)), true) },
            localInterfaces = emptyList(),
            materialFactory = { DirectPairMaterial("KEY", leafPublicKey(), "CSR".toByteArray()) },
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(200, result.pairStatus)
        val home = assertNotNull(identStore.load())
        assertEquals("https://custom-relay.solstone.app:8443", home.relayOrigin)
        assertEquals(bootstrapToken, home.deviceToken)
    }

    @Test
    fun directPairWithMalformedBootstrapDoesNotFailPairing() {
        val credStore = FakeCredentialStore()
        val identStore = FakeIdentityStore()
        val endpStore = FakeEndpointStore()
        val responseJson = pairResponseWithRelayAccess(relayAccessJson = """{"invalid":"shape"}""")

        val result = pairAndProbe(
            pairLink = validPairLink(),
            deviceLabel = "test phone",
            credentialStore = credStore,
            identityStore = identStore,
            endpointStore = endpStore,
            sessionOpener = { _, _ -> CertlessSession(app.solstone.core.pl.MuxSession(responseDuplex(200, responseJson)), true) },
            localInterfaces = emptyList(),
            materialFactory = { DirectPairMaterial("KEY", leafPublicKey(), "CSR".toByteArray()) },
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(200, result.pairStatus)
        val home = assertNotNull(identStore.load())
        assertNull(home.relayOrigin)
        assertNull(home.deviceToken)
    }

    @Test
    fun directPairSameHomeWithReadyBootstrapMutatesPriorHome() {
        val priorHome = PairedHome(
            instanceId = "test-instance",
            homeLabel = "My Home",
            relayOrigin = null,
            caChainFingerprint = "sha256:" + sha256Hex(app.solstone.core.crypto.pemToDer(PAIR_TEST_CA_PEM, "CERTIFICATE")),
            clientCertFingerprint = "sha256:" + sha256Hex(certificateFromPem(PAIR_TEST_LEAF_PEM).encoded),
            observerHandle = null,
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val credStore = FakeCredentialStore(ClientCredential("KEY", PAIR_TEST_LEAF_PEM, listOf(PAIR_TEST_CA_PEM)))
        val identStore = FakeIdentityStore(priorHome)
        val endpStore = FakeEndpointStore()
        val mutator = FakeMutator(identStore)
        val bootstrapToken = v2Jwt("test-instance", 100, 2000000000)
        val responseJson = pairResponseWithRelayAccess(
            relayAccessJson = """{"protocol_version":2,"status":"ready","relay_origin":"https://link.solstone.app","instance_id":"test-instance","device_token":"$bootstrapToken","expires_at":"2033-05-18T03:33:20Z"}""",
        )

        val result = pairAndProbe(
            pairLink = validPairLink(),
            deviceLabel = "test phone",
            credentialStore = credStore,
            identityStore = identStore,
            endpointStore = endpStore,
            mutator = mutator,
            sessionOpener = { _, _ -> CertlessSession(app.solstone.core.pl.MuxSession(responseDuplex(200, responseJson)), true) },
            localInterfaces = emptyList(),
            materialFactory = { DirectPairMaterial("KEY", leafPublicKey(), "CSR".toByteArray()) },
            statusProbe = { _, _ -> HttpResponse(200, emptyMap(), "ok".toByteArray()) },
        )

        assertEquals(DirectPairConnectionMode.ALREADY_CONNECTED, result.connectionMode)
        val updatedHome = assertNotNull(identStore.load())
        assertEquals("https://link.solstone.app", updatedHome.relayOrigin)
        assertEquals(bootstrapToken, updatedHome.deviceToken)
    }

    private fun leafPublicKey(): ByteArray =
        certificateFromPem(PAIR_TEST_LEAF_PEM).publicKey.encoded

    private fun validPairLink(): String {
        val caPrefix = sha256(app.solstone.core.crypto.pemToDer(PAIR_TEST_CA_PEM, "CERTIFICATE")).copyOf(16)
        val ip = byteArrayOf(10, 0, 0, 2)
        val bytes = ByteArray(37 + 4)
        bytes[0] = 0x05
        bytes[1] = 0x01
        bytes[2] = 1
        bytes[3] = (7657 shr 8).toByte()
        bytes[4] = (7657 and 0xff).toByte()
        ip.copyInto(bytes, 5)
        ByteArray(16) { 0x01 }.copyInto(bytes, 9)
        caPrefix.copyInto(bytes, 25)
        return "https://go.solstone.app/p#${encodeCrockford(bytes)}"
    }

    private fun pairResponseWithRelayAccess(
        fingerprint: String = "sha256:" + sha256Hex(certificateFromPem(PAIR_TEST_LEAF_PEM).encoded),
        relayAccessJson: String? = null,
    ): String =
        """
        {
          "ca_chain":[${app.solstone.core.pl.toJson(PAIR_TEST_CA_PEM)}],
          "client_cert":${app.solstone.core.pl.toJson(PAIR_TEST_LEAF_PEM)},
          "instance_id":"test-instance",
          "home_label":"test-home",
          "home_attestation":"attestation.jwt",
          "fingerprint":"$fingerprint"${if (relayAccessJson != null) ",\n  \"relay_access\":$relayAccessJson" else ""}
        }
        """.trimIndent()

    private fun responseDuplex(status: Int, body: String): ByteDuplex {
        val reason = if (status == 200) "OK" else "Error"
        val response = "HTTP/1.1 $status $reason\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"
            .toByteArray(Charsets.UTF_8)
        return object : ByteDuplex {
            override val input: InputStream = ByteArrayInputStream(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, response))
            override val output: OutputStream = ByteArrayOutputStream()
            override fun close() = Unit
        }
    }

    private fun encodeCrockford(bytes: ByteArray): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val output = StringBuilder()
        var buffer = 0
        var bits = 0
        bytes.forEach { raw ->
            buffer = (buffer shl 8) or (raw.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                output.append(alphabet[(buffer shr bits) and 31])
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (bits > 0) output.append(alphabet[(buffer shl (5 - bits)) and 31])
        return output.toString()
    }

    private fun v2Jwt(instanceId: String, iat: Long, exp: Long): String {
        val payload = """{"iss":"https://link.solstone.app","sub":"instance:$instanceId","aud":"spl-relay","scope":"session.dial","ver":2,"instance_id":"$instanceId","iat":$iat,"exp":$exp,"jti":"test-jti"}"""
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString(payload.toByteArray())}.sig"
    }

    private class FakeCredentialStore(private var cred: ClientCredential? = null) : ClientCredentialStore {
        override fun save(credential: ClientCredential) { cred = credential }
        override fun load(): ClientCredential? = cred
        override fun clear() { cred = null }
    }

    private class FakeIdentityStore(private var home: PairedHome? = null) : IdentityStore {
        override fun save(home: PairedHome) { this.home = home }
        override fun load(): PairedHome? = home
        override fun clear() { home = null }
    }

    private class FakeEndpointStore(private var ep: DirectEndpoint? = null) : EndpointStore {
        override fun save(endpoint: DirectEndpoint) { ep = endpoint }
        override fun load(): DirectEndpoint? = ep
        override fun clear() { ep = null }
    }

    private class FakeMutator(private val store: FakeIdentityStore) : IdentityMutator {
        private var accessGen = 1L
        override fun current(): PairedHome? = store.load()
        override fun currentPairingGeneration(): PairingGeneration? {
            val h = store.load() ?: return null
            return PairingGeneration(h.instanceId, h.clientCertFingerprint)
        }
        override fun currentAccessMutationGen(): Long = accessGen
        override fun isRelayLiveEligible(): Boolean = true
        override fun disableRelayLive() {}
        override fun lastPersistenceIssue(): app.solstone.core.identity.PersistenceIssue? = null
        override fun installNewPairing(home: PairedHome): Boolean {
            store.save(home)
            return true
        }
        override fun mutate(
            expectedPairing: PairingGeneration,
            expectedAccessMutationGen: Long,
            transform: (PairedHome) -> PairedHome,
        ): AccessMutationResult {
            val h = store.load() ?: return AccessMutationResult.Conflict("missing home")
            val next = transform(h)
            store.save(next)
            accessGen++
            return AccessMutationResult.Applied(next, accessGen)
        }
    }
}
