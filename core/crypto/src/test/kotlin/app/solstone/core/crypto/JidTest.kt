// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JidTest {
    @Test
    fun jidFromCaPemDelegatesToDerivation() {
        val spkiDer = certificateFromPem(TEST_RELAY_CA_PEM_FOR_JID).publicKey.encoded

        assertEquals(jidFromSpkiDer(spkiDer), jidFromCaPem(TEST_RELAY_CA_PEM_FOR_JID))
    }

    @Test
    fun malformedSpkiIsTypedRefusal() {
        val refusal = assertFailsWith<JidRefusalException> { jidFromSpkiDer(byteArrayOf(0x30, 0x00)) }

        assertEquals(JidRefusalKind.MALFORMED_SPKI, refusal.kind)
    }

    @Test
    fun incompleteOrTrailingNonEcSpkiIsMalformed() {
        assertMalformedSpki(byteArrayOf(0x30, 0x07, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70))
        assertMalformedSpki(
            hexBytes(
                "302a300506032b65700321003b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da2900",
            ),
        )
    }

    private fun assertMalformedSpki(spkiDer: ByteArray) {
        val refusal = assertFailsWith<JidRefusalException> { jidFromSpkiDer(spkiDer) }

        assertEquals(JidRefusalKind.MALFORMED_SPKI, refusal.kind)
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

// Test-only ephemeral certificate used to exercise the PEM adapter; not an operational secret.
private const val TEST_RELAY_CA_PEM_FOR_JID = """-----BEGIN CERTIFICATE-----
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
