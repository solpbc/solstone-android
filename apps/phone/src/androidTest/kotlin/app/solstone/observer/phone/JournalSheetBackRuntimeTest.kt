// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
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
import app.solstone.core.pl.browser.BrowserTerminalClass
import app.solstone.core.pl.browser.BrowserTerminalReason
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalSheetBackRuntimeTest {
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
    private val history = CopyOnWriteArrayList<Hist>()
    private val sessions = CopyOnWriteArrayList<JournalBrowserSession>()
    private val latestSession = AtomicReference<JournalBrowserSession?>()

    private data class Hist(val url: String, val canGoBack: Boolean)

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
        history.clear()
        sessions.clear()
        latestSession.set(null)
        PhoneJournalTestHooks.onVisitedHistory = { url, canGoBack ->
            history.add(Hist(url, canGoBack))
        }
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = {
            val session = JournalBrowserSession(
                pairing = { PairingGeneration("inst1", "sha256:cert") },
                accessStillCurrent = { true },
                upstreamFactory = { ScriptedUpstream() },
                diag = {},
            )
            sessions.add(session)
            latestSession.set(session)
            LiveJournalSheetSession(session)
        }
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
        PhoneJournalTestHooks.reset()
        sessions.forEach { runCatching { it.stop() } }
        sessions.clear()
        history.clear()
        latestSession.set(null)
    }

    @Test
    fun backWalksFullLoadAndPushStateThenDismissesSheet() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.onNodeWithTag("journalMarkPill").performClick()

            // Wait until /a loaded
            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/a" && !last.canGoBack
            }
            composeRule.onNodeWithText("close").assertExists()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // Tap center to load /b
            tapWebView(0.5f, 0.5f)

            // Wait until /b loaded, no fragment, canGoBack == true
            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/b" && uri.fragment == null && last.canGoBack
            }
            composeRule.onNodeWithText("close").assertExists()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // Tap top half for pushState to #x
            tapWebView(0.5f, 0.25f)

            // Wait until fragment == x and canGoBack == true
            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/b" && uri.fragment == "x" && last.canGoBack
            }
            composeRule.onNodeWithText("close").assertExists()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // Back 1: #x -> /b
            pressBackKey()
            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/b" && uri.fragment == null && last.canGoBack
            }
            composeRule.onNodeWithText("close").assertExists()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // Back 2: /b -> /a
            pressBackKey()
            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/a" && !last.canGoBack
            }
            composeRule.onNodeWithText("close").assertExists()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            // Back 3: dismiss sheet
            pressBackKey()
            assertSheetDismissed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun backOnFirstPageDismissesSheet() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.onNodeWithTag("journalMarkPill").performClick()

            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/a" && !last.canGoBack
            }
            composeRule.onNodeWithText("close").assertExists()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            pressBackKey()
            assertSheetDismissed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun backOnDeepLinkDismissesSheet() {
        val launchIntent = Intent(context, PhoneShellActivity::class.java).apply {
            putExtra(JournalPushPoster.EXTRA_JOURNAL_OPEN, "/app/health")
        }
        ActivityScenario.launch<PhoneShellActivity>(launchIntent).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/app/health" && !last.canGoBack
            }
            composeRule.onNodeWithText("close").assertExists()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            pressBackKey()
            assertSheetDismissed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun backOnMainFrameFailureDismissesSheet() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.onNodeWithTag("journalMarkPill").performClick()

            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/a"
            }

            tapWebView(0.5f, 0.5f)

            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/b"
            }

            tapWebView(0.5f, 0.75f)

            val lostMsg = "lost the connection to your journal. keep the solstone app open and try again."
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(lostMsg).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(lostMsg).assertExists()

            pressBackKey()
            assertSheetDismissed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun backAfterTryAgainDismissesSheet() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.onNodeWithTag("journalMarkPill").performClick()

            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/a"
            }

            tapWebView(0.5f, 0.5f)

            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/b"
            }

            tapWebView(0.5f, 0.75f)

            val lostMsg = "lost the connection to your journal. keep the solstone app open and try again."
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(lostMsg).fetchSemanticsNodes().isNotEmpty()
            }

            val historyCountBeforeRetry = history.size
            composeRule.onNodeWithText("try again").performClick()

            // Wait until a new history entry shows path /a and canGoBack == false
            composeRule.waitUntil(10_000) {
                if (history.size <= historyCountBeforeRetry) return@waitUntil false
                val last = history.last()
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/a" && !last.canGoBack
            }
            composeRule.onNodeWithText("close").assertExists()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            pressBackKey()
            assertSheetDismissed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun backAfterCarrierLossDismissesSheet() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.onNodeWithTag("journalMarkPill").performClick()

            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/a"
            }

            tapWebView(0.5f, 0.5f)

            composeRule.waitUntil(10_000) {
                val last = history.lastOrNull() ?: return@waitUntil false
                val uri = runCatching { URI(last.url) }.getOrNull()
                uri?.path == "/b" && last.canGoBack
            }

            latestSession.get()!!.triggerTerminal(BrowserTerminalReason(BrowserTerminalClass.SESSION_CARRIER_LOSS))

            val lostMsg = "lost the connection to your journal. keep the solstone app open and try again."
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText(lostMsg).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(lostMsg).assertExists()

            pressBackKey()
            assertSheetDismissed()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
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

    private fun tapWebView(xFraction: Float, yFraction: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // A history entry is recorded when a navigation commits, before the page has laid out.
        // A cold web view can take seconds longer to become tappable, so wait for the page itself.
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
            val w = webView.width.toFloat()
            val h = webView.height.toFloat()
            val x = w * xFraction
            val y = h * yFraction
            val downTime = SystemClock.uptimeMillis()
            val eventTime = downTime
            val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            val upEvent = MotionEvent.obtain(downTime, eventTime + 50, MotionEvent.ACTION_UP, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            webView.dispatchTouchEvent(downEvent)
            webView.dispatchTouchEvent(upEvent)
            downEvent.recycle()
            upEvent.recycle()
        }
    }

    private fun pressBackKey() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
    }

    private fun assertSheetDismissed() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("close").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("close").assertDoesNotExist()
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

    private class ScriptedUpstream : JournalBrowserUpstream {
        override val isPoisoned: Boolean get() = false
        override fun close() {}

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): BrowserHttpResponse {
            if (method != "GET") {
                return BrowserHttpResponse(
                    405,
                    listOf("Content-Type" to "text/plain"),
                    "Method Not Allowed".encodeToByteArray(),
                )
            }
            val cleanPath = path.substringBefore('?')
            return when (cleanPath) {
                "/" -> BrowserHttpResponse(
                    status = 302,
                    headers = listOf("Location" to "/a"),
                    body = ByteArray(0),
                )
                "/a" -> BrowserHttpResponse(
                    status = 200,
                    headers = listOf("Content-Type" to "text/html; charset=utf-8"),
                    body = """<!DOCTYPE html><html><body style="margin:0;padding:0;"><a href="/b" style="position:absolute;left:0;top:0;width:100%;height:100%">open b</a></body></html>""".encodeToByteArray(),
                )
                "/b" -> BrowserHttpResponse(
                    status = 200,
                    headers = listOf("Content-Type" to "text/html; charset=utf-8"),
                    body = """<!DOCTYPE html><html><body style="margin:0;padding:0;"><button type="button" onclick="history.pushState({s:1},'','#x')" style="position:absolute;left:0;top:0;width:100%;height:50%">push</button><a href="/fail" style="position:absolute;left:0;top:50%;width:100%;height:50%">fail</a></body></html>""".encodeToByteArray(),
                )
                "/fail" -> BrowserHttpResponse(
                    status = 500,
                    headers = listOf("Content-Type" to "text/plain"),
                    body = "Server Error".encodeToByteArray(),
                )
                "/app/health" -> BrowserHttpResponse(
                    status = 200,
                    headers = listOf("Content-Type" to "text/html; charset=utf-8"),
                    body = """<!DOCTYPE html><html><body><p>Health</p></body></html>""".encodeToByteArray(),
                )
                else -> BrowserHttpResponse(
                    status = 404,
                    headers = listOf("Content-Type" to "text/plain"),
                    body = "Not Found".encodeToByteArray(),
                )
            }
        }
    }
}
