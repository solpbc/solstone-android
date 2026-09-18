// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalBrowserCookieContainmentRuntimeTest {

    @Test
    fun cookiesDoNotLeakAcrossDistinctSessionTokensAndDomainIsStripped() {
        val session = JournalBrowserSession(
            pairing = { PairingGeneration("inst1", "sha256:cert") },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        return BrowserHttpResponse(
                            status = 200,
                            headers = listOf(
                                "Content-Type" to "text/plain",
                                "Set-Cookie" to "leak=1; Domain=localhost; Path=/",
                            ),
                            body = "OK".encodeToByteArray(),
                        )
                    }
                    override fun close() {}
                }
            },
            diag = {},
        )

        val origin1 = session.start()
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        // Set cookie with Domain=localhost on origin 1
        cookieManager.setCookie(origin1.url, "leak=1; Domain=localhost; Path=/")
        cookieManager.flush()

        val cookie1 = cookieManager.getCookie(origin1.url) ?: ""
        assertTrue("Cookie should be present on origin1", cookie1.contains("leak=1"))

        session.stop()

        val origin2 = session.start()
        assertTrue("New session origin must differ from previous", origin1.url != origin2.url)

        val cookie2 = cookieManager.getCookie(origin2.url) ?: ""
        assertFalse("Cookie with Domain=localhost from origin1 must not leak to origin2", cookie2.contains("leak=1"))

        // Also fetch from fake upstream and verify proxied response strips Domain
        val uri2 = URI(origin2.url)
        val response = sendRawHttpRequest(uri2.port, "GET / HTTP/1.1\r\nHost: ${uri2.host}:${uri2.port}\r\n\r\n")
        assertTrue("Response must succeed", response.startsWith("HTTP/1.1 200 OK"))
        assertTrue("Response must contain host-only Set-Cookie", response.contains("Set-Cookie: leak=1; Path=/"))
        assertFalse("Response must NOT contain Domain=localhost", response.contains("Domain=localhost"))

        session.stop()
    }

    private fun sendRawHttpRequest(port: Int, rawHttp: String): String {
        Socket("127.0.0.1", port).use { socket ->
            val out = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())
            out.write(rawHttp.toByteArray(Charsets.US_ASCII))
            out.flush()
            val buf = ByteArray(4096)
            val read = input.read(buf)
            return if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
        }
    }
}
