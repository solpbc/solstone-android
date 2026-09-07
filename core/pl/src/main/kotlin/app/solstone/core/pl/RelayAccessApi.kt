// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64

sealed interface RelayAccessResponse {
    data class Ready(
        val protocolVersion: Int,
        val relayOrigin: String,
        val instanceId: String,
        val deviceToken: String,
        val expiresAt: String,
    ) : RelayAccessResponse {
        val token: String get() = deviceToken
    }

    data object NotConfigured : RelayAccessResponse
    data object Unavailable : RelayAccessResponse
    data object NotFound : RelayAccessResponse
    data class Malformed(val reason: String) : RelayAccessResponse
    data class Failure(val status: Int?, val message: String?) : RelayAccessResponse
}

data class RelayAccessJwtV2(
    val iss: String,
    val sub: String,
    val aud: String,
    val scope: String,
    val ver: Int,
    val instanceId: String,
    val iat: Long,
    val exp: Long,
    val jti: String,
)

private val EXACT_JWT_V2_KEYS = setOf("iss", "sub", "aud", "scope", "ver", "instance_id", "iat", "exp", "jti")
private val EXACT_NOT_CONFIGURED_KEYS = setOf("protocol_version", "status")
private val EXACT_READY_KEYS = setOf("protocol_version", "status", "relay_origin", "instance_id", "device_token", "expires_at")

fun parseRelayAccessJwtV2(token: String, pairedInstanceId: String): RelayAccessJwtV2? = runCatching {
    val parts = token.split('.')
    if (parts.size != 3) return null
    val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
    val root = parseJson(payloadBytes.toString(Charsets.UTF_8)) as? Map<*, *> ?: return null

    // Strictly enforce exact claim set {iss, sub, aud, scope, ver, instance_id, iat, exp, jti}
    val keys = root.keys.map { it.toString() }.toSet()
    if (keys != EXACT_JWT_V2_KEYS) return null

    val verNum = root["ver"] as? Number ?: return null
    if (verNum.toDouble() != 2.0) return null
    val ver = 2

    val aud = root["aud"] as? String ?: return null
    if (aud != "spl-relay") return null

    val scope = root["scope"] as? String ?: return null
    if (scope != "session.dial") return null

    val instanceId = root["instance_id"] as? String ?: return null
    if (instanceId != pairedInstanceId) return null

    val sub = root["sub"] as? String ?: return null
    if (sub != "instance:$pairedInstanceId") return null

    val iss = root["iss"] as? String ?: return null
    if (iss.isBlank()) return null

    val jti = root["jti"] as? String ?: return null
    if (jti.isBlank()) return null

    val iatNum = root["iat"] as? Number ?: return null
    val iatD = iatNum.toDouble()
    if (iatD.isNaN() || iatD.isInfinite() || iatD != Math.floor(iatD) || iatD < 0.0) return null
    val iat = iatD.toLong()

    val expNum = root["exp"] as? Number ?: return null
    val expD = expNum.toDouble()
    if (expD.isNaN() || expD.isInfinite() || expD != Math.floor(expD) || expD < 0.0) return null
    val exp = expD.toLong()

    if (exp <= iat) return null

    RelayAccessJwtV2(
        iss = iss,
        sub = sub,
        aud = aud,
        scope = scope,
        ver = ver,
        instanceId = instanceId,
        iat = iat,
        exp = exp,
        jti = jti,
    )
}.getOrNull()

fun parseRfc3339ToEpochSeconds(timestamp: String): Long? = runCatching {
    Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(timestamp)).epochSecond
}.getOrNull()

fun validateRelayAccessReady(
    protocolVersion: Int,
    status: String,
    relayOrigin: String,
    instanceId: String,
    deviceToken: String,
    expiresAt: String,
    pairedInstanceId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
): RelayAccessResponse {
    if (protocolVersion != 2 || status != "ready") {
        return RelayAccessResponse.Malformed("protocol_version must be 2 and status ready")
    }

    if (instanceId != pairedInstanceId) {
        return RelayAccessResponse.Malformed("ready instance_id does not match paired instance")
    }

    val normalizedOrigin = relayOrigin.trimEnd('/')
    try {
        val url = URL(normalizedOrigin)
        if (url.protocol != "https" && url.protocol != "http") {
            return RelayAccessResponse.Malformed("invalid relay origin protocol")
        }
        if (url.host.isNullOrBlank()) {
            return RelayAccessResponse.Malformed("empty relay origin host")
        }
    } catch (_: Exception) {
        return RelayAccessResponse.Malformed("malformed relay origin url")
    }

    val jwt = parseRelayAccessJwtV2(deviceToken, pairedInstanceId)
        ?: return RelayAccessResponse.Malformed("invalid or mismatched jwt v2 token")

    val expiresAtSec = parseRfc3339ToEpochSeconds(expiresAt)
        ?: return RelayAccessResponse.Malformed("invalid rfc3339 expires_at")

    if (expiresAtSec != jwt.exp) {
        return RelayAccessResponse.Malformed("expires_at does not match jwt exp")
    }

    if (nowEpochMs / 1000L >= jwt.exp) {
        return RelayAccessResponse.Malformed("token already expired")
    }

    return RelayAccessResponse.Ready(
        protocolVersion = protocolVersion,
        relayOrigin = normalizedOrigin,
        instanceId = instanceId,
        deviceToken = deviceToken,
        expiresAt = expiresAt,
    )
}

fun parseRelayAccessBody(bodyText: String, pairedInstanceId: String, nowEpochMs: Long): RelayAccessResponse = runCatching {
    val root = parseJson(bodyText) as? Map<*, *> ?: return RelayAccessResponse.Malformed("not a json map")
    val keys = root.keys.map { it.toString() }.toSet()

    val protocolVersion = (root["protocol_version"] as? Number)?.toInt()
        ?: return RelayAccessResponse.Malformed("missing protocol_version")
    if (protocolVersion != 2) {
        return RelayAccessResponse.Malformed("unsupported protocol_version $protocolVersion")
    }

    val status = root["status"] as? String ?: return RelayAccessResponse.Malformed("missing status")
    if (status == "not_configured") {
        if (keys != EXACT_NOT_CONFIGURED_KEYS) {
            return RelayAccessResponse.Malformed("extra keys in not_configured response")
        }
        return RelayAccessResponse.NotConfigured
    }
    if (status == "ready") {
        if (keys != EXACT_READY_KEYS) {
            return RelayAccessResponse.Malformed("unexpected or missing keys in ready response")
        }
        val relayOrigin = root["relay_origin"] as? String ?: return RelayAccessResponse.Malformed("missing relay_origin")
        val instanceId = root["instance_id"] as? String ?: return RelayAccessResponse.Malformed("missing instance_id")
        val deviceToken = root["device_token"] as? String ?: return RelayAccessResponse.Malformed("missing device_token")
        val expiresAt = root["expires_at"] as? String ?: return RelayAccessResponse.Malformed("missing expires_at")
        return validateRelayAccessReady(protocolVersion, status, relayOrigin, instanceId, deviceToken, expiresAt, pairedInstanceId, nowEpochMs)
    }
    return RelayAccessResponse.Malformed("unknown status $status")
}.getOrElse { RelayAccessResponse.Malformed("json parse exception: ${it.message}") }

fun fetchRelayAccess(
    client: PlHttpClient,
    pairedInstanceId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
    maxResponseBytes: Int = 64 * 1024,
): RelayAccessResponse = try {
    val response = client.request(
        method = "GET",
        path = "/app/network/api/relay/access",
        headers = mapOf("Accept" to "application/json", "Cache-Control" to "no-cache"),
        body = null,
        maxResponseBytes = maxResponseBytes,
    )
    when (response.status) {
        200 -> parseRelayAccessBody(response.bodyText(), pairedInstanceId, nowEpochMs)
        404 -> RelayAccessResponse.NotFound
        503 -> RelayAccessResponse.Unavailable
        else -> RelayAccessResponse.Failure(response.status, "HTTP ${response.status}")
    }
} catch (e: Exception) {
    RelayAccessResponse.Failure(null, e.javaClass.simpleName)
}
