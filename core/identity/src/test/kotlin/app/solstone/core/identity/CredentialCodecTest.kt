// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.security.interfaces.ECPrivateKey
import javax.net.ssl.X509KeyManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CredentialCodecTest {
    @Test
    fun credentialRoundTripsPemAndBuildsKeyManagers() {
        val credential = ClientCredential(TEST_PRIVATE_KEY_PEM, TEST_CLIENT_CERT_PEM, listOf(TEST_ROOT_CERT_PEM))
        val decoded = CredentialCodec.decode(credential)

        assertTrue(decoded.privateKey is ECPrivateKey)
        assertEquals(TEST_CLIENT_CERT_PEM, CredentialCodec.encode(decoded.privateKey, decoded.clientCert, decoded.caChain).clientCertPem)
        assertEquals(TEST_PRIVATE_KEY_PEM, CredentialCodec.encode(decoded.privateKey, decoded.clientCert, decoded.caChain).privateKeyPem)
        assertEquals(listOf(TEST_ROOT_CERT_PEM), CredentialCodec.encode(decoded.privateKey, decoded.clientCert, decoded.caChain).caChainPem)

        val keyManager = CredentialCodec.keyManagers(credential).filterIsInstance<X509KeyManager>().single()
        val aliases = keyManager.getClientAliases("EC", null)
        assertNotNull(aliases)
        assertTrue("client" in aliases)
        assertTrue(keyManager.getPrivateKey("client") is ECPrivateKey)
        assertTrue(keyManager.getCertificateChain("client").isNotEmpty())
    }

    @Test
    fun pairedHomeModelConstructs() {
        val home = PairedHome(
            instanceId = "inst",
            homeLabel = "home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = null,
            deviceToken = null,
            state = IdentityState.PAIRED,
        )
        assertEquals(IdentityState.PAIRED, home.state)
    }
}

private const val TEST_PRIVATE_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg+qMFOOvzSEoSsmnF
1C4hRkGycvgh6JYUc+E0clyh8RyhRANCAAQbqfHWrZacqrd8shfL9DAF/QLEyQcd
A+ck4mg5niVnRf4i1CwTu7+ZI0jzKoc1/uNS3nwgN43qD+ZRSOzlQTfm
-----END PRIVATE KEY-----
"""

private const val TEST_CLIENT_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBgDCCASagAwIBAgIUPHnu1UmhAJQEWvnkfnhJysFbhvMwCgYIKoZIzj0EAwIw
HTEbMBkGA1UEAwwSc29sc3RvbmUtdGVzdC1yb290MB4XDTI2MDYxNjIwMTgyN1oX
DTM2MDYxMzIwMTgyN1owHzEdMBsGA1UEAwwUc29sc3RvbmUtdGVzdC1jbGllbnQw
WTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQbqfHWrZacqrd8shfL9DAF/QLEyQcd
A+ck4mg5niVnRf4i1CwTu7+ZI0jzKoc1/uNS3nwgN43qD+ZRSOzlQTfmo0IwQDAd
BgNVHQ4EFgQUH6ADnRjFChv7N3ETgd+zYI2ThCUwHwYDVR0jBBgwFoAU/MNAu41T
MZkbAIOVdCN4RBHOi+QwCgYIKoZIzj0EAwIDSAAwRQIgLD/R7i5e/wZ0djNR4uz6
+9OhQ/YaEeg/9+PwE4AblE4CIQDvxF8TolKPOT1Aud4GvqSPC93WjY9nRsaNJeL8
RssZUQ==
-----END CERTIFICATE-----
"""

private const val TEST_ROOT_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBjjCCATWgAwIBAgIUSUGOs8jjRKxvugydDhY6NaOCITswCgYIKoZIzj0EAwIw
HTEbMBkGA1UEAwwSc29sc3RvbmUtdGVzdC1yb290MB4XDTI2MDYxNjIwMTgyN1oX
DTM2MDYxMzIwMTgyN1owHTEbMBkGA1UEAwwSc29sc3RvbmUtdGVzdC1yb290MFkw
EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEYtpHGllY66cH2hexTmmaWdO9GQfk48y9
hhl6IqHmMgtw3xMPiGCcQKIjZQmOrFWZMtBkxsmpF7IUJ3clVTLs3aNTMFEwHQYD
VR0OBBYEFPzDQLuNUzGZGwCDlXQjeEQRzovkMB8GA1UdIwQYMBaAFPzDQLuNUzGZ
GwCDlXQjeEQRzovkMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDRwAwRAIg
V9jU1ex9tPW047hf3YcLaUCVtEi7sCzJN1tWm+Ao9AMCIEsE+l+FIGFYPmUDZOgJ
myy/KG7HMOZ3GDzOlcdOZGHs
-----END CERTIFICATE-----
"""
