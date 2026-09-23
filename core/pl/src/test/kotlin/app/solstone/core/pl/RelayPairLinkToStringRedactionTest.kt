// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayPairLinkToStringRedactionTest {
    @Test
    fun toStringRedactsPairingSecretFingerprintAndOrigin() {
        val link = RelayPairLink(byteArrayOf(11, 22), byteArrayOf(33, 44), "https://relay.example")

        val rendered = link.toString()

        assertFalse(rendered.contains("[11, 22]"))
        assertFalse(rendered.contains("[33, 44]"))
        assertFalse(rendered.contains("https://relay.example"))
        assertTrue(rendered.contains("s=<redacted>"))
        assertTrue(rendered.contains("caFpSpki=<redacted>"))
        assertTrue(rendered.contains("relayOrigin=<custom>"))
    }
}
