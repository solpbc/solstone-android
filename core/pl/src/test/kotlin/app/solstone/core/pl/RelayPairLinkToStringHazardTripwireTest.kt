// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertTrue

class RelayPairLinkToStringHazardTripwireTest {
    /**
     * This pins a known secret-rendering hazard so any behavior change is reviewed deliberately.
     * It is not an endorsement. Redaction belongs in separate work with its own blast-radius review.
     */
    @Test
    fun currentToStringRendersPairingSecretAndFingerprint() {
        val link = RelayPairLink(byteArrayOf(11, 22), byteArrayOf(33, 44), "https://relay.example")

        val rendered = link.toString()

        assertTrue(rendered.contains("s=[11, 22]"))
        assertTrue(rendered.contains("caFpSpki=[33, 44]"))
    }
}
