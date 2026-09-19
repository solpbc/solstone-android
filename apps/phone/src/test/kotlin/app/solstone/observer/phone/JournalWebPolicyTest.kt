// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalWebPolicyTest {
    private val policy = JournalWebPolicy("http://0123456789abcdef0123456789abcdef.localhost:7657/")

    @Test
    fun onlyExactOriginIsAllowed() {
        assertTrue(policy.allows("http://0123456789abcdef0123456789abcdef.localhost:7657/a?b=c"))
        listOf(
            "https://0123456789abcdef0123456789abcdef.localhost:7657/",
            "http://0123456789abcdef0123456789abcdef.localhost:7658/",
            "http://localhost:7657/",
            "http://0123456789abcdef0123456789abcdef.localhost.evil.test:7657/",
            "javascript:alert(1)",
            "data:text/html,hello",
            "file:///tmp/a",
            "content://files/a",
            "intent://open",
            "not a url",
        ).forEach { assertFalse(policy.allows(it), it) }
    }

    @Test
    fun onlyTrustedHttpsMainFrameGestureCanLeaveTheApp() {
        assertTrue(policy.ownerInitiatedForeign("https://example.test/help", true, true))
        assertFalse(policy.ownerInitiatedForeign("https://example.test/help", true, false))
        assertFalse(policy.ownerInitiatedForeign("https://example.test/help", false, true))
        assertTrue(policy.ownerInitiatedForeign("http://example.test/help", true, true))
        assertFalse(
            policy.ownerInitiatedForeign(
                "http://0123456789abcdef0123456789abcdef.localhost:7657/a",
                true,
                true,
            ),
        )
    }

    @Test
    fun insetFallbackTracksEmbeddedWebViewMilestones() {
        assertEquals(JournalWebInsetPolicy(true, true), journalWebInsetPolicy(null))
        assertEquals(JournalWebInsetPolicy(true, true), journalWebInsetPolicy("138.0.0.0"))
        assertEquals(JournalWebInsetPolicy(true, false), journalWebInsetPolicy("139.0.0.0"))
        assertEquals(JournalWebInsetPolicy(true, false), journalWebInsetPolicy("143.0.0.0"))
        assertEquals(JournalWebInsetPolicy(false, false), journalWebInsetPolicy("144.0.0.0"))
    }
}
