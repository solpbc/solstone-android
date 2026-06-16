// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

interface PlHttpClient {
    fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse
}
