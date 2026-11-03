// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TlsDecisionsTest {
    private val rootDer = certificateFromPem(TEST_ROOT_CERT_PEM).encoded
    private val rootJid = jidFromCaPem(TEST_ROOT_CERT_PEM)

    @Test
    fun aDirectPairNamingThePinnedCasIdPasses() {
        assertDirectPairIdentity(rootJid, TEST_ROOT_CERT_PEM, rootDer)
    }

    @Test
    fun aDirectPairNamingAnyOtherIdIsRefused() {
        assertFailsWith<CaPinException> {
            assertDirectPairIdentity("f30ed159-ef46-8e9c-913f-e49f0fe7d201", TEST_ROOT_CERT_PEM, rootDer)
        }
    }

    // A forger holds a real journal's public key, because every door handshake shows it. Without
    // its private key, the forger can't make a certificate of that key that signs itself.
    @Test
    fun aCertificateOfAnotherJournalsKeyThatDoesNotSignItselfIsRefused() {
        val clientDer = certificateFromPem(TEST_CLIENT_CERT_PEM).encoded
        val claimed = jidFromSpkiDerOrNull(clientDer)
        assertFailsWith<CaPinException> {
            assertDirectPairIdentity(claimed ?: rootJid, TEST_CLIENT_CERT_PEM, clientDer)
        }
    }

    @Test
    fun aChainThatIsNotThePinnedCertificateIsRefused() {
        assertFailsWith<CaPinException> {
            assertDirectPairIdentity(rootJid, TEST_RELAY_UNRELATED_PEM, rootDer)
        }
    }

    @Test
    fun pinnedBytesWithAnythingAfterTheCertificateAreRefused() {
        assertFailsWith<CaPinException> {
            assertDirectPairIdentity(rootJid, TEST_ROOT_CERT_PEM, rootDer + byteArrayOf(0))
        }
    }

    @Test
    fun aChainThatCannotBeReadIsRefused() {
        assertFailsWith<CaPinException> {
            assertDirectPairIdentity(rootJid, "not a certificate", rootDer)
        }
    }

    @Test
    fun theHandshakesLeafHasToChainToTheCertificateByItsSignature() {
        val signedLeaf = certificateFromPem(TEST_CLIENT_CERT_PEM).encoded
        val otherLeaf = certificateFromPem(TEST_RELAY_UNRELATED_PEM).encoded

        assertDirectPairIdentity(rootJid, TEST_ROOT_CERT_PEM, rootDer, signedLeaf)
        assertFailsWith<CaPinException> {
            assertDirectPairIdentity(rootJid, TEST_ROOT_CERT_PEM, rootDer, otherLeaf)
        }
    }

    @Test
    fun anIdIsCheckedAgainstAStoredCaAndAnUnreadableCaSaysNothing() {
        assertEquals(true, instanceIdMatchesCa(rootJid, TEST_ROOT_CERT_PEM))
        assertEquals(false, instanceIdMatchesCa("f30ed159-ef46-8e9c-913f-e49f0fe7d201", TEST_ROOT_CERT_PEM))
        assertEquals(null, instanceIdMatchesCa(rootJid, "not a certificate"))
    }

    private fun jidFromSpkiDerOrNull(certificateDer: ByteArray): String? =
        runCatching { jidFromSpkiDer(certificateFromDer(certificateDer).publicKey.encoded) }.getOrNull()

    @Test
    fun assertDirectCaPinAcceptsLeafSignedByPinnedCa() {
        val clientDer = certificateFromPem(TEST_CLIENT_CERT_PEM).encoded
        val rootDer = certificateFromPem(TEST_ROOT_CERT_PEM).encoded

        assertDirectCaPin(listOf(clientDer, rootDer), sha256(rootDer).copyOf(16))
    }

    @Test
    fun assertDirectCaPinRejectsLeafNotSignedByPinnedCa() {
        val unrelatedDer = certificateFromPem(TEST_RELAY_UNRELATED_PEM).encoded
        val rootDer = certificateFromPem(TEST_ROOT_CERT_PEM).encoded

        assertFailsWith<CaPinException> {
            assertDirectCaPin(listOf(unrelatedDer, rootDer), sha256(rootDer).copyOf(16))
        }
    }

    @Test
    fun assertDirectCaPinRejectsChainWithNoPrefixMatch() {
        val clientDer = certificateFromPem(TEST_CLIENT_CERT_PEM).encoded
        val rootDer = certificateFromPem(TEST_ROOT_CERT_PEM).encoded
        val unrelatedDer = certificateFromPem(TEST_RELAY_UNRELATED_PEM).encoded

        assertFailsWith<CaPinException> {
            assertDirectCaPin(listOf(clientDer, rootDer), sha256(unrelatedDer).copyOf(16))
        }
    }

    @Test
    fun assertDirectCaPinRejectsPinnedNonSelfSignedCert() {
        val clientDer = certificateFromPem(TEST_CLIENT_CERT_PEM).encoded

        assertFailsWith<CaPinException> {
            assertDirectCaPin(listOf(clientDer), sha256(clientDer).copyOf(16))
        }
    }

    @Test
    fun assertDirectCaPinRejectsEmptyChain() {
        val rootDer = certificateFromPem(TEST_ROOT_CERT_PEM).encoded

        assertFailsWith<CaPinException> {
            assertDirectCaPin(emptyList(), sha256(rootDer).copyOf(16))
        }
    }

    @Test
    fun requireTls13ReturnsOnlyTls13() {
        assertEquals(listOf("TLSv1.3"), requireTls13(arrayOf("TLSv1.3", "TLSv1.2")).toList())
    }

    @Test
    fun requireTls13ThrowsWhenUnsupported() {
        assertFailsWith<SSLException> {
            requireTls13(arrayOf("TLSv1.2", "TLSv1.1"))
        }
    }
}

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
