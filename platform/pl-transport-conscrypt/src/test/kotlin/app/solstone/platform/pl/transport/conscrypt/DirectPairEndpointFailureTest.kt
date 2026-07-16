// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.LocalIPv4Interface
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.EndpointStore
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DirectPairEndpointFailureTest {
    @Test
    fun finalFailureReportsEndpointAfterCandidateReordering() {
        val attempted = mutableListOf<DirectEndpoint>()

        val failure = assertIs<DirectPairEndpointException>(
            runCatching {
                pairAndProbe(
                    pairLink = pairLink(
                        listOf(
                            byteArrayOf(10, 0, 1, 2),
                            byteArrayOf(10, 0, 0, 2),
                        ),
                    ),
                    deviceLabel = "test device",
                    credentialStore = FakeCredentialStore,
                    identityStore = FakeIdentityStore,
                    endpointStore = FakeEndpointStore,
                    sessionOpener = { endpoint, _ ->
                        attempted += endpoint
                        throw ConnectException("refused ${endpoint.host}")
                    },
                    localInterfaces = listOf(LocalIPv4Interface("10.0.0.8", 24)),
                )
            }.exceptionOrNull(),
        )

        assertEquals("10.0.1.2", failure.endpointHost)
        assertEquals(7657, failure.endpointPort)
        assertEquals(
            listOf(DirectEndpoint("10.0.0.2", 7657), DirectEndpoint("10.0.1.2", 7657)),
            attempted,
        )
    }

    private fun pairLink(ips: List<ByteArray>, port: Int = 7657): String {
        val bytes = ByteArray(37 + 4 * ips.size)
        bytes[0] = 0x05
        bytes[1] = 0x01
        bytes[2] = ips.size.toByte()
        bytes[3] = ((port shr 8) and 0xff).toByte()
        bytes[4] = (port and 0xff).toByte()
        ips.forEachIndexed { index, ip -> ip.copyInto(bytes, 5 + 4 * index) }
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

    private object FakeCredentialStore : ClientCredentialStore {
        override fun save(credential: ClientCredential) = Unit
        override fun load(): ClientCredential? = null
        override fun clear() = Unit
    }

    private object FakeIdentityStore : IdentityStore {
        override fun save(home: PairedHome) = Unit
        override fun load(): PairedHome? = null
        override fun clear() = Unit
    }

    private object FakeEndpointStore : EndpointStore {
        override fun save(endpoint: DirectEndpoint) = Unit
        override fun load(): DirectEndpoint? = null
        override fun clear() = Unit
    }
}
