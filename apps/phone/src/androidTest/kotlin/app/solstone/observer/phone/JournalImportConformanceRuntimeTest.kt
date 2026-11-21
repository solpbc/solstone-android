// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
import app.solstone.core.pl.browser.JournalBrowserOriginKeeper
import app.solstone.core.pl.browser.JournalBrowserOriginRecord
import app.solstone.core.pl.browser.JournalBrowserOriginStore
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.platform.work.syncStores
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The journal's import host conformance kit, run through the app's real journal window: the kit's
 * pages are served at the window's own journal origin, and the host, its message listener and the
 * system picker are the shipped ones.
 *
 * Operator-driven, so it runs only when asked: `-e importConformance 1`. When the kit opens the
 * picker the first time, dismiss it (Back); the second time, choose a file named `sample.txt`.
 * Each kit line is written to logcat under `ImportConformance`. The kit pages are the journal's
 * `contracts/journal-web-host/conformance/` at commit 5840dc1b3a43f47a40c5f0f21c3f4ead016e6bc2.
 */
@RunWith(AndroidJUnit4::class)
class JournalImportConformanceRuntimeTest {
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
    private val keeper = JournalBrowserOriginKeeper(object : JournalBrowserOriginStore {
        @Volatile var record: JournalBrowserOriginRecord? = null
        override fun read() = record
        override fun write(record: JournalBrowserOriginRecord) { this.record = record }
        override fun clear() { record = null }
    })

    @Before
    fun setUp() {
        assumeTrue(
            "operator-driven: pass -e importConformance 1",
            InstrumentationRegistry.getArguments().getString("importConformance") == "1",
        )
        resetObserverRuntime()
        resetPersistence(context)
        syncStores(context).journalConfirmationStore.confirm("client-fp")
        PhoneJournalTestHooks.onLoadUrl = { loadedUrls.add(it) }
        PhoneJournalTestHooks.pairingSnapshotOverride = committedSnapshot()
        PhoneJournalTestHooks.sessionOverride = {
            LiveJournalSheetSession(
                JournalBrowserSession(
                    pairing = { PairingGeneration("inst1", "sha256:cert") },
                    accessStillCurrent = { true },
                    upstreamFactory = { KitUpstream() },
                    diag = {},
                    originKeeper = keeper,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
        PhoneJournalTestHooks.reset()
        keeper.clear()
    }

    @Test
    fun theKitPassesInTheRealJournalWindow() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            composeRule.onNodeWithTag("journalMarkPill").performClick()
            composeRule.waitUntil(15_000) { loadedUrls.isNotEmpty() && findWebView() != null }
            val view = requireNotNull(findWebView())
            scenario.onActivity { view.evaluateJavascript("location.assign('/conformance/index.html')", null) }
            awaitLog(view, 15_000) { title(view) == "Journal import host conformance" }

            click(scenario, view, "detect")
            awaitLog(view, 15_000) { "top document capability" in it && "embedded document" in it }
            click(scenario, view, "wire")
            awaitLog(view, 15_000) { "invalid context refused" in it || "FAIL" in it }
            Log.i(TAG, "dismiss the picker now")
            click(scenario, view, "cancel")
            awaitLog(view, 180_000) { "picker cancellation" in it || "FAIL" in it }
            Log.i(TAG, "choose sample.txt now")
            click(scenario, view, "queue")
            awaitLog(view, 180_000) { "correlated status" in it || "FAIL" in it }
            Thread.sleep(3_000)
            val final = log(view)
            final.lines().filter { it.isNotBlank() }.forEach { Log.i(TAG, it) }
            assertTrue(final, "FAIL" !in final)
            listOf(
                "PASS top document capability",
                "PASS embedded document",
                "PASS unsupported message",
                "PASS invalid context refused",
                "PASS overlapping request reports busy",
                "PASS picker cancellation",
                "PASS queue owns the sample",
                "PASS correlated status",
            ).forEach { assertTrue("missing: $it\n$final", it in final) }
        }
    }

    private fun click(scenario: ActivityScenario<PhoneShellActivity>, view: WebView, id: String) {
        scenario.onActivity { view.evaluateJavascript("document.getElementById('$id').click()", null) }
    }

    private fun awaitLog(view: WebView, timeoutMs: Long, done: (String) -> Boolean): String {
        var last = ""
        composeRule.waitUntil(timeoutMs) {
            last = log(view)
            done(last)
        }
        return last
    }

    private fun title(view: WebView): String {
        var value = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = view.title.orEmpty() }
        return value
    }

    private fun log(view: WebView): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var value = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript("(document.getElementById('log')||{}).textContent||''") { raw ->
                value = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                latch.countDown()
            }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return value
    }

    private fun findWebView(): WebView? {
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
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            for (root in roots) find(root)?.let { target = it }
        }
        return target
    }

    private fun committedSnapshot() = PairingGraphSnapshot.Committed(
        sequenceNumber = 1L,
        revisions = GraphRevisions(1L, 1L, 1L),
        home = PairedHome(
            instanceId = "test-instance",
            homeLabel = "Test Journal",
            relayOrigin = null,
            caChainFingerprint = "ca-fp",
            clientCertFingerprint = "client-fp",
            observerHandle = "obs-1",
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        ),
        hasDirectEndpoint = false,
        directAssociated = false,
        relayLiveEligible = false,
    )

    /** Serves the kit at the journal origin; the contract is the copy the app itself ships. */
    private inner class KitUpstream : JournalBrowserUpstream {
        override val isPoisoned: Boolean get() = false
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
            val name = path.substringBefore('?')
            val (bytes, type) = when (name) {
                "/import-host-contract.json" ->
                    context.assets.open("journal-web-host/import-host-contract.json").use { it.readBytes() } to "application/json"
                "/conformance/index.html", "/conformance/frame.html" -> kit(name.substringAfterLast('/')) to "text/html; charset=utf-8"
                "/conformance/cases.json" -> kit("cases.json") to "application/json"
                else -> "<!doctype html><title>kit host</title>".encodeToByteArray() to "text/html; charset=utf-8"
            }
            return BrowserHttpResponse(status = 200, headers = listOf("Content-Type" to type), body = bytes)
        }
        override fun close() {}

        private fun kit(file: String): ByteArray =
            InstrumentationRegistry.getInstrumentation().context.assets.open("import-conformance/$file").use { it.readBytes() }
    }

    private companion object {
        const val TAG = "ImportConformance"
    }
}
