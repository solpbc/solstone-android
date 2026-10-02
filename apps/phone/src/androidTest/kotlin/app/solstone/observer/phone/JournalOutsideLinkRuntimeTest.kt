// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.platform.work.syncStores
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalOutsideLinkRuntimeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val history = CopyOnWriteArrayList<String>()
    private val sessions = CopyOnWriteArrayList<JournalBrowserSession>()
    private val originUrl = AtomicReference<String?>()
    private val externalIntents = CopyOnWriteArrayList<Intent>()

    private enum class Page {
        EMPTY,
        INTERNAL_LINK,
        INTERNAL_TARGET_BLANK,
        OUTSIDE_TARGET_BLANK,
        OUTSIDE_SUBRESOURCES,
    }

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
        syncStores(context).journalConfirmationStore.confirm("client-fp")
        history.clear()
        sessions.clear()
        originUrl.set(null)
        externalIntents.clear()
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.onLoadUrl = { originUrl.set(it) }
        PhoneJournalTestHooks.onVisitedHistory = { url, _ -> history.add(url) }
        PhoneJournalTestHooks.externalViewStarter = { externalIntents.add(it) }
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
        PhoneJournalTestHooks.reset()
        sessions.forEach { runCatching { it.stop() } }
        sessions.clear()
        history.clear()
        externalIntents.clear()
        originUrl.set(null)
    }

    @Test
    fun scriptLocationToOutsideHttpsOpensOnceAndKeepsJournalLoaded() {
        launchJournal(Page.EMPTY).use { scenario ->
            val webView = requireNotNull(findDialogWebView())
            scenario.onActivity {
                webView.evaluateJavascript("location.assign(\"https://example.test/handoff\")", null)
            }

            composeRule.waitUntil(10_000) { externalIntents.size == 1 }
            assertExternalIntent(externalIntents.single(), "https://example.test/handoff")
            assertEquals(journalOriginHost(), latestHistoryHost())
            assertTrue(composeRule.onAllNodesWithText(UNAVAILABLE_NOTICE).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun outsideIframeAndImageRequestsDoNotOpenExternalView() {
        launchJournal(Page.OUTSIDE_SUBRESOURCES).use {
            waitForPage("/")
            composeRule.waitUntil(10_000) {
                val view = findDialogWebView() ?: return@waitUntil false
                var ready = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    ready = view.progress == 100 && view.contentHeight > 0
                }
                ready
            }
            assertTrue(externalIntents.isEmpty())
        }
    }

    @Test
    fun targetBlankAndInternalLinksStayOrHandoffInCurrentWebView() {
        launchJournal(Page.INTERNAL_LINK).use {
            tapWebView()
            waitForPage("/landed")
            assertTrue(externalIntents.isEmpty())
        }

        history.clear()
        externalIntents.clear()
        launchJournal(Page.INTERNAL_TARGET_BLANK).use {
            tapWebView()
            waitForPage("/landed-blank")
            assertTrue(externalIntents.isEmpty())
            assertTrue(composeRule.onAllNodesWithText(UNAVAILABLE_NOTICE).fetchSemanticsNodes().isEmpty())
        }

        history.clear()
        externalIntents.clear()
        launchJournal(Page.OUTSIDE_TARGET_BLANK).use {
            tapWebView()
            composeRule.waitUntil(10_000) { externalIntents.size == 1 }
            assertExternalIntent(externalIntents.single(), "https://example.test/handoff")
            assertEquals(journalOriginHost(), latestHistoryHost())
        }
    }

    @Test
    fun throwingStarterShowsNoticeAndDoesNotRetry() {
        val startTime = AtomicReference<Long?>()
        val starterCalls = CopyOnWriteArrayList<Intent>()
        PhoneJournalTestHooks.externalViewStarter = { intent ->
            starterCalls.add(intent)
            if (startTime.get() == null) startTime.set(SystemClock.uptimeMillis())
            throw IllegalStateException("no activity")
        }

        launchJournal(Page.EMPTY).use { scenario ->
            val webView = requireNotNull(findDialogWebView())
            scenario.onActivity {
                webView.evaluateJavascript("location.assign(\"https://example.test/handoff\")", null)
            }

            composeRule.waitUntil(10_000) {
                starterCalls.size == 1 &&
                    composeRule.onAllNodesWithText(UNAVAILABLE_NOTICE).fetchSemanticsNodes().isNotEmpty()
            }
            assertExternalIntent(starterCalls.single(), "https://example.test/handoff")
            assertEquals(journalOriginHost(), latestHistoryHost())

            val retryWindowEndsAt = requireNotNull(startTime.get()) + RETRY_OBSERVATION_MS
            composeRule.waitUntil(RETRY_OBSERVATION_MS + 1_000) {
                SystemClock.uptimeMillis() >= retryWindowEndsAt
            }
            assertEquals(1, starterCalls.size)
        }
    }

    private fun launchJournal(page: Page): ActivityScenario<PhoneShellActivity> {
        PhoneJournalTestHooks.sessionOverride = {
            val session = JournalBrowserSession(
                pairing = { PairingGeneration("inst1", "sha256:cert") },
                accessStillCurrent = { true },
                upstreamFactory = { ScriptedUpstream(page) },
                diag = {},
            )
            sessions.add(session)
            LiveJournalSheetSession(session)
        }
        val scenario = ActivityScenario.launch(PhoneShellActivity::class.java)
        composeRule.onNodeWithTag("journalMarkPill").performClick()
        waitForPage("/")
        return scenario
    }

    private fun waitForPage(path: String) {
        composeRule.waitUntil(10_000) {
            val last = history.lastOrNull() ?: return@waitUntil false
            val uri = runCatching { URI(last) }.getOrNull() ?: return@waitUntil false
            uri.host == journalOriginHost() && uri.path == path
        }
    }

    private fun journalOriginHost(): String =
        URI(requireNotNull(originUrl.get())).host

    private fun latestHistoryHost(): String =
        URI(history.last()).host

    private fun assertExternalIntent(intent: Intent, expectedUrl: String) {
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(expectedUrl, intent.dataString)
        assertNull(intent.extras)
        assertNull(intent.categories)
    }

    private fun findDialogWebView(): WebView? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var target: WebView? = null
        instrumentation.runOnMainSync {
            val global = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getInstance")
                .invoke(null)
            val viewsField = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
            val views = when (val raw = viewsField.get(global)) {
                is List<*> -> raw.filterIsInstance<View>()
                is Array<*> -> raw.filterIsInstance<View>()
                else -> emptyList()
            }
            for (root in views) {
                val found = findAttachedWebView(root)
                if (found != null && found.width > 0 && found.height > 0) {
                    target = found
                    break
                }
            }
        }
        return target
    }

    private fun findAttachedWebView(view: View): WebView? {
        if (view is WebView && view.isAttachedToWindow) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = findAttachedWebView(view.getChildAt(i))
                if (child != null) return child
            }
        }
        return null
    }

    private fun tapWebView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        composeRule.waitUntil(10_000) {
            val candidate = findDialogWebView() ?: return@waitUntil false
            var ready = false
            instrumentation.runOnMainSync {
                ready = candidate.progress == 100 && candidate.contentHeight > 0
            }
            ready
        }
        val webView = findDialogWebView() ?: error("WebView not found in dialog")
        instrumentation.runOnMainSync {
            webView.requestFocus()
            val x = webView.width / 2f
            val y = webView.height / 2f
            val downTime = SystemClock.uptimeMillis()
            val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val upEvent = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            webView.dispatchTouchEvent(downEvent)
            webView.dispatchTouchEvent(upEvent)
            downEvent.recycle()
            upEvent.recycle()
        }
    }

    private fun committedSnapshot(): PairingGraphSnapshot.Committed {
        val home = PairedHome(
            instanceId = "test-instance",
            homeLabel = "Test Journal",
            relayOrigin = null,
            caChainFingerprint = "ca-fp",
            clientCertFingerprint = "client-fp",
            observerHandle = "obs-1",
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        return PairingGraphSnapshot.Committed(
            sequenceNumber = 1L,
            revisions = GraphRevisions(1L, 1L, 1L),
            home = home,
            hasDirectEndpoint = false,
            directAssociated = false,
            relayLiveEligible = false,
        )
    }

    private class ScriptedUpstream(private val page: Page) : JournalBrowserUpstream {
        override val isPoisoned: Boolean get() = false
        override fun close() {}

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): BrowserHttpResponse {
            if (method != "GET") {
                return response(405, "text/plain", "Method Not Allowed")
            }
            return when (path.substringBefore('?')) {
                "/" -> response(200, "text/html; charset=utf-8", rootHtml())
                "/landed" -> response(200, "text/html; charset=utf-8", "<!doctype html><p>landed</p>")
                "/landed-blank" -> response(200, "text/html; charset=utf-8", "<!doctype html><p>landed blank</p>")
                else -> response(404, "text/plain", "Not Found")
            }
        }

        private fun rootHtml(): String {
            val control = when (page) {
                Page.EMPTY -> "<p>journal</p>"
                Page.INTERNAL_LINK -> fullBleedAnchor("/landed", "internal")
                Page.INTERNAL_TARGET_BLANK -> fullBleedAnchor("/landed-blank", "internal blank", targetBlank = true)
                Page.OUTSIDE_TARGET_BLANK -> fullBleedAnchor(
                    "https://example.test/handoff",
                    "outside blank",
                    targetBlank = true,
                )
                Page.OUTSIDE_SUBRESOURCES ->
                    "<iframe src=\"https://example.test/handoff\"></iframe>" +
                        "<img src=\"https://example.test/handoff\">"
            }
            return "<!doctype html><html><body style=\"margin:0;padding:0;\">$control</body></html>"
        }

        private fun fullBleedAnchor(href: String, label: String, targetBlank: Boolean = false): String {
            val target = if (targetBlank) " target=\"_blank\"" else ""
            return "<a href=\"$href\"$target style=\"position:absolute;left:0;top:0;width:100%;height:100%\">$label</a>"
        }

        private fun response(status: Int, contentType: String, body: String) = BrowserHttpResponse(
            status = status,
            headers = listOf("Content-Type" to contentType),
            body = body.encodeToByteArray(),
        )
    }

    private companion object {
        const val UNAVAILABLE_NOTICE = "that link isn't available in the app."
        const val RETRY_OBSERVATION_MS = 1_000L
    }
}
