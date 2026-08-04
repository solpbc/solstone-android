// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.crypto.JidRefusalException
import app.solstone.core.crypto.jidFromSpkiDer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JidDerivationInstrumentedTest {
    @Test
    fun canonicalAndCompressedP256DeriveThePublishedJid() {
        assertEquals(EXPECTED_JID, jidFromSpkiDer(hexBytes(CANONICAL_SPKI_HEX)))
        assertEquals(EXPECTED_JID, jidFromSpkiDer(hexBytes(COMPRESSED_SPKI_HEX)))
    }

    @Test
    fun trailingDataIsRefused() {
        try {
            jidFromSpkiDer(hexBytes(TRAILING_DATA_SPKI_HEX))
            fail("expected trailing SPKI data to be refused")
        } catch (_: JidRefusalException) {
            // Expected: ASN1Primitive.fromByteArray requires the whole input to parse.
        }
    }

    private fun hexBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object {
        const val EXPECTED_JID = "5620bab1-476a-88df-93d4-f4f525b991dd"
        const val CANONICAL_SPKI_HEX =
            "3059301306072a8648ce3d020106082a8648ce3d030107034200046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
        const val COMPRESSED_SPKI_HEX =
            "3039301306072a8648ce3d020106082a8648ce3d030107032200036b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        const val TRAILING_DATA_SPKI_HEX =
            "3059301306072a8648ce3d020106082a8648ce3d030107034200046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5ff"
    }
}
