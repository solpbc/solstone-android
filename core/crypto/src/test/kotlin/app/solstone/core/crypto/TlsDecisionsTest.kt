// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TlsDecisionsTest {
    @Test
    fun chainMatchesPrefixWhenCaIsNotLeaf() {
        val clientDer = certificateFromPem(TEST_CLIENT_CERT_PEM).encoded
        val rootDer = certificateFromPem(TEST_ROOT_CERT_PEM).encoded
        val prefix = sha256(rootDer).copyOf(4)

        assertTrue(chainMatchesPrefix(listOf(clientDer, rootDer), prefix))
    }

    @Test
    fun chainDoesNotMatchAbsentCa() {
        val clientDer = certificateFromPem(TEST_CLIENT_CERT_PEM).encoded
        val rootDer = certificateFromPem(TEST_ROOT_CERT_PEM).encoded
        val prefix = sha256(rootDer).copyOf(4)

        assertFalse(chainMatchesPrefix(listOf(clientDer), prefix))
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
