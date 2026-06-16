// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import java.io.Closeable

class ConscryptPlHttpClient internal constructor(private val session: MuxSession) : PlHttpClient, Closeable {
    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val contentType = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        return session.request(method, path, contentType, body)
    }

    override fun close() {
        session.close()
    }
}
