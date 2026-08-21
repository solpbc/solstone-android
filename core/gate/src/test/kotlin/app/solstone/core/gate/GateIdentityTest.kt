// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GateIdentityTest {
    @Test
    fun derivesObserverHostnameFromRunNonce() {
        assertEquals(
            "android-gate-0123456789abcdef.test",
            deriveGateObserverHostname("20260729T120000Z-0123456789abcdef"),
        )
    }

    @Test
    fun malformedNonceIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            deriveGateObserverHostname("20260729T120000Z-0123456789ABCDEF")
        }
    }
}
