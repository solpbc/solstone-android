// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.util.Base64

data class JwtClaims(val iat: Long, val exp: Long)

fun parseJwtClaims(token: String): JwtClaims? =
    runCatching {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val payload = Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8)
        val root = parseJson(payload) as? Map<*, *> ?: return null
        val iat = (root["iat"] as? Number)?.toLong() ?: return null
        val exp = (root["exp"] as? Number)?.toLong() ?: return null
        JwtClaims(iat = iat, exp = exp)
    }.getOrNull()

fun shouldRefreshDeviceToken(token: String, nowEpochMs: Long): Boolean {
    val claims = parseJwtClaims(token) ?: return false
    val nowSec = nowEpochMs / 1000L
    val ttl = claims.exp - claims.iat
    val age = nowSec - claims.iat
    return ttl > 0 && age * 100L > ttl * 80L
}
