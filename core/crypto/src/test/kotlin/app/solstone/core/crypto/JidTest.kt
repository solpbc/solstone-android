// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JidTest {
    @Test
    fun derivesJidFromRelayCaSpkiVector() {
        assertEquals("2c04a888-b4bc-842e-98eb-3954d2460d47", jidFromSpki(TEST_RELAY_CA_PEM_FOR_JID))
    }

    @Test
    fun rejectsWrongCurveCertificate() {
        assertFailsWith<IllegalArgumentException> {
            jidFromSpki(TEST_P384_CA_PEM)
        }
    }

    @Test
    fun rejectsGarbageInput() {
        assertFailsWith<Exception> {
            jidFromSpki("not a certificate")
        }
    }
}

// Test-only ephemeral certificate copied from RelayPairingTest; not an operational secret.
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

private const val TEST_P384_CA_PEM = """-----BEGIN CERTIFICATE-----
MIIBvjCCAUSgAwIBAgIUPWXOyfi/+c17PxGDI6Sh4bcKOscwCgYIKoZIzj0EAwIw
FjEUMBIGA1UEAwwLd3JvbmctY3VydmUwHhcNMjYwNjMwMDIwOTQyWhcNMzYwNjI3
MDIwOTQyWjAWMRQwEgYDVQQDDAt3cm9uZy1jdXJ2ZTB2MBAGByqGSM49AgEGBSuB
BAAiA2IABB6L0FqVMFWjiXKrh0q9TBfVdNAbh8ZJEE/M6EVN1qnJFF6cLdiWj3ri
ouP2rm8Gu3iGkO49HN5GbH1TqxPW8v4feA/hL3hbusf/5m+H4xyzlllEZj9ghX2Q
BnciYiiRf6NTMFEwHQYDVR0OBBYEFBhm9Sv7+d/CWX36jRG1LqscFCSFMB8GA1Ud
IwQYMBaAFBhm9Sv7+d/CWX36jRG1LqscFCSFMA8GA1UdEwEB/wQFMAMBAf8wCgYI
KoZIzj0EAwIDaAAwZQIwMytyPYrtJ2ghWwUr07zrEO42yEqcj5QWY84I0TI0vabr
QAttRrSQFobelUOlx9FDAjEA/MWQOD+BmIjzuvvTqNOUYRZ9ok0J1g80u/+PAQmq
a0Nk+ltTXc5eWo+YbK4vNB8U
-----END CERTIFICATE-----
"""
