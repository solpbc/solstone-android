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
        assertEquals(listOf(DirectEndpoint("10.1.2.3", 7657)), parsed.candidates)
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

    @Test
    fun parsesV05CountTwoCanonicalVector() {
        val parsed = parseDirectPairLink(
            "https://go.solstone.app/p#0M0G47F9R00042P66DJ18001081G81860W40J2GB1G6GW3X0M6HA7955MTKTHADANEPAVBNF",
        )

        assertEquals(
            listOf(
                DirectEndpoint("192.0.2.10", 7657),
                DirectEndpoint("198.51.100.20", 7657),
            ),
            parsed.candidates,
        )
        assertEquals("192.0.2.10", parsed.host)
        assertEquals(7657, parsed.port)
        assertEquals("000102030405060708090a0b0c0d0e0f", parsed.nonce)
        assertContentEquals(caFingerprint(), parsed.caFingerprintPrefix)
    }

    @Test
    fun parsesV05CountFourCanonicalVector() {
        val parsed = parseDirectPairLink(
            "https://go.solstone.app/p#0M0G87F9R00042P66DJ19JR0E4F0M000500020G30G2GC1R81450P30D1R7T18D2MEJAB9N7N2MTNAXCNPQAY",
        )

        assertEquals(
            listOf(
                DirectEndpoint("192.0.2.10", 7657),
                DirectEndpoint("198.51.100.20", 7657),
                DirectEndpoint("203.0.113.30", 7657),
                DirectEndpoint("10.0.0.40", 7657),
            ),
            parsed.candidates,
        )
        assertEquals("000102030405060708090a0b0c0d0e0f", parsed.nonce)
        assertContentEquals(caFingerprint(), parsed.caFingerprintPrefix)
    }

    @Test
    fun rejectsMalformedV05Payloads() {
        assertFailsWith<IllegalArgumentException> {
            parseDirectPairLink(withBlob(v05Blob(emptyList())))
        }
        assertFailsWith<IllegalArgumentException> {
            parseDirectPairLink(withBlob(v05Blob(listOf(byteArrayOf(192.toByte(), 0, 2, 10))).also { it[1] = 0x02 }))
        }
        assertFailsWith<IllegalArgumentException> {
            parseDirectPairLink(withBlob(v05Blob(listOf(byteArrayOf(192.toByte(), 0, 2, 10))).copyOf(40)))
        }
        assertFailsWith<IllegalArgumentException> {
            parseDirectPairLink(withBlob(v05Blob(listOf(byteArrayOf(192.toByte(), 0, 2, 10))).also { it[0] = 0x06 }))
        }
        assertFailsWith<IllegalArgumentException> {
            parseDirectPairLink(
                withBlob(
                    v05Blob(
                        listOf(
                            byteArrayOf(127, 0, 0, 1),
                            byteArrayOf(127, 1, 2, 3),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun filtersV05LoopbackCandidates() {
        val parsed = parseDirectPairLink(
            withBlob(
                v05Blob(
                    listOf(
                        byteArrayOf(127, 0, 0, 1),
                        byteArrayOf(10, 0, 0, 40),
                    ),
                ),
            ),
        )

        assertEquals(listOf(DirectEndpoint("10.0.0.40", 7657)), parsed.candidates)
    }

    @Test
    fun classifiesPairResponseStatusForDialLoop() {
        assertEquals(DialDecision.SUCCEED, classifyPairResponseStatus(200))
        assertEquals(DialDecision.TERMINAL, classifyPairResponseStatus(410))
        listOf(400, 401, 404, 500, 503).forEach { status ->
            assertEquals(DialDecision.ADVANCE, classifyPairResponseStatus(status))
        }
    }

    @Test
    fun ordersCandidatesByMatchingLocalSubnet() {
        val candidates = listOf(
            DirectEndpoint("198.51.100.10", 7657),
            DirectEndpoint("192.168.1.90", 7657),
            DirectEndpoint("203.0.113.8", 7657),
            DirectEndpoint("10.0.250.9", 7657),
        )
        val interfaces = listOf(
            LocalIPv4Interface("192.168.1.20", 24),
            LocalIPv4Interface("10.0.5.4", 16),
        )

        assertEquals(
            listOf(
                DirectEndpoint("192.168.1.90", 7657),
                DirectEndpoint("10.0.250.9", 7657),
                DirectEndpoint("198.51.100.10", 7657),
                DirectEndpoint("203.0.113.8", 7657),
            ),
            orderCandidatesBySubnet(candidates, interfaces),
        )
        assertEquals(candidates, orderCandidatesBySubnet(candidates, emptyList()))

        val withUnparseable = listOf(
            DirectEndpoint("not-an-ip", 7657),
            DirectEndpoint("192.168.1.91", 7657),
        )
        assertEquals(
            listOf(
                DirectEndpoint("192.168.1.91", 7657),
                DirectEndpoint("not-an-ip", 7657),
            ),
            orderCandidatesBySubnet(withUnparseable, interfaces),
        )
    }

    private fun pairLink(ip: ByteArray, port: Int, nonce: ByteArray = ByteArray(16), ca: ByteArray = ByteArray(16)): String =
        withBlob(blob(ip, port, nonce, ca))

    private fun withBlob(blob: ByteArray): String = "https://go.solstone.app/p#${crockfordEncode(blob)}"

    private fun v05Blob(
        ips: List<ByteArray>,
        port: Int = 7657,
        nonce: ByteArray = ByteArray(16) { it.toByte() },
        ca: ByteArray = caFingerprint(),
    ): ByteArray {
        val out = ByteArray(37 + 4 * ips.size)
        out[0] = 0x05
        out[1] = 0x01
        out[2] = ips.size.toByte()
        out[3] = ((port shr 8) and 0xff).toByte()
        out[4] = (port and 0xff).toByte()
        ips.forEachIndexed { index, ip ->
            ip.copyInto(out, 5 + 4 * index)
        }
        val nonceOffset = 5 + 4 * ips.size
        nonce.copyInto(out, nonceOffset)
        ca.copyInto(out, nonceOffset + 16)
        return out
    }

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

    private fun caFingerprint(): ByteArray = ByteArray(16) { (0xa0 + it).toByte() }

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
