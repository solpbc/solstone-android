// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairApiTest {
    @Test
    fun pairRequestUsesReferenceFieldNames() {
        assertEquals("""{"csr":"csr-pem","device_label":"device"}""", PairRequest("csr-pem", "device").toJson())
    }

    @Test
    fun pairResponseReadsRequiredAndOptionalFields() {
        val response = PairResponse.fromJson(
            """
            {
              "ca_chain":["ca1","ca2"],
              "client_cert":"cert",
              "instance_id":"inst",
              "home_label":"home",
              "home_attestation":"jwt",
              "fingerprint":"sha256:abc",
              "local_endpoints":[{"ip":"10.0.0.2","port":7657,"scope":"lan"}]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("ca1", "ca2"), response.caChain)
        assertEquals("cert", response.clientCert)
        assertEquals("inst", response.instanceId)
        assertEquals("home", response.homeLabel)
        assertEquals("jwt", response.homeAttestation)
        assertEquals("sha256:abc", response.fingerprint)
        assertEquals("10.0.0.2", response.localEndpoints.single()["ip"])
        assertEquals(7657.0, response.localEndpoints.single()["port"])
    }

    @Test
    fun missingOrEmptyCaChainThrows() {
        assertFailsWith<IllegalArgumentException> {
            PairResponse.fromJson("""{"client_cert":"cert","instance_id":"inst","home_attestation":"jwt","fingerprint":"fp"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            PairResponse.fromJson("""{"ca_chain":[],"client_cert":"cert","instance_id":"inst","home_attestation":"jwt","fingerprint":"fp"}""")
        }
    }
}
