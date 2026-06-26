// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class SpkiPinTest {
    @Test
    fun caSpkiFp16UsesCertificatePublicKeyEncoding() {
        assertContentEquals(sha256(certificateFromPem(TEST_CA_PEM).publicKey.encoded).copyOf(16), caSpkiFp16(TEST_CA_PEM))
    }

    @Test
    fun assertCaPinAcceptsLeafSignedByPinnedCa() {
        assertCaPin(TEST_CA_PEM, caSpkiFp16(TEST_CA_PEM), certificateFromPem(TEST_LEAF_PEM).encoded)
    }

    @Test
    fun assertCaPinRejectsWrongFingerprint() {
        val expected = caSpkiFp16(TEST_CA_PEM).also { it[0] = (it[0].toInt() xor 0xff).toByte() }

        assertFailsWith<CaPinException> {
            assertCaPin(TEST_CA_PEM, expected, certificateFromPem(TEST_LEAF_PEM).encoded)
        }
    }

    @Test
    fun assertCaPinRejectsMissingPeerLeaf() {
        assertFailsWith<CaPinException> {
            assertCaPin(TEST_CA_PEM, caSpkiFp16(TEST_CA_PEM), null)
        }
    }

    @Test
    fun assertCaPinRejectsLeafNotSignedByCa() {
        assertFailsWith<CaPinException> {
            assertCaPin(TEST_CA_PEM, caSpkiFp16(TEST_CA_PEM), certificateFromPem(TEST_UNRELATED_PEM).encoded)
        }
    }
}

// Test-only ephemeral certificates generated with openssl; not operational secrets.
internal const val TEST_RELAY_CA_PEM = """-----BEGIN CERTIFICATE-----
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

internal const val TEST_RELAY_LEAF_PEM = """-----BEGIN CERTIFICATE-----
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

internal const val TEST_RELAY_UNRELATED_PEM = """-----BEGIN CERTIFICATE-----
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

private const val TEST_CA_PEM = TEST_RELAY_CA_PEM
private const val TEST_LEAF_PEM = TEST_RELAY_LEAF_PEM
private const val TEST_UNRELATED_PEM = TEST_RELAY_UNRELATED_PEM
