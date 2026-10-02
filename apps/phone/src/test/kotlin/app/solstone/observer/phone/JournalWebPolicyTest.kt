// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalWebPolicyTest {
    private val origin = "http://0123456789abcdef0123456789abcdef.localhost:7657/"
    private val policy = JournalWebPolicy(origin)

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

        val defaultPortPolicy = JournalWebPolicy("http://example.test/")
        assertTrue(defaultPortPolicy.allows("http://example.test:80/a"))
        assertFalse(policy.allows("http://0123456789abcdef0123456789abcdef.localhost/a"))
    }

    @Test
    fun exactOriginLoadsAndOutsideHttpAndHttpsAreDispatched() {
        val exactUrl = "http://0123456789abcdef0123456789abcdef.localhost:7657/a?b=c"
        val atInPathUrl = "http://0123456789abcdef0123456789abcdef.localhost:7657/a@b"
        val exactDecision = policy.decide(exactUrl, isMainFrame = true)
        assertFalse(exactDecision.consume)
        assertNull(exactDecision.openUrl)
        assertFalse(exactDecision.notifyIfGesture)
        assertFalse(policy.decide(atInPathUrl, isMainFrame = true).consume)
        var exactOpenCount = 0
        var exactNoticeCount = 0
        assertFalse(
            applyJournalWebNavigation(
                exactDecision,
                hasGesture = true,
                open = { exactOpenCount++ },
                notify = { exactNoticeCount++ },
            ),
        )
        assertEquals(0, exactOpenCount)
        assertEquals(0, exactNoticeCount)

        listOf("http://example.test/help", "https://example.test/help").forEach { url ->
            listOf(true, false).forEach { hasGesture ->
                val decision = policy.decide(url, isMainFrame = true)
                val opened = mutableListOf<String>()
                var noticeCount = 0
                assertTrue(
                    applyJournalWebNavigation(
                        decision,
                        hasGesture,
                        open = { opened.add(it) },
                        notify = { noticeCount++ },
                    ),
                )
                assertEquals(listOf(url), opened)
                assertEquals(0, noticeCount)
            }
            val subframeDecision = policy.decide(url, isMainFrame = false)
            assertTrue(subframeDecision.consume)
            assertNull(subframeDecision.openUrl)
            assertFalse(subframeDecision.notifyIfGesture)
            var subframeOpens = 0
            var subframeNotices = 0
            assertTrue(
                applyJournalWebNavigation(
                    subframeDecision,
                    hasGesture = true,
                    open = { subframeOpens++ },
                    notify = { subframeNotices++ },
                ),
            )
            assertEquals(0, subframeOpens)
            assertEquals(0, subframeNotices)
        }

        val decisionOnly = policy.decide("https://example.test/help", isMainFrame = true)
        assertTrue(decisionOnly.consume)
        assertEquals("https://example.test/help", decisionOnly.openUrl)
        assertFalse(decisionOnly.notifyIfGesture)
    }

    @Test
    fun adjacentAndUnsafeUrlsAreConsumedAndOnlyGestureNotified() {
        listOf(
            "http://0123456789abcdef0123456789abcdef.localhost:7658/",
            "https://0123456789abcdef0123456789abcdef.localhost:7657/",
            "http://user@0123456789abcdef0123456789abcdef.localhost:7657/",
            "https://example.test/path@value",
            "http://0123456789abcdef0123456789abcdef.localhost.evil.test/",
            "https://evil.0123456789abcdef0123456789abcdef.localhost/",
            "javascript:alert(1)",
            "data:text/html,hello",
            "file:///tmp/a",
            "content://files/a",
            "intent://open",
            "blob:https://example.test/id",
            "about:blank",
            "not a url",
        ).forEach(::assertBlocked)

        val localHostUrl = "http://localhost:7657/"
        val localHostDecision = policy.decide(localHostUrl, isMainFrame = true)
        assertTrue(localHostDecision.consume)
        assertEquals(localHostUrl, localHostDecision.openUrl)
        var opened: String? = null
        assertTrue(
            applyJournalWebNavigation(
                localHostDecision,
                hasGesture = false,
                open = { opened = it },
                notify = {},
            ),
        )
        assertEquals(localHostUrl, opened)
    }

    @Test
    fun openerFailureNotifiesOnceAndSuccessfulOpenDoesNotNotify() {
        val decision = policy.decide("https://example.test/help", isMainFrame = true)
        var openCount = 0
        var noticeCount = 0
        assertTrue(
            applyJournalWebNavigation(
                decision,
                hasGesture = false,
                open = {
                    openCount++
                    error("no activity")
                },
                notify = { noticeCount++ },
            ),
        )
        assertEquals(1, openCount)
        assertEquals(1, noticeCount)

        var successfulNoticeCount = 0
        assertTrue(
            applyJournalWebNavigation(
                decision,
                hasGesture = true,
                open = {},
                notify = { successfulNoticeCount++ },
            ),
        )
        assertEquals(0, successfulNoticeCount)
    }

    @Test
    fun insetFallbackTracksEmbeddedWebViewMilestones() {
        assertEquals(JournalWebInsetPolicy(true, true), journalWebInsetPolicy(null))
        assertEquals(JournalWebInsetPolicy(true, true), journalWebInsetPolicy("138.0.0.0"))
        assertEquals(JournalWebInsetPolicy(true, false), journalWebInsetPolicy("139.0.0.0"))
        assertEquals(JournalWebInsetPolicy(true, false), journalWebInsetPolicy("143.0.0.0"))
        assertEquals(JournalWebInsetPolicy(false, false), journalWebInsetPolicy("144.0.0.0"))
    }

    private fun assertBlocked(url: String) {
        val decision = policy.decide(url, isMainFrame = true)
        assertTrue(decision.consume, url)
        assertNull(decision.openUrl, url)
        assertTrue(decision.notifyIfGesture, url)

        var openCount = 0
        var noticeCount = 0
        assertTrue(
            applyJournalWebNavigation(
                decision,
                hasGesture = true,
                open = { openCount++ },
                notify = { noticeCount++ },
            ),
            url,
        )
        assertEquals(0, openCount, url)
        assertEquals(1, noticeCount, url)

        assertTrue(
            applyJournalWebNavigation(
                decision,
                hasGesture = false,
                open = { openCount++ },
                notify = { noticeCount++ },
            ),
            url,
        )
        assertEquals(0, openCount, url)
        assertEquals(1, noticeCount, url)

        val subframeDecision = policy.decide(url, isMainFrame = false)
        assertTrue(subframeDecision.consume, url)
        assertNull(subframeDecision.openUrl, url)
        assertFalse(subframeDecision.notifyIfGesture, url)
        assertTrue(
            applyJournalWebNavigation(
                subframeDecision,
                hasGesture = true,
                open = { openCount++ },
                notify = { noticeCount++ },
            ),
            url,
        )
        assertEquals(0, openCount, url)
        assertEquals(1, noticeCount, url)
    }
}
