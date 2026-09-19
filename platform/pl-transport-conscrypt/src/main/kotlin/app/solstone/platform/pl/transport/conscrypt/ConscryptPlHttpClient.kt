// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.LedgerCategory
import app.solstone.core.pl.LiveRoot
import app.solstone.core.pl.MemoryLedger
import app.solstone.core.pl.MuxSession
import app.solstone.core.pl.PlHttpClient
import java.io.Closeable

class ConscryptPlHttpClient internal constructor(private val session: MuxSession) : PlHttpClient, Closeable, LiveRoot {
    override val category: LedgerCategory = LedgerCategory.TLS
    override val capacity: Long = 64 * 1024L

    init {
        MemoryLedger.registerRoot(this)
    }

    override fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Int,
    ): HttpResponse {
        return session.request(method, path, headers, body, maxResponseBytes)
    }

    fun requestBrowser(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Int = app.solstone.core.pl.MAX_BROWSER_RESPONSE_BYTES,
    ): app.solstone.core.pl.browser.BrowserHttpResponse {
        return session.requestBrowser(method, path, headers, body, maxResponseBytes)
    }

    fun requestStreaming(
        method: String,
        path: String,
        headers: List<Pair<String, String>>,
        bodySource: app.solstone.core.pl.browser.BrowserRequestBodySource?,
        responseSink: app.solstone.core.pl.browser.BrowserResponseSink,
    ) {
        session.requestStreaming(method, path, headers, bodySource, responseSink)
    }

    val isPoisoned: Boolean
        get() = session.isPoisoned

    override fun close() {
        MemoryLedger.unregisterRoot(this)
        session.close()
    }
}
