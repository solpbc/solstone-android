// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import java.net.InetAddress
import java.net.URI
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalBrowserLoopbackResolutionRuntimeTest {

    @Test
    fun allResolvedAddressesForOriginHostAreLoopback() {
        val session = JournalBrowserSession(
            pairing = { PairingGeneration("inst1", "sha256:cert") },
            accessStillCurrent = { true },
            upstreamFactory = { FakeUpstream() },
            diag = {},
        )

        val origin = session.start()
        val host = URI(origin.url).host

        val addresses = InetAddress.getAllByName(host)
        assertTrue("Must resolve at least one address", addresses.isNotEmpty())
        for (addr in addresses) {
            assertTrue("Address $addr must be loopback", addr.isLoopbackAddress)
        }

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
