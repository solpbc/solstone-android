// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PemTest {
    @Test
    fun pemWrapsAtSixtyFourColumnsAndRoundTrips() {
        val der = ByteArray(80) { it.toByte() }
        val encoded = pem("TEST", der)
        val lines = encoded.lines()
        assertEquals("-----BEGIN TEST-----", lines[0])
        assertEquals(64, lines[1].length)
        assertTrue(lines[2].isNotEmpty())
        assertEquals("-----END TEST-----", lines[3])
        assertTrue(encoded.endsWith("\n"))
        assertContentEquals(der, pemToDer(encoded, "TEST"))
    }

    @Test
    fun keyAndCertificatePemDecodeToOriginalDer() {
        val key = privateKeyFromPem(TEST_PRIVATE_KEY_PEM)
        val cert = certificateFromPem(TEST_CERT_PEM)
        assertEquals(TEST_PRIVATE_KEY_PEM, pem("PRIVATE KEY", key.encoded))
        assertEquals(TEST_CERT_PEM, pem("CERTIFICATE", cert.encoded))
    }
}

internal const val TEST_PRIVATE_KEY_PEM = """-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg3AkWVkg53r67hj8L
uwGLQESvV+mBnJthSxeLAwSwhs+hRANCAASBTe0bOF36qZ5UDu5oRG/ap5uexhdP
+s/TWeexTbbuOtIOtzHP9Q+cuCdyap8IVFH9J484Vk0zYSx3qVB1ZDqL
-----END PRIVATE KEY-----
"""

internal const val TEST_CERT_PEM = """-----BEGIN CERTIFICATE-----
MIIBhTCCASugAwIBAgIUcnoareKnxFJaaDnbSJgAL0hcIrQwCgYIKoZIzj0EAwIw
GDEWMBQGA1UEAwwNc29sc3RvbmUtdGVzdDAeFw0yNjA2MTYyMDE1NDBaFw0zNjA2
MTMyMDE1NDBaMBgxFjAUBgNVBAMMDXNvbHN0b25lLXRlc3QwWTATBgcqhkjOPQIB
BggqhkjOPQMBBwNCAASBTe0bOF36qZ5UDu5oRG/ap5uexhdP+s/TWeexTbbuOtIO
tzHP9Q+cuCdyap8IVFH9J484Vk0zYSx3qVB1ZDqLo1MwUTAdBgNVHQ4EFgQU9/9r
pq8birxeClvv5rLzoykTHEkwHwYDVR0jBBgwFoAU9/9rpq8birxeClvv5rLzoykT
HEkwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiASPm1UYpASQBW3
7HNzWbB0rfQLO4hFJPjRt2hsntjlzQIhAKDxkgE4oSyEBIvJxe3ePW4x3xxPhuQd
oTaSul6M75cr
-----END CERTIFICATE-----
"""
