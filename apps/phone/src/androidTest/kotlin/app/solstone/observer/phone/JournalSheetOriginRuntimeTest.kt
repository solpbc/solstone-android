// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.BrowserTerminalClass
import app.solstone.core.pl.browser.BrowserTerminalReason
import app.solstone.core.pl.browser.JournalBrowserOriginKeeper
import app.solstone.core.pl.browser.JournalBrowserOriginRecord
import app.solstone.core.pl.browser.JournalBrowserOriginStore
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.platform.work.syncStores
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The journal's origin lasts the pairing, and its listener outlives the page in the real sheet.
 *
 * The page opens `EventSource('/sse/events')` as the journal does, and reconnects by itself. When
 * the sheet closes, or the session ends under it, another app spinning on the port must never be
 * handed one of its requests.
 */
@RunWith(AndroidJUnit4::class)
class JournalSheetOriginRuntimeTest {
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
    private val loadedUrls = CopyOnWriteArrayList<String>()
    private val sessions = CopyOnWriteArrayList<JournalBrowserSession>()
    private val latestSession = AtomicReference<JournalBrowserSession?>()
    private val streamsOpened = AtomicInteger(0)
    private val keeper = JournalBrowserOriginKeeper(MemoryOriginStore())
    private var upstreamFactory: () -> JournalBrowserUpstream = { LiveChannelUpstream(streamsOpened) }

    private class MemoryOriginStore : JournalBrowserOriginStore {
        @Volatile var record: JournalBrowserOriginRecord? = null
        override fun read() = record
        override fun write(record: JournalBrowserOriginRecord) { this.record = record }
        override fun clear() { record = null }
    }

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
        syncStores(context).journalConfirmationStore.confirm("client-fp")
        PhoneJournalTestHooks.onLoadUrl = { loadedUrls.add(it) }
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = {
            val session = JournalBrowserSession(
                pairing = { PairingGeneration("inst1", "sha256:cert") },
                accessStillCurrent = { true },
                upstreamFactory = { upstreamFactory() },
                diag = {},
                originKeeper = keeper,
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
        keeper.clear()
    }

    @Test
    fun theOriginIsKeptWhenTheSheetClosesAndOpensAgain() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            val first = openAndAwaitLiveChannel()
            closeSheet()
            val second = openAndAwaitLiveChannel()
            assertEquals(first, second)
            closeSheet()
        }
    }

    @Test
    fun closingTheSheetUnderALiveChannelHandsNothingToAnotherApp() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            val origin = openAndAwaitLiveChannel()
            Squatter(origin.port).use { squatter ->
                closeSheet()
                Thread.sleep(2_000)
                assertFalse("the port stays ours while the origin is in use", squatter.bound.get())
                assertEquals("handed: ${squatter.heads}", 0, squatter.received.get())

                // The next open is served from the port the process kept.
                assertEquals(origin, openAndAwaitLiveChannel())
                closeSheet()
                assertEquals("handed: ${squatter.heads}", 0, squatter.received.get())
            }
        }
    }

    @Test
    fun aSessionEndedUnderALiveChannelHandsNothingToAnotherApp() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            val origin = openAndAwaitLiveChannel()
            Squatter(origin.port).use { squatter ->
                latestSession.get()!!.triggerTerminal(
                    BrowserTerminalReason(BrowserTerminalClass.SESSION_CARRIER_LOSS),
                )
                // The pane swaps to its failure card, which destroys the web view.
                composeRule.waitUntil(10_000) {
                    composeRule.onAllNodesWithText("try again").fetchSemanticsNodes().isNotEmpty()
                }
                Thread.sleep(2_000)
                assertFalse("the port stays ours while the origin is in use", squatter.bound.get())
                assertEquals("handed: ${squatter.heads}", 0, squatter.received.get())
            }
            closeSheet()
        }
    }

    @Test
    fun journalErrorPagesRemainVisibleWhateverTheirHttpStatus() {
        for (status in listOf(404, 500, 502, 504)) {
            launchErrorPage(status).use {
                composeRule.waitUntil(10_000) {
                    val view = findDialogWebView() ?: return@waitUntil false
                    var rendered = false
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        rendered = view.progress == 100 && view.title == "journal-response-$status"
                    }
                    rendered
                }
                assertEquals(0, composeRule.onAllNodesWithText("try again").fetchSemanticsNodes().size)
                closeSheet()
            }
        }
    }

    @Test
    fun transportFailureStillShowsTheAppsRetryAction() {
        launchErrorPage(null).use {
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithText("try again").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(null, findDialogWebView())
            closeSheet()
        }
    }

    private fun launchErrorPage(status: Int?): ActivityScenario<PhoneShellActivity> {
        upstreamFactory = {
            object : JournalBrowserUpstream {
                override val isPoisoned: Boolean get() = false
                override fun request(
                    method: String,
                    path: String,
                    headers: Map<String, String>,
                    body: ByteArray?,
                ): BrowserHttpResponse {
                    val isLink = path.startsWith("/app/open/")
                    if (isLink && status == null) throw IOException("fixture transport failure")
                    val code = if (isLink) requireNotNull(status) else 200
                    return BrowserHttpResponse(
                        status = code,
                        headers = listOf("Content-Type" to "text/html; charset=utf-8"),
                        body = "<!doctype html><title>journal-response-$code</title><p>fixture page</p>".encodeToByteArray(),
                    )
                }
                override fun close() {}
            }
        }
        val scenario = ActivityScenario.launch(PhoneShellActivity::class.java)
        try {
            val urlsBefore = loadedUrls.size
            composeRule.onNodeWithTag("journalMarkPill").performClick()
            composeRule.waitUntil(10_000) {
                if (loadedUrls.size <= urlsBefore) return@waitUntil false
                val view = findDialogWebView() ?: return@waitUntil false
                var ready = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    ready = view.progress == 100 && view.title == "journal-response-200"
                }
                ready
            }
            val webView = requireNotNull(findDialogWebView())
            scenario.onActivity {
                webView.evaluateJavascript("location.assign('/app/open/fixture')", null)
            }
            return scenario
        } catch (failure: Throwable) {
            scenario.close()
            throw failure
        }
    }

    private fun findDialogWebView(): WebView? {
        var target: WebView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
            val viewsField = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
            val roots = when (val raw = viewsField.get(global)) {
                is List<*> -> raw.filterIsInstance<View>()
                is Array<*> -> raw.filterIsInstance<View>()
                else -> emptyList()
            }
            fun find(view: View): WebView? {
                if (view is WebView && view.isAttachedToWindow && view.width > 0 && view.height > 0) return view
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            for (root in roots) find(root)?.let { target = it }
        }
        return target
    }

    private fun openAndAwaitLiveChannel(): URI {
        val urlsBefore = loadedUrls.size
        val streamsBefore = streamsOpened.get()
        composeRule.onNodeWithTag("journalMarkPill").performClick()
        composeRule.waitUntil(15_000) { loadedUrls.size > urlsBefore && streamsOpened.get() > streamsBefore }
        return URI(loadedUrls.last())
    }

    private fun closeSheet() {
        composeRule.onNodeWithText("close").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("close").fetchSemanticsNodes().isEmpty()
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

    /** A page holding the journal's live channel open, and the channel itself. */
    private class LiveChannelUpstream(private val streamsOpened: AtomicInteger) : JournalBrowserUpstream {
        private val closed = CountDownLatch(1)
        override val isPoisoned: Boolean get() = false

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): BrowserHttpResponse = BrowserHttpResponse(
            status = 200,
            headers = listOf("Content-Type" to "text/html; charset=utf-8"),
            body = PAGE.encodeToByteArray(),
        )

        override fun requestStreaming(
            method: String,
            path: String,
            headers: List<Pair<String, String>>,
            bodySource: BrowserRequestBodySource?,
            responseSink: BrowserResponseSink,
        ) {
            if (path.substringBefore('?') != "/sse/events") {
                super.requestStreaming(method, path, headers, bodySource, responseSink)
                return
            }
            streamsOpened.incrementAndGet()
            responseSink.onStatusAndHeaders(
                200,
                "OK",
                listOf(
                    "Content-Type" to "text/event-stream",
                    "Cache-Control" to "no-store",
                    "Transfer-Encoding" to "chunked",
                ),
            )
            val event = "retry: 20\n\ndata: hello\n\n".encodeToByteArray()
            responseSink.onBodyChunk(event, 0, event.size)
            closed.await(60, TimeUnit.SECONDS)
            throw IOException("carrier closed")
        }

        override fun close() {
            closed.countDown()
        }

        private companion object {
            const val PAGE = """<!DOCTYPE html><html><body><p>journal</p>
<script>new EventSource('/sse/events');</script></body></html>"""
        }
    }

    /** Another app spinning on `bind()` for the port, counting every connection it is handed. */
    private class Squatter(private val port: Int) : AutoCloseable {
        val received = AtomicInteger(0)
        val heads = CopyOnWriteArrayList<String>()
        val bound = AtomicBoolean(false)
        private val running = AtomicBoolean(true)
        private val thread = Thread({ run() }, "squatter").apply {
            isDaemon = true
            start()
        }

        private fun run() {
            var server: ServerSocket? = null
            while (running.get() && server == null) {
                server = try {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                    }
                } catch (_: IOException) {
                    null
                }
            }
            server ?: return
            server.use { ss ->
                bound.set(true)
                ss.soTimeout = 50
                while (running.get()) {
                    try {
                        ss.accept().use { client ->
                            received.incrementAndGet()
                            client.soTimeout = 2_000
                            val head = try {
                                val buf = ByteArray(256)
                                val n = client.getInputStream().read(buf)
                                if (n <= 0) "no bytes (eof)" else String(buf, 0, n).substringBefore("\r\n")
                            } catch (_: SocketTimeoutException) {
                                "no bytes (timeout)"
                            }
                            heads.add(head)
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        }

        override fun close() {
            running.set(false)
            thread.join(5_000)
        }
    }
}
