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
    fun parsesRelayPairWindowDefaultOriginConformanceVector() {
        val parsed = parsePairLink(
            "https://go.solstone.app/p#0R0J6HB7H6NWVVR1VTPVXVYAZTXBW0938NKRKAYDXW00",
        ) as RelayPairLink

        assertContentEquals(hexBytes("0123456789abcdef"), parsed.s)
        assertContentEquals(hexBytes("deadbeefcafebabe0123456789abcdef"), parsed.caFpSpki)
        assertEquals(null, parsed.relayOrigin)
    }

    @Test
    fun parsesRelayPairWindowCustomOriginConformanceVector() {
        val parsed = parsePairLink(
            "https://go.solstone.app/p#0R0J6HB7H6NWVVR1VTPVXVYAZTXBW0938NKRKAYDXWAPGX3ME1SKMBSFE9JPRRBS5SJQGRBDE1P6A",
        ) as RelayPairLink

        assertContentEquals(hexBytes("0123456789abcdef"), parsed.s)
        assertContentEquals(hexBytes("deadbeefcafebabe0123456789abcdef"), parsed.caFpSpki)
        assertEquals("https://relay.example", parsed.relayOrigin)
    }

    @Test
    fun parsePairLinkDispatchesDirectButParseDirectRejectsRelay() {
        val direct = parsePairLink(pairLink(byteArrayOf(10, 1, 2, 3), 7657))
        assertTrue(direct is DirectPairLink)
        assertFailsWith<IllegalArgumentException> {
            parseDirectPairLink(
                "https://go.solstone.app/p#0R0J6HB7H6NWVVR1VTPVXVYAZTXBW0938NKRKAYDXW00",
            )
        }
    }

    @Test
    fun rejectsRetiredRelayPayloadAndMalformedPairWindowPayloads() {
        assertFailsWith<IllegalArgumentException> {
            parsePairLink(withBlob(ByteArray(54).also { it[0] = 0x03 }))
        }
        assertFailsWith<IllegalArgumentException> {
            parsePairLink(
                withBlob(
                    relayWindowBlob().also {
                        it[9] = 0x02
                    },
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parsePairLink(withBlob(ByteArray(26).also { it[0] = 0x06 }))
        }
        assertFailsWith<IllegalArgumentException> {
            parsePairLink(withBlob(relayWindowBlob().copyOf(28)))
        }
        assertFailsWith<IllegalArgumentException> {
            parsePairLink(withBlob(relayWindowBlob("abc").copyOf(29)))
        }
        assertFailsWith<IllegalArgumentException> {
            parsePairLink(withBlob(relayWindowBlob("abc") + 0x00.toByte()))
        }
    }

    @Test
    fun rejectsNonZeroCrockfordPadBits() {
        assertFailsWith<IllegalArgumentException> {
            parsePairLink("https://go.solstone.app/p#1")
        }
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
    fun acceptsCgnatBoundaryRanges() {
        listOf(
            byteArrayOf(100, 64, 0, 0),
            byteArrayOf(100, 127.toByte(), 255.toByte(), 255.toByte()),
        ).forEach { ip ->
            parseDirectPairLink(pairLink(ip, 0))
        }

        assertFailsWith<IllegalArgumentException> {
            parseDirectPairLink(pairLink(byteArrayOf(100, 63, 0, 1), 0))
        }
        assertFailsWith<IllegalArgumentException> {
            parseDirectPairLink(pairLink(byteArrayOf(100, 128.toByte(), 0, 1), 0))
        }
    }

    @Test
    fun filtersV05ToPrivateCandidatesOrderPreserved() {
        val parsed = parseDirectPairLink(
            withBlob(
                v05Blob(
                    listOf(
                        byteArrayOf(10, 0, 0, 40),
                        byteArrayOf(8, 8, 8, 8),
                        byteArrayOf(127, 0, 0, 1),
                        byteArrayOf(100, 64, 0, 5),
                        byteArrayOf(192.toByte(), 168.toByte(), 1, 90),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                DirectEndpoint("10.0.0.40", 7657),
                DirectEndpoint("100.64.0.5", 7657),
                DirectEndpoint("192.168.1.90", 7657),
            ),
            parsed.candidates,
        )
        assertEquals("10.0.0.40", parsed.host)
        assertEquals(7657, parsed.port)
        assertEquals("000102030405060708090a0b0c0d0e0f", parsed.nonce)
        assertContentEquals(caFingerprint(), parsed.caFingerprintPrefix)
    }

    @Test
    fun parsesV05FourPrivateCandidates() {
        val parsed = parseDirectPairLink(
            withBlob(
                v05Blob(
                    listOf(
                        byteArrayOf(10, 0, 0, 40),
                        byteArrayOf(172.toByte(), 16, 0, 1),
                        byteArrayOf(192.toByte(), 168.toByte(), 1, 90),
                        byteArrayOf(169.toByte(), 254.toByte(), 0, 1),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                DirectEndpoint("10.0.0.40", 7657),
                DirectEndpoint("172.16.0.1", 7657),
                DirectEndpoint("192.168.1.90", 7657),
                DirectEndpoint("169.254.0.1", 7657),
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
            parseDirectPairLink(withBlob(v05Blob(listOf(byteArrayOf(8, 8, 8, 8)))))
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

    private fun relayWindowBlob(origin: String? = null): ByteArray {
        val originBytes = origin?.toByteArray(Charsets.UTF_8)
        val out = ByteArray(27 + (originBytes?.size ?: 0))
        out[0] = 0x06
        hexBytes("0123456789abcdef").copyInto(out, 1)
        out[9] = 0x01
        hexBytes("deadbeefcafebabe0123456789abcdef").copyInto(out, 10)
        if (originBytes == null) {
            out[26] = 0x00
        } else {
            out[26] = originBytes.size.toByte()
            originBytes.copyInto(out, 27)
        }
        return out
    }

    private fun caFingerprint(): ByteArray = ByteArray(16) { (0xa0 + it).toByte() }

    private fun hexBytes(value: String): ByteArray {
        val out = ByteArray(value.length / 2)
        for (index in out.indices) {
            out[index] = value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
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
