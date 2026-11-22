// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.pl.parseJson
import app.solstone.platform.persistence.room.ImportHandoffStatus
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalAuthHostTest {
    private val origin = "http://0123456789abcdef0123456789abcdef.localhost:7657"
    private val importContract = assertNotNull(
        parseImportHostContract(File("src/main/assets/journal-web-host/import-host-contract.json").readBytes()),
    )
    private val authContract = assertNotNull(
        parseAuthHostContract(File("src/main/assets/journal-web-host/auth-host-contract.json").readBytes()),
    )

    private class FakeTimerScheduler : JournalAuthTimerScheduler {
        data class Entry(val delayMs: Long, val task: () -> Unit, var cancelled: Boolean = false)

        val entries = mutableListOf<Entry>()

        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val entry = Entry(delayMs, task)
            entries += entry
            return { entry.cancelled = true }
        }

        fun fireLatest() {
            val entry = entries.lastOrNull { !it.cancelled } ?: return
            entry.task()
        }
    }

    private fun makeHost(
        timer: FakeTimerScheduler = FakeTimerScheduler(),
        onOpen: (String, String) -> Boolean = { _, _ -> true },
    ): Pair<JournalImportHost<String>, JournalAuthHost> {
        val auth = JournalAuthHost(
            contract = authContract,
            openBrowser = onOpen,
            timerScheduler = timer,
        )
        val importHost = JournalImportHost<String>(
            origin = origin,
            contract = importContract,
            authHost = auth,
            openPicker = { _, _ -> },
            itemFor = { name -> IntakeItem.Stream(name, null, "content", 1, ByteArrayInputStream(byteArrayOf(1))) },
            submit = { _, done -> done(IntakeOutcome(emptyList(), emptyList())) },
            status = { ImportHandoffStatus("gone") },
            background = { it() },
        )
        return importHost to auth
    }

    private fun send(
        host: JournalImportHost<String>,
        messageJson: String,
        replies: MutableList<Map<*, *>>,
        from: String = origin,
        mainFrame: Boolean = true,
    ) {
        val packet = """{"seq":${replies.size + 1},"message":$messageJson,"context":null}"""
        host.onMessage(packet, from, mainFrame) { text ->
            val parsed = parseJson(text) as Map<*, *>
            replies += parsed["body"] as Map<*, *>
        }
    }

    @Test
    fun bundledAuthContractAndScriptAdvertiseV2AuthTypes() {
        assertEquals("solstoneJournalHost", authContract.capability)
        assertEquals("auth.prepare", authContract.prepare)
        assertEquals("auth.prepared", authContract.prepared)
        assertEquals("auth.authorize", authContract.authorize)
        assertEquals("auth.cancel", authContract.cancel)
        assertEquals("auth.result", authContract.result)
        assertEquals("unsupported", authContract.unsupported)
        assertNull(parseAuthHostContract("""{"version":1,"capability":"solstoneJournalHost"}""".toByteArray()))

        val template = File("src/main/assets/journal-web-host/import-host.js").readText()
        val script = journalImportHostScript(template, importContract, origin, authContract)
        assertTrue(script.contains("\"prepare\": \"auth.prepare\""))
        assertTrue(script.contains("\"authorize\": \"auth.authorize\""))
        assertTrue(script.contains("\"cancel\": \"auth.cancel\""))
        assertTrue("__" !in script.substringAfterLast("})("))
    }

    @Test
    fun happyPathBothAllowlistedSchemesAndNonConsumingRefusalOnBadStateOrDuplicates() {
        val launches = mutableListOf<Pair<String, String>>()
        val (host, auth) = makeHost { url, scheme ->
            launches += url to scheme
            true
        }
        val replies = mutableListOf<Map<*, *>>()

        // 1. solstone://oauth/callback (skipping loopback preference ahead of it)
        send(
            host,
            """{"type":"auth.prepare","id":"p1","preferences":[{"mode":"loopback","host":"127.0.0.1","path":"/cb"},{"mode":"custom_scheme","uri":"solstone://oauth/callback"}]}""",
            replies,
        )
        val p1 = replies.last()
        assertEquals("auth.prepared", p1["type"])
        assertEquals("p1", p1["id"])
        assertEquals("ready", p1["outcome"])
        assertEquals("custom_scheme", p1["mode"])
        assertEquals("solstone://oauth/callback", p1["redirect_uri"])
        val sid1 = p1["session_id"] as String

        send(
            host,
            """{"type":"auth.authorize","id":"a1","session_id":"$sid1","authorize_url":"https://provider.example/authorize?client_id=abc","expected_state":"state_one_123"}""",
            replies,
        )
        assertEquals(1, launches.size)
        assertEquals("https://provider.example/authorize?client_id=abc" to "solstone", launches[0])

        // Stray/malicious callbacks MUST be refused WITHOUT consuming the session.
        assertFalse(auth.onCallbackUrl("solstone://oauth/callback?code=c&state=wrong"))
        assertFalse(auth.onCallbackUrl("solstone://oauth/callback?code=c&state=state_one_123&state=state_one_123"))
        assertFalse(auth.onCallbackUrl("solstone://oauth/callback?code=c1&code=c2&state=state_one_123"))
        assertFalse(auth.onCallbackUrl("solstone://oauth/other?code=c&state=state_one_123"))
        assertFalse(auth.onCallbackUrl("solstone://other/callback?code=c&state=state_one_123"))
        assertFalse(auth.onCallbackUrl("solstone://oauth/callback?state=state_one_123"))
        assertFalse(auth.onCallbackUrl("app.solstone:/callback?code=c&state=state_one_123"))
        assertEquals(1, replies.size, "invalid callbacks must not settle authorize")

        // Valid callback succeeds and consumes session.
        val valid1 = "solstone://oauth/callback?code=real_code_1&state=state_one_123"
        assertTrue(auth.onCallbackUrl(valid1))
        val r1 = replies.last()
        assertEquals("auth.result", r1["type"])
        assertEquals("a1", r1["id"])
        assertEquals("callback", r1["outcome"])
        assertEquals(valid1, r1["callback_url"])
        assertFalse(auth.onCallbackUrl(valid1), "single-use session cannot accept replay")

        // 2. Hostless app.solstone:/callback (RFC 8252 reverse-DNS custom scheme)
        send(
            host,
            """{"type":"auth.prepare","id":"p2","preferences":[{"mode":"custom_scheme","uri":"app.solstone:/callback"}]}""",
            replies,
        )
        val p2 = replies.last()
        assertEquals("ready", p2["outcome"])
        assertEquals("app.solstone:/callback", p2["redirect_uri"])
        val sid2 = p2["session_id"] as String

        send(
            host,
            """{"type":"auth.authorize","id":"a2","session_id":"$sid2","authorize_url":"https://accounts.example/o/oauth2/v2/auth","expected_state":"state_two_456"}""",
            replies,
        )
        assertEquals("app.solstone", launches[1].second)

        // Authority-bearing smuggling and wrong paths MUST be rejected without consuming session.
        assertFalse(auth.onCallbackUrl("app.solstone://callback?code=c&state=state_two_456"))
        assertFalse(auth.onCallbackUrl("app.solstone://evil/callback?code=c&state=state_two_456"))
        assertFalse(auth.onCallbackUrl("app.solstone:///callback?code=c&state=state_two_456"))
        assertFalse(auth.onCallbackUrl("app.solstone:/other?code=c&state=state_two_456"))

        val valid2 = "app.solstone:/callback?code=google_code&state=state_two_456"
        assertTrue(auth.onCallbackUrl(valid2))
        assertEquals("callback", replies.last()["outcome"])
        assertEquals(valid2, replies.last()["callback_url"])
    }

    @Test
    fun loopbackOnlyAndUnallowlistedCustomSchemesReturnNoCompatibleMode() {
        val (host, _) = makeHost()
        val replies = mutableListOf<Map<*, *>>()

        send(
            host,
            """{"type":"auth.prepare","id":"p-lb","preferences":[{"mode":"loopback","host":"127.0.0.1","path":"/cb","port":0}]}""",
            replies,
        )
        assertEquals(
            mapOf("type" to "auth.prepared", "id" to "p-lb", "outcome" to "no_compatible_mode"),
            replies.last(),
        )

        send(
            host,
            """{"type":"auth.prepare","id":"p-evil","preferences":[{"mode":"custom_scheme","uri":"evil://oauth/callback"},{"mode":"custom_scheme","uri":"solstone://other/path"}]}""",
            replies,
        )
        assertEquals(
            mapOf("type" to "auth.prepared", "id" to "p-evil", "outcome" to "no_compatible_mode"),
            replies.last(),
        )
    }

    @Test
    fun invalidAuthorizeUrlsAreRefusedWithoutLaunchingBrowser() {
        var launched = false
        val (host, _) = makeHost { _, _ ->
            launched = true
            true
        }
        val replies = mutableListOf<Map<*, *>>()
        send(
            host,
            """{"type":"auth.prepare","id":"p1","preferences":[{"mode":"custom_scheme","uri":"solstone://oauth/callback"}]}""",
            replies,
        )
        val sid = replies.last()["session_id"] as String

        val badUrls = listOf(
            "http://provider.example/auth",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:text/html,hi",
            "https://user:pass@provider.example/auth",
            "https://provider.example/auth with space",
            "https://provider.example/${"a".repeat(8200)}",
        )
        for (bad in badUrls) {
            send(
                host,
                """{"type":"auth.authorize","id":"a-bad","session_id":"$sid","authorize_url":"$bad","expected_state":"st"}""",
                replies,
            )
            assertEquals("refused", replies.last()["outcome"], "must refuse $bad")
            assertEquals("invalid_authorize_url", replies.last()["reason"], "must refuse $bad")
        }
        assertFalse(launched)
    }

    @Test
    fun cancelSupersedingPrepareTeardownTimeoutAndTopFrameOriginIsolation() {
        // 1. Explicit auth.cancel settles in-flight auth.authorize as cancelled.
        run {
            val (host, _) = makeHost()
            val replies = mutableListOf<Map<*, *>>()
            send(
                host,
                """{"type":"auth.prepare","id":"p1","preferences":[{"mode":"custom_scheme","uri":"solstone://oauth/callback"}]}""",
                replies,
            )
            val sid = replies.last()["session_id"] as String
            send(
                host,
                """{"type":"auth.authorize","id":"a1","session_id":"$sid","authorize_url":"https://provider.example/auth","expected_state":"st1"}""",
                replies,
            )
            send(host, """{"type":"auth.cancel","id":"c1","session_id":"$sid"}""", replies)
            assertTrue(
                replies.any { it["id"] == "a1" && it["outcome"] == "cancelled" },
                "in-flight authorize must settle as cancelled",
            )
            assertTrue(
                replies.any { it["id"] == "c1" && it["outcome"] == "cancelled" },
                "cancel must reply cancelled",
            )
        }

        // 2. Superseding auth.prepare settles prior in-flight auth.authorize as cancelled.
        run {
            val (host, _) = makeHost()
            val replies = mutableListOf<Map<*, *>>()
            send(
                host,
                """{"type":"auth.prepare","id":"p1","preferences":[{"mode":"custom_scheme","uri":"solstone://oauth/callback"}]}""",
                replies,
            )
            val sid1 = replies.last()["session_id"] as String
            send(
                host,
                """{"type":"auth.authorize","id":"a1","session_id":"$sid1","authorize_url":"https://provider.example/auth","expected_state":"st1"}""",
                replies,
            )
            send(
                host,
                """{"type":"auth.prepare","id":"p2","preferences":[{"mode":"custom_scheme","uri":"app.solstone:/callback"}]}""",
                replies,
            )
            assertTrue(replies.any { it["id"] == "a1" && it["outcome"] == "cancelled" })
            assertTrue(replies.any { it["id"] == "p2" && it["outcome"] == "ready" })
        }

        // 3. Document navigation / teardown settles in-flight auth.authorize as cancelled.
        run {
            val (host, _) = makeHost()
            val replies = mutableListOf<Map<*, *>>()
            send(
                host,
                """{"type":"auth.prepare","id":"p1","preferences":[{"mode":"custom_scheme","uri":"solstone://oauth/callback"}]}""",
                replies,
            )
            val sid = replies.last()["session_id"] as String
            send(
                host,
                """{"type":"auth.authorize","id":"a1","session_id":"$sid","authorize_url":"https://provider.example/auth","expected_state":"st1"}""",
                replies,
            )
            host.documentStarted()
            assertEquals("cancelled", replies.last()["outcome"])
            assertEquals("a1", replies.last()["id"])
        }

        // 4. Timeout settles in-flight auth.authorize with outcome "timeout" (clamped 1..600s).
        run {
            val timer = FakeTimerScheduler()
            val (host, _) = makeHost(timer = timer)
            val replies = mutableListOf<Map<*, *>>()
            send(
                host,
                """{"type":"auth.prepare","id":"p1","preferences":[{"mode":"custom_scheme","uri":"solstone://oauth/callback"}]}""",
                replies,
            )
            val sid = replies.last()["session_id"] as String
            send(
                host,
                """{"type":"auth.authorize","id":"a1","session_id":"$sid","authorize_url":"https://provider.example/auth","expected_state":"st1","timeout_seconds":9999}""",
                replies,
            )
            assertEquals(600_000L, timer.entries.last().delayMs)
            timer.fireLatest()
            assertEquals("timeout", replies.last()["outcome"])
            assertEquals("a1", replies.last()["id"])
        }

        // 5. Subframe and wrong-origin auth messages are ignored completely.
        run {
            val (host, _) = makeHost()
            val replies = mutableListOf<Map<*, *>>()
            send(
                host,
                """{"type":"auth.prepare","id":"sf","preferences":[{"mode":"custom_scheme","uri":"solstone://oauth/callback"}]}""",
                replies,
                mainFrame = false,
            )
            send(
                host,
                """{"type":"auth.prepare","id":"wo","preferences":[{"mode":"custom_scheme","uri":"solstone://oauth/callback"}]}""",
                replies,
                from = "https://evil.example",
            )
            assertTrue(replies.isEmpty())
        }

        // 6. OAuth callback URI recognizer distinguishes OAuth callbacks from pairing / journal links.
        assertTrue(JournalAuthValidation.isOAuthCallbackUrl("solstone://oauth/callback?code=1&state=s"))
        assertTrue(JournalAuthValidation.isOAuthCallbackUrl("app.solstone:/callback?code=1&state=s"))
        assertFalse(JournalAuthValidation.isOAuthCallbackUrl("https://go.solstone.app/p#abc"))
        assertFalse(JournalAuthValidation.isOAuthCallbackUrl("https://go.solstone.app/j/settings"))
        assertFalse(JournalAuthValidation.isOAuthCallbackUrl("solstone://pair?token=1"))
    }
}
