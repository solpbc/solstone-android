// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import java.net.URI
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalBrowserLocalhostShapeRuntimeTest {

    @Test
    fun originHostMatchesExpected32HexLocalhostShape() {
        val session = JournalBrowserSession(
            pairing = { PairingGeneration("inst1", "sha256:cert") },
            accessStillCurrent = { true },
            upstreamFactory = { FakeUpstream() },
            diag = {},
        )

        val origin = session.start()
        val uri = URI(origin.url)
        val host = uri.host

        assertTrue("Host should match 32 hex chars followed by .localhost: $host", host.matches(Regex("^[0-9a-f]{32}\\.localhost$")))
        assertTrue("Port must be positive", uri.port > 0)
        session.stop()
    }

    private class FakeUpstream : JournalBrowserUpstream {
        override val isPoisoned: Boolean get() = false
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse {
            return BrowserHttpResponse(200, listOf("Content-Type" to "text/plain"), "OK".encodeToByteArray())
        }
        override fun close() {}
    }
}
