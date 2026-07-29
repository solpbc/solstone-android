// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GateIdentityTest {
    @Test
    fun derivesAuthoritativeFixedVector() {
        val identity = deriveGateIdentity("20260729T120000Z-0123456789abcdef")

        assertEquals("android-gate-0123456789abcdef.test", identity.observerHostname)
        assertEquals("20260729", identity.observerDay)
        assertEquals("120000_5830", identity.g1Segment)
        assertEquals(
            "solstone android gate g1 run=20260729T120000Z-0123456789abcdef\n",
            identity.g1Payload.toString(Charsets.US_ASCII),
        )
        assertEquals(63, identity.g1PayloadBytes)
        assertEquals(
            "3c85822cc57274453415f75f643b5c134778b55a025bcf361cb8b1ac81a90201",
            identity.g1PayloadSha256,
        )
        assertEquals(
            "706f638515d2bc56a33db9237f4fac532a5919468394649e6bf92ab054765ac0",
            identity.fixtureNamespaceSha256,
        )
    }

    @Test
    fun derivesModuloBoundaryVectors() {
        val zero = deriveGateIdentity("20260729T235959Z-0000000000000000")
        val maximum = deriveGateIdentity("20260729T000000Z-ffffffffffffffff")

        assertEquals("235959_1", zero.g1Segment)
        assertEquals("000000_1897", maximum.g1Segment)
        assertEquals(
            "284c2ef507ddac457cf99e8948ce98b4fb85551fe149e6b537e5f49bce92c66d",
            zero.g1PayloadSha256,
        )
        assertEquals(
            "825870606eebcc1b2b536b65d7f4a64e140c4681be99514158535c21d01d4a39",
            maximum.g1PayloadSha256,
        )
    }

    @Test
    fun mismatchedCoordinatorCommitmentFails() {
        val identity = deriveGateIdentity("20260729T120000Z-0123456789abcdef")

        assertTrue(identity.matchesG1Commitment(63, identity.g1PayloadSha256))
        assertFalse(identity.matchesG1Commitment(62, identity.g1PayloadSha256))
        assertFalse(identity.matchesG1Commitment(63, "0".repeat(64)))
    }

    @Test
    fun malformedNonceIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            deriveGateIdentity("20260729T120000Z-0123456789ABCDEF")
        }
    }
}
