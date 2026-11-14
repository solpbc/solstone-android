// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PhoneJournalLinkTest {
    @Test
    fun preservesRawTokenAndMomentAtBothBounds() {
        listOf("a", "AZaz09_-", "a".repeat(512), "b".repeat(512) + "~235959").forEach { token ->
            val result = assertIs<PhoneJournalLink.Open>(phoneJournalLink("https://go.solstone.app/j#$token"))
            assertEquals("/app/open/$token", result.path)
            assertFalse(result.toString().contains(result.path))
        }
    }

    @Test
    fun malformedJournalLinksAreRefusedWithoutDecoding() {
        listOf("", "a".repeat(513), "abc=", "abc+", "abc/", "abc%2D", "abc~12345", "abc~1234567",
            "abc~12a456", "abc~123456~123456", "abc#def", "abc\n").forEach { token ->
            assertIs<PhoneJournalLink.Invalid>(phoneJournalLink("https://go.solstone.app/j#$token"))
        }
        listOf("https://go.solstone.app/j", "https://go.solstone.app/j?x=1#abc",
            "https://user@go.solstone.app/j#abc", "http://go.solstone.app/j#abc",
            "https://go.solstone.app:443/j#abc", "https://other.test/j#abc").forEach {
            assertIs<PhoneJournalLink.Invalid>(phoneJournalLink(it))
        }
    }

    @Test
    fun pairingAndLauncherRemainSeparate() {
        assertIs<PhoneJournalLink.Other>(phoneJournalLink(null))
        assertIs<PhoneJournalLink.Other>(phoneJournalLink("https://go.solstone.app/p#abc"))
    }
}
