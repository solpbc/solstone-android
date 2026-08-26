// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient

data class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

class RecordingPlHttpClient(var response: HttpResponse) : PlHttpClient {
    val requests = mutableListOf<RecordedRequest>()
    val lastRequest: RecordedRequest
        get() = requests.last()

    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        requests += RecordedRequest(method, path, headers, body)
        return response
    }
}
