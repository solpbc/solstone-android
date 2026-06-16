// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairLinkTest {
    @Test
    fun parsesKnownPlaintextVector() {
        val nonce = ByteArray(16) { (0x10 + it).toByte() }
        val ca = ByteArray(16) { (0xa0 + it).toByte() }
        val link = pairLink(byteArrayOf(10, 1, 2, 3), 7657, nonce, ca)

        val parsed = parseDirectPairLink(link)

        assertEquals("10.1.2.3", parsed.host)
        assertEquals(7657, parsed.port)
        assertEquals("101112131415161718191a1b1c1d1e1f", parsed.nonce)
        assertContentEquals(ca, parsed.caFingerprintPrefix)
        assertEquals(DirectEndpoint("10.1.2.3", 7657), parsed.endpoint())
    }

    @Test
    fun rejectsMalformedPayloadsAndUnrecognizedHost() {
        assertTrue(looksLikePairLink("https://go.solstone.app/p#abc"))
        val retiredHost = listOf("link", "solpbc", "org").joinToString(".")
        assertFalse(looksLikePairLink("https://$retiredHost/p#abc"))
        assertFalse(looksLikePairLink("https://example.invalid/p#abc"))

        val base = blob(byteArrayOf(10, 1, 2, 3), 7657)
        assertFailsWith<IllegalArgumentException> { parseDirectPairLink(withBlob(base.also { it[0] = 0x05 })) }
        assertFailsWith<IllegalArgumentException> { parseDirectPairLink(withBlob(blob(byteArrayOf(10, 1, 2, 3), 7657).also { it[1] = 0x02 })) }
        assertFailsWith<IllegalArgumentException> { parseDirectPairLink("https://go.solstone.app/p#${crockfordEncode(ByteArray(39))}") }
        assertFailsWith<IllegalArgumentException> { parseDirectPairLink("https://go.solstone.app/p#not-valid-*") }
        assertFailsWith<IllegalArgumentException> { parseDirectPairLink(pairLink(byteArrayOf(8, 8, 8, 8), 7657)) }
        assertFailsWith<IllegalArgumentException> { parseDirectPairLink(pairLink(byteArrayOf(172.toByte(), 32, 0, 1), 7657)) }
        assertFailsWith<IllegalArgumentException> { parseDirectPairLink(pairLink(byteArrayOf(192.toByte(), 169.toByte(), 0, 1), 7657)) }
    }

    @Test
    fun acceptsPrivateAndLinkLocalRanges() {
        listOf(
            byteArrayOf(10, 0, 0, 1),
            byteArrayOf(172.toByte(), 16, 0, 1),
            byteArrayOf(172.toByte(), 31, 255.toByte(), 1),
            byteArrayOf(192.toByte(), 168.toByte(), 0, 1),
            byteArrayOf(169.toByte(), 254.toByte(), 0, 1),
        ).forEach { ip ->
            parseDirectPairLink(pairLink(ip, 0))
        }
    }

    private fun pairLink(ip: ByteArray, port: Int, nonce: ByteArray = ByteArray(16), ca: ByteArray = ByteArray(16)): String =
        withBlob(blob(ip, port, nonce, ca))

    private fun withBlob(blob: ByteArray): String = "https://go.solstone.app/p#${crockfordEncode(blob)}"

    private fun blob(ip: ByteArray, port: Int, nonce: ByteArray = ByteArray(16), ca: ByteArray = ByteArray(16)): ByteArray {
        val out = ByteArray(40)
        out[0] = 0x04
        out[1] = 0x01
        ip.copyInto(out, 2)
        out[6] = ((port shr 8) and 0xff).toByte()
        out[7] = (port and 0xff).toByte()
        nonce.copyInto(out, 8)
        ca.copyInto(out, 24)
        return out
    }

    private fun crockfordEncode(bytes: ByteArray): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 0x1f])
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (bits > 0) {
            out.append(alphabet[(buffer shl (5 - bits)) and 0x1f])
        }
        return out.toString()
    }
}
