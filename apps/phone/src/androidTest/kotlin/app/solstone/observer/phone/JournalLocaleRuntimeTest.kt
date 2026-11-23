// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies in a live Android WebView backed by [JournalBrowserSession] that:
 * - in en-XA, the initial document, a later full-document navigation, and a same-origin fetch
 *   all deliver `X-Solstone-Locale: en-XA` to the paired journal upstream;
 * - in en-US (English), no `X-Solstone-Locale` header is sent;
 * - no request to any other origin carries the header (and foreign subresources stay blocked).
 */
@RunWith(AndroidJUnit4::class)
class JournalLocaleRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private data class CapturedRequest(
        val path: String,
        val localeHeader: String?,
    )

    @Test
    fun webViewCarriesLocaleAcrossFirstDocumentLaterNavigationAndFetchNotOtherOrigin() {
        val journalRequests = CopyOnWriteArrayList<CapturedRequest>()
        val otherOriginRequests = CopyOnWriteArrayList<String>()
        val otherServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val otherPort = otherServer.localPort
        val otherThread = Thread {
            runCatching {
                while (!otherServer.isClosed) {
                    val sock = otherServer.accept()
                    sock.use {
                        val raw = it.getInputStream().readNBytes(1024).toString(Charsets.UTF_8)
                        otherOriginRequests.add(raw)
                        it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                        it.getOutputStream().flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val fetchDone = CountDownLatch(1)
        val nextDone = CountDownLatch(1)

        val session = JournalBrowserSession(
            pairing = { PairingGeneration("inst1", "sha256:cert") },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse =
                        BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
                    override fun requestStreaming(
                        method: String,
                        path: String,
                        headers: List<Pair<String, String>>,
                        bodySource: BrowserRequestBodySource?,
                        responseSink: BrowserResponseSink,
                    ) {
                        val locale = headers.firstOrNull { it.first.equals("x-solstone-locale", ignoreCase = true) }?.second
                        journalRequests.add(CapturedRequest(path, locale))
                        val (contentType, body) = when (path) {
                            "/api/localized" -> {
                                fetchDone.countDown()
                                "application/json" to """{"locale":"${locale ?: "en-US"}"}"""
                            }
                            "/app/next" -> {
                                nextDone.countDown()
                                "text/html; charset=utf-8" to "<!doctype html><html><head><title>next</title></head><body>next</body></html>"
                            }
                            else -> "text/html; charset=utf-8" to """
                                <!doctype html>
                                <html><head><title>home</title></head>
                                <body>
                                  <a id="next" href="/app/next">Next</a>
                                  <script>
                                    fetch('/api/localized').catch(function(){});
                                    fetch('http://127.0.0.1:$otherPort/foreign', { mode: 'no-cors' }).catch(function(){});
                                  </script>
                                </body></html>
                            """.trimIndent()
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        responseSink.onStatusAndHeaders(
                            200,
                            "OK",
                            listOf("Content-Type" to contentType, "Content-Length" to bytes.size.toString()),
                        )
                        responseSink.onBodyChunk(bytes, 0, bytes.size)
                        responseSink.onComplete()
                    }
                    override fun close() {}
                }
            },
            diag = {},
            requestLocale = { "en-XA" },
        )

        val origin = session.start().url
        val webViewRef = AtomicReference<WebView?>(null)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val wv = createJournalWebView(
                    context = context,
                    origin = origin,
                    loadTarget = origin,
                    textZoom = 100,
                    onLiveView = {},
                    onVisitedHistory = { _, _, _ -> },
                    onPageCommitted = {},
                    onRetryableFailure = {},
                    onUnsupportedTransfer = {},
                    onUnsupportedFileSelection = {},
                    onUnsupportedNavigation = {},
                    localeTagProvider = { "en-XA" },
                )
                webViewRef.set(wv)
            }
            assertTrue("initial same-origin fetch did not arrive", fetchDone.await(15, TimeUnit.SECONDS))

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webViewRef.get()?.evaluateJavascript("document.getElementById('next').click();", null)
            }
            assertTrue("later full-document navigation did not arrive", nextDone.await(15, TimeUnit.SECONDS))

            val initial = journalRequests.firstOrNull { it.path == "/" }
            val apiFetch = journalRequests.firstOrNull { it.path == "/api/localized" }
            val nextDoc = journalRequests.firstOrNull { it.path == "/app/next" }
            assertEquals("en-XA", initial?.localeHeader)
            assertEquals("en-XA", apiFetch?.localeHeader)
            assertEquals("en-XA", nextDoc?.localeHeader)
            assertTrue("foreign origin must not receive any request from journal WebView", otherOriginRequests.isEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webViewRef.get()?.destroy()
            }
            session.stop()
            runCatching { otherServer.close() }
            otherThread.interrupt()
        }
    }

    @Test
    fun webViewInEnglishSendsNoLocaleHeader() {
        val journalRequests = CopyOnWriteArrayList<CapturedRequest>()
        val fetchDone = CountDownLatch(1)
        val nextDone = CountDownLatch(1)

        val session = JournalBrowserSession(
            pairing = { PairingGeneration("inst1", "sha256:cert") },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse =
                        BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
                    override fun requestStreaming(
                        method: String,
                        path: String,
                        headers: List<Pair<String, String>>,
                        bodySource: BrowserRequestBodySource?,
                        responseSink: BrowserResponseSink,
                    ) {
                        val locale = headers.firstOrNull { it.first.equals("x-solstone-locale", ignoreCase = true) }?.second
                        journalRequests.add(CapturedRequest(path, locale))
                        val (contentType, body) = when (path) {
                            "/api/localized" -> {
                                fetchDone.countDown()
                                "application/json" to """{"locale":"en-US"}"""
                            }
                            "/app/next" -> {
                                nextDone.countDown()
                                "text/html; charset=utf-8" to "<!doctype html><html><head><title>next</title></head><body>next</body></html>"
                            }
                            else -> "text/html; charset=utf-8" to """
                                <!doctype html>
                                <html><head><title>home</title></head>
                                <body>
                                  <a id="next" href="/app/next">Next</a>
                                  <script>fetch('/api/localized').catch(function(){});</script>
                                </body></html>
                            """.trimIndent()
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        responseSink.onStatusAndHeaders(
                            200,
                            "OK",
                            listOf("Content-Type" to contentType, "Content-Length" to bytes.size.toString()),
                        )
                        responseSink.onBodyChunk(bytes, 0, bytes.size)
                        responseSink.onComplete()
                    }
                    override fun close() {}
                }
            },
            diag = {},
            requestLocale = { null },
        )

        val origin = session.start().url
        val webViewRef = AtomicReference<WebView?>(null)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val wv = createJournalWebView(
                    context = context,
                    origin = origin,
                    loadTarget = origin,
                    textZoom = 100,
                    onLiveView = {},
                    onVisitedHistory = { _, _, _ -> },
                    onPageCommitted = {},
                    onRetryableFailure = {},
                    onUnsupportedTransfer = {},
                    onUnsupportedFileSelection = {},
                    onUnsupportedNavigation = {},
                    localeTagProvider = { null },
                )
                webViewRef.set(wv)
            }
            assertTrue("initial same-origin fetch did not arrive", fetchDone.await(15, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webViewRef.get()?.evaluateJavascript("document.getElementById('next').click();", null)
            }
            assertTrue("later navigation did not arrive", nextDone.await(15, TimeUnit.SECONDS))

            assertTrue(journalRequests.any { it.path == "/" })
            assertTrue(journalRequests.any { it.path == "/api/localized" })
            assertTrue(journalRequests.any { it.path == "/app/next" })
            for (req in journalRequests) {
                assertNull("unexpected X-Solstone-Locale on ${req.path}", req.localeHeader)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webViewRef.get()?.destroy()
            }
            session.stop()
        }
    }
}
