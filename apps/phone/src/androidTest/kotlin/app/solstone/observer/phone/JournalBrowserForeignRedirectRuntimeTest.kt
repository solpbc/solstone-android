// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

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
class JournalBrowserForeignRedirectRuntimeTest {

    @Test
    fun foreignRedirectYieldsBadGatewayWithoutLocation() {
        val session = JournalBrowserSession(
            pairing = { PairingGeneration("inst1", "sha256:cert") },
            accessStillCurrent = { true },
            upstreamFactory = {
                object : JournalBrowserUpstream {
                    override val isPoisoned: Boolean get() = false
                    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
                        return BrowserHttpResponse(
                            status = 302,
                            headers = listOf("Location" to "https://evil.example/malicious"),
                            body = ByteArray(0),
                        )
                    }
                    override fun close() {}
                }
            },
            diag = {},
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val port = uri.port
        val host = uri.host

        val response = sendRawHttpRequest(port, "GET /redirect HTTP/1.1\r\nHost: $host:$port\r\n\r\n")

        assertTrue("Expected 502 Bad Gateway for foreign redirect: $response", response.startsWith("HTTP/1.1 502 Bad Gateway"))
        assertFalse("Response must not contain Location header", response.contains("Location:"))

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
