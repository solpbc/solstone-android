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
        assertEquals(7657, (response.localEndpoints.single()["port"] as Number).toInt())
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

    @Test
    fun pairResponseRelayAccessVariants() {
        val base = """{"ca_chain":["ca1"],"client_cert":"cert","instance_id":"inst","home_attestation":"jwt","fingerprint":"fp""""

        val omitted = PairResponse.fromJson("$base}")
        assertEquals(PairRelayAccess.Omitted, omitted.relayAccess)

        val presentNull = PairResponse.fromJson("""$base,"relay_access":null}""")
        assertEquals(PairRelayAccess.PresentNull, presentNull.relayAccess)

        val obj = PairResponse.fromJson("""$base,"relay_access":{"protocol_version":2,"status":"ready"}}""")
        assert(obj.relayAccess is PairRelayAccess.Object)
        assertEquals(2, ((obj.relayAccess as PairRelayAccess.Object).fields["protocol_version"] as Number).toInt())

        val nonObj = PairResponse.fromJson("""$base,"relay_access":"string_val"}""")
        assert(nonObj.relayAccess is PairRelayAccess.NonObject)
        assertEquals("string_val", (nonObj.relayAccess as PairRelayAccess.NonObject).json)
    }

    @Test
    fun pairResponseToStringRedactsSensitiveFields() {
        val response = PairResponse(
            caChain = listOf("ca1"),
            clientCert = "sensitive-cert",
            instanceId = "inst",
            homeLabel = "home",
            homeAttestation = "sensitive-jwt",
            fingerprint = "fp",
            relayAccess = PairRelayAccess.Object(mapOf("device_token" to "secret-token")),
        )
        val stringified = response.toString()
        assert(!stringified.contains("sensitive-cert"))
        assert(!stringified.contains("sensitive-jwt"))
        assert(!stringified.contains("secret-token"))
        assert(stringified.contains("<redacted>"))
    }
}
