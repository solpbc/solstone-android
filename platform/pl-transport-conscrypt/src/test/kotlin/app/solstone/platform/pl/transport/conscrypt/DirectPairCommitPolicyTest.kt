// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.ByteDuplex
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.FLAG_CLOSE
import app.solstone.core.pl.FLAG_DATA
import app.solstone.core.pl.FLAG_OPEN
import app.solstone.core.pl.LocalIPv4Interface
import app.solstone.core.pl.MuxSession
import app.solstone.core.pl.encodeFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DirectPairCommitPolicyTest {
    @Test
    fun mixedV05RefusalCreatesNoMaterialSessionDuplexOrRequest() {
        val counts = Counts()
        val link = pairLink(
            listOf(
                byteArrayOf(10, 0, 0, 2),
                byteArrayOf(192.toByte(), 0, 2, 42),
            ),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            invokePair(link, counts) { _, _ ->
                counts.sessionOpens++
                counts.duplexCreations++
                CertlessSession(MuxSession(responseDuplex(500, counts)), true)
            }
        }

        assertEquals("pair link is not local/private IPv4", failure.message)
        assertEquals(Counts(), counts)
    }

    @Test
    fun publicV04RefusalOpensNoSession() {
        val counts = Counts()

        val failure = assertFailsWith<IllegalArgumentException> {
            invokePair(v04PairLink(byteArrayOf(8, 8, 8, 8)), counts) { _, _ ->
                counts.sessionOpens++
                error("must not open")
            }
        }

        assertEquals("pair link is not local/private IPv4", failure.message)
        assertEquals(Counts(), counts)
    }

    @Test
    fun invalidV05CountsCreateNoMaterialSessionOrRequest() {
        listOf(0, 5, 8).forEach { count ->
            val counts = Counts()
            val link = pairLink((1..count).map { byteArrayOf(10, 0, 0, it.toByte()) })

            val failure = assertFailsWith<IllegalArgumentException> {
                invokePair(link, counts) { _, _ ->
                    counts.sessionOpens++
                    error("must not open")
                }
            }

            assertEquals("unsupported pair link payload", failure.message)
            assertEquals(Counts(), counts)
        }
    }

    @Test
    fun uniqueCandidatesOpenAtMostOnceInSubnetRankedOrder() {
        val counts = Counts()
        val attempted = mutableListOf<DirectEndpoint>()
        val link = pairLink(
            listOf(
                byteArrayOf(10, 0, 1, 2),
                byteArrayOf(10, 0, 0, 2),
                byteArrayOf(10, 0, 1, 2),
                byteArrayOf(10, 0, 2, 2),
            ),
        )

        assertFailsWith<DirectPairEndpointException> {
            invokePair(
                link,
                counts,
                localInterfaces = listOf(LocalIPv4Interface("10.0.0.8", 24)),
            ) { endpoint, _ ->
                counts.sessionOpens++
                attempted += endpoint
                throw ConnectException("refused")
            }
        }

        assertEquals(
            listOf(
                DirectEndpoint("10.0.0.2", 7657),
                DirectEndpoint("10.0.1.2", 7657),
                DirectEndpoint("10.0.2.2", 7657),
            ),
            attempted,
        )
        assertEquals(1, counts.materialGenerations)
        assertEquals(3, counts.sessionOpens)
        assertEquals(0, counts.duplexCreations)
        assertEquals(0, counts.requestInvocations)
    }

    @Test
    fun materialGeneratesOnceAcrossSessionOpenFailures() {
        val counts = Counts()
        val link = pairLink(listOf(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 1, 2)))

        assertFailsWith<DirectPairEndpointException> {
            invokePair(link, counts) { _, _ ->
                counts.sessionOpens++
                throw ConnectException("refused")
            }
        }

        assertEquals(1, counts.materialGenerations)
        assertEquals(2, counts.sessionOpens)
        assertEquals(0, counts.requestInvocations)
    }

    @Test
    fun sessionOpenFailureAdvancesAndSecondCandidateReceivesOneRequest() {
        val counts = Counts()
        val opened = mutableListOf<DirectEndpoint>()
        val link = pairLink(listOf(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 1, 2)))

        val failure = assertFailsWith<DirectPairCodeExpiredException> {
            invokePair(link, counts) { endpoint, _ ->
                counts.sessionOpens++
                opened += endpoint
                if (counts.sessionOpens == 1) throw ConnectException("refused")
                counts.duplexCreations++
                CertlessSession(MuxSession(responseDuplex(410, counts)), true)
            }
        }

        assertEquals(
            listOf(DirectEndpoint("10.0.0.2", 7657), DirectEndpoint("10.0.1.2", 7657)),
            opened,
        )
        assertEquals("10.0.1.2", failure.endpointHost)
        assertEquals(2, counts.sessionOpens)
        assertEquals(1, counts.requestInvocations)
        assertEquals(1, counts.materialGenerations)
    }

    @Test
    fun singleCandidateSessionOpenFailureUsesEndpointFailureNotExhaustionMessage() {
        val counts = Counts()

        val failure = assertFailsWith<DirectPairEndpointException> {
            invokePair(pairLink(listOf(byteArrayOf(10, 0, 0, 2))), counts) { _, _ ->
                counts.sessionOpens++
                throw ConnectException("refused")
            }
        }

        assertEquals("10.0.0.2", failure.endpointHost)
        assertEquals("refused", failure.cause?.message)
        assertTrue(failure.message != "all pair candidates exhausted")
        assertEquals(1, counts.sessionOpens)
        assertEquals(0, counts.requestInvocations)
    }

    @Test
    fun requestFailureDoesNotOpenLaterCandidate() {
        val counts = Counts()
        val link = pairLink(listOf(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 1, 2)))

        assertFailsWith<DirectPairEndpointException> {
            invokePair(link, counts) { _, _ ->
                counts.sessionOpens++
                counts.duplexCreations++
                CertlessSession(MuxSession(closedDuplex(counts)), true)
            }
        }

        assertEquals(1, counts.materialGenerations)
        assertEquals(1, counts.sessionOpens)
        assertEquals(1, counts.duplexCreations)
        assertTrue(counts.requestInvocations > 0)
    }

    @Test
    fun nonSuccessHttpDoesNotOpenLaterCandidate() {
        val counts = Counts()
        val link = pairLink(listOf(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 1, 2)))

        val failure = assertFailsWith<DirectPairEndpointException> {
            invokePair(link, counts) { _, _ ->
                counts.sessionOpens++
                counts.duplexCreations++
                CertlessSession(MuxSession(responseDuplex(503, counts)), true)
            }
        }

        assertEquals("pair failed HTTP 503: failure", failure.cause?.message)
        assertEquals(1, counts.sessionOpens)
        assertEquals(1, counts.duplexCreations)
        assertTrue(counts.requestInvocations > 0)
    }

    @Test
    fun malformedSuccessResponseDoesNotOpenLaterCandidate() {
        val counts = Counts()
        val link = pairLink(listOf(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 1, 2)))

        assertFailsWith<IllegalArgumentException> {
            invokePair(link, counts) { _, _ ->
                counts.sessionOpens++
                counts.duplexCreations++
                CertlessSession(MuxSession(responseDuplex(200, counts, "{")), true)
            }
        }

        assertEquals(1, counts.sessionOpens)
        assertEquals(1, counts.duplexCreations)
        assertTrue(counts.requestInvocations > 0)
    }

    @Test
    fun caPrefixVerificationFailureDoesNotOpenLaterCandidate() {
        val counts = Counts()
        val link = pairLink(listOf(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 1, 2)))

        val failure = assertFailsWith<javax.net.ssl.SSLException> {
            invokePair(link, counts) { _, _ ->
                counts.sessionOpens++
                counts.duplexCreations++
                CertlessSession(MuxSession(responseDuplex(200, counts, pairResponse())), true)
            }
        }

        assertEquals("pair response CA fingerprint did not match QR pin", failure.message)
        assertEquals(1, counts.sessionOpens)
        assertEquals(1, counts.requestInvocations)
        assertTrue(failure.message != "all pair candidates exhausted")
    }

    @Test
    fun clientCertificateFingerprintFailureDoesNotOpenLaterCandidate() {
        val counts = Counts()
        val caPrefix = app.solstone.core.crypto.sha256(
            app.solstone.core.crypto.pemToDer(TEST_RELAY_CA_PEM, "CERTIFICATE"),
        ).copyOf(16)
        val link = pairLink(
            listOf(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 1, 2)),
            caPrefix = caPrefix,
        )

        val failure = assertFailsWith<javax.net.ssl.SSLException> {
            invokePair(link, counts) { _, _ ->
                counts.sessionOpens++
                counts.duplexCreations++
                CertlessSession(
                    MuxSession(responseDuplex(200, counts, pairResponse(fingerprint = "sha256:wrong"))),
                    true,
                )
            }
        }

        assertEquals("pair response client fingerprint mismatch", failure.message)
        assertEquals(1, counts.sessionOpens)
        assertEquals(1, counts.requestInvocations)
        assertTrue(failure.message != "all pair candidates exhausted")
    }

    @Test
    fun http410ThrowsDirectPairCodeExpiredWithoutSecrets() {
        val counts = Counts()
        val link = pairLink(listOf(byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 1, 2)))

        val failure = assertFailsWith<DirectPairCodeExpiredException> {
            invokePair(link, counts) { _, _ ->
                counts.sessionOpens++
                counts.duplexCreations++
                CertlessSession(MuxSession(responseDuplex(410, counts)), true)
            }
        }

        assertEquals("10.0.0.2", failure.endpointHost)
        assertEquals(7657, failure.endpointPort)
        assertEquals("direct pairing code expired at 10.0.0.2:7657", failure.message)
        assertMessageRedacted(failure.message.orEmpty(), link)
        assertEquals(1, counts.sessionOpens)
    }

    @Test
    fun wrappedTerminalFailureMessagesContainNoPairMaterial() {
        val counts = Counts()
        val link = pairLink(listOf(byteArrayOf(10, 0, 0, 2)))

        val failure = assertFailsWith<DirectPairEndpointException> {
            invokePair(link, counts) { _, _ ->
                counts.sessionOpens++
                counts.duplexCreations++
                CertlessSession(MuxSession(responseDuplex(503, counts)), true)
            }
        }

        assertMessageRedacted(failure.message.orEmpty(), link)
        assertMessageRedacted(failure.cause?.message.orEmpty(), link)
    }

    private fun invokePair(
        pairLink: String,
        counts: Counts,
        localInterfaces: List<LocalIPv4Interface> = emptyList(),
        sessionOpener: (DirectEndpoint, ByteArray) -> CertlessSession,
    ) {
        pairAndProbe(
            pairLink = pairLink,
            deviceLabel = "test device",
            credentialStore = EmptyCredentialStore,
            identityStore = EmptyIdentityStore,
            endpointStore = EmptyEndpointStore,
            sessionOpener = sessionOpener,
            localInterfaces = localInterfaces,
            materialFactory = {
                counts.materialGenerations++
                DirectPairMaterial(PRIVATE_KEY_MARKER, CSR_MARKER.toByteArray())
            },
        )
    }

    private fun responseDuplex(status: Int, counts: Counts, body: String = "failure"): ByteDuplex {
        val reason = when (status) {
            200 -> "OK"
            410 -> "Gone"
            else -> "Failure"
        }
        val response = "HTTP/1.1 $status $reason\r\nContent-Length: ${body.length}\r\n\r\n$body"
            .toByteArray(Charsets.US_ASCII)
        return CountingDuplex(
            ByteArrayInputStream(encodeFrame(1, FLAG_DATA or FLAG_CLOSE, response)),
            counts,
        )
    }

    private fun closedDuplex(counts: Counts): ByteDuplex =
        CountingDuplex(ByteArrayInputStream(ByteArray(0)), counts)

    private fun pairResponse(
        fingerprint: String = "sha256:" + app.solstone.core.crypto.sha256Hex(
            app.solstone.core.crypto.certificateFromPem(TEST_RELAY_LEAF_PEM).encoded,
        ),
    ): String =
        """
        {
          "ca_chain":[${app.solstone.core.pl.toJson(TEST_RELAY_CA_PEM)}],
          "client_cert":${app.solstone.core.pl.toJson(TEST_RELAY_LEAF_PEM)},
          "instance_id":"test-instance",
          "home_label":"test-home",
          "home_attestation":"attestation.jwt",
          "fingerprint":"$fingerprint"
        }
        """.trimIndent()

    private fun assertMessageRedacted(message: String, link: String) {
        val fragment = link.substringAfter('#')
        assertTrue(!message.contains(NONCE_HEX))
        assertTrue(!message.contains(fragment))
        assertTrue(!message.contains(link))
        assertTrue(!message.contains(CSR_MARKER))
        assertTrue(!message.contains(PRIVATE_KEY_MARKER))
    }

    private data class Counts(
        var materialGenerations: Int = 0,
        var sessionOpens: Int = 0,
        var duplexCreations: Int = 0,
        var requestInvocations: Int = 0,
    )

    private class CountingDuplex(
        override val input: InputStream,
        private val counts: Counts,
    ) : ByteDuplex {
        override val output: OutputStream = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len >= 8 && (b[off + 4].toInt() and FLAG_OPEN) != 0) {
                    counts.requestInvocations++
                }
                super.write(b, off, len)
            }

            override fun write(b: Int) {
                super.write(b)
            }
        }

        override fun close() {
            input.close()
            output.close()
        }
    }

    private object EmptyCredentialStore : ClientCredentialStore {
        override fun save(credential: ClientCredential) = Unit
        override fun load(): ClientCredential? = null
        override fun clear() = Unit
    }

    private object EmptyIdentityStore : IdentityStore {
        override fun save(home: PairedHome) = Unit
        override fun load(): PairedHome? = null
        override fun clear() = Unit
    }

    private object EmptyEndpointStore : EndpointStore {
        override fun save(endpoint: DirectEndpoint) = Unit
        override fun load(): DirectEndpoint? = null
        override fun clear() = Unit
    }

    companion object {
        private const val PRIVATE_KEY_MARKER = "PRIVATE-KEY-MARKER"
        private const val CSR_MARKER = "CSR-MARKER"
        private const val NONCE_HEX = "000102030405060708090a0b0c0d0e0f"

        private fun pairLink(
            ips: List<ByteArray>,
            port: Int = 7657,
            caPrefix: ByteArray = ByteArray(16),
        ): String {
            val bytes = ByteArray(37 + 4 * ips.size)
            bytes[0] = 0x05
            bytes[1] = 0x01
            bytes[2] = ips.size.toByte()
            bytes[3] = ((port shr 8) and 0xff).toByte()
            bytes[4] = (port and 0xff).toByte()
            ips.forEachIndexed { index, ip -> ip.copyInto(bytes, 5 + 4 * index) }
            ByteArray(16) { it.toByte() }.copyInto(bytes, 5 + 4 * ips.size)
            caPrefix.copyInto(bytes, 21 + 4 * ips.size)
            return "https://go.solstone.app/p#${encodeCrockford(bytes)}"
        }

        private fun v04PairLink(ip: ByteArray, port: Int = 7657): String {
            val bytes = ByteArray(40)
            bytes[0] = 0x04
            bytes[1] = 0x01
            ip.copyInto(bytes, 2)
            bytes[6] = ((port shr 8) and 0xff).toByte()
            bytes[7] = (port and 0xff).toByte()
            ByteArray(16) { it.toByte() }.copyInto(bytes, 8)
            return "https://go.solstone.app/p#${encodeCrockford(bytes)}"
        }

        private fun encodeCrockford(bytes: ByteArray): String {
            val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            val output = StringBuilder()
            var buffer = 0
            var bits = 0
            bytes.forEach { raw ->
                buffer = (buffer shl 8) or (raw.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    output.append(alphabet[(buffer shr bits) and 31])
                    buffer = buffer and ((1 shl bits) - 1)
                }
            }
            if (bits > 0) output.append(alphabet[(buffer shl (5 - bits)) and 31])
            return output.toString()
        }
    }
}
