// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.net.URI
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

        override fun toString(): String =
            "Ready(protocolVersion=$protocolVersion, relayOrigin=$relayOrigin, instanceId=$instanceId, deviceToken=<redacted>, expiresAt=$expiresAt)"
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

data class RelayAccessJwtLegacy(
    val iss: String,
    val sub: String,
    val aud: String,
    val scope: String,
    val deviceFp: String,
    val instanceId: String,
    val iat: Long,
    val exp: Long,
    val jti: String,
)

data class RelayOrigin(
    val scheme: String,
    val host: String,
    val effectivePort: Int,
    val httpsBase: String,
    val wssBase: String,
)

private val EXACT_JWT_V2_KEYS = setOf("iss", "sub", "aud", "scope", "ver", "instance_id", "iat", "exp", "jti")
private val EXACT_JWT_LEGACY_KEYS = setOf("iss", "sub", "aud", "scope", "instance_id", "device_fp", "iat", "exp", "jti")
private val EXACT_NOT_CONFIGURED_KEYS = setOf("protocol_version", "status")
private val EXACT_READY_KEYS = setOf("protocol_version", "status", "relay_origin", "instance_id", "device_token", "expires_at")

fun parseProductionRelayOrigin(raw: String): RelayOrigin? = runCatching {
    if (raw.isBlank() || raw.any { it <= ' ' || it.code == 0x7f } || raw.contains('\\')) return null
    val uri = URI(raw)
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/")
    ) return null
    val host = uri.host?.lowercase() ?: return null
    if (uri.rawAuthority.endsWith(':')) return null
    val port = if (uri.port == -1) 443 else uri.port
    if (port !in 1..65535) return null
    val unbracketed = host.removePrefix("[").removeSuffix("]")
    if (unbracketed.contains('%') || unbracketed.all { it.isDigit() }) return null
    val authorityHost = if (unbracketed.contains(':')) "[$unbracketed]" else unbracketed
    val authority = authorityHost + if (port == 443) "" else ":$port"
    RelayOrigin("https", unbracketed, port, "https://$authority", "wss://$authority")
}.getOrNull()

internal fun exactJsonInteger(value: Any?): Long? {
    val number = value as? Number ?: return null
    if (number is java.math.BigDecimal) {
        val exact = runCatching { number.longValueExact() }.getOrNull() ?: return null
        return exact.takeIf { it in 0L..9007199254740991L }
    }
    val d = number.toDouble()
    if (!d.isFinite() || d < 0 || d > 9007199254740991.0 || d != Math.floor(d)) return null
    return d.toLong()
}

private fun validJwtParts(parts: List<String>): Boolean =
    parts.size == 3 && parts.all { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' } }

fun parseRelayAccessJwtV2(token: String, pairedInstanceId: String): RelayAccessJwtV2? = runCatching {
    val parts = token.split('.')
    if (!validJwtParts(parts)) return null
    val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
    val root = parseJson(payloadBytes.toString(Charsets.UTF_8)) as? Map<*, *> ?: return null

    // Strictly enforce exact claim set {iss, sub, aud, scope, ver, instance_id, iat, exp, jti}
    val keys = root.keys.map { it.toString() }.toSet()
    if (keys != EXACT_JWT_V2_KEYS) return null

    if (exactJsonInteger(root["ver"]) != 2L) return null
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

    val iat = exactJsonInteger(root["iat"]) ?: return null
    val exp = exactJsonInteger(root["exp"]) ?: return null

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

fun inspectRelayTokenPayload(token: String): Map<String, Any?>? = runCatching {
    val parts = token.split('.')
    if (!validJwtParts(parts)) return null
    val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
    val root = parseJson(payloadBytes.toString(Charsets.UTF_8)) as? Map<*, *> ?: return null
    val map = LinkedHashMap<String, Any?>()
    for ((k, v) in root) {
        if (k is String) map[k] = v
    }
    map
}.getOrNull()

fun parseLegacyDeviceTokenJwt(token: String, pairedInstanceId: String): RelayAccessJwtLegacy? = runCatching {
    val parts = token.split('.')
    if (!validJwtParts(parts)) return null
    val payloadBytes = Base64.getUrlDecoder().decode(parts[1])
    val root = parseJson(payloadBytes.toString(Charsets.UTF_8)) as? Map<*, *> ?: return null

    val keys = root.keys.map { it.toString() }.toSet()
    if (keys != EXACT_JWT_LEGACY_KEYS) return null

    val aud = root["aud"] as? String ?: return null
    if (aud != "spl-relay") return null

    val scope = root["scope"] as? String ?: return null
    if (scope != "session.dial") return null

    val sub = root["sub"] as? String ?: return null
    if (!sub.startsWith("device:") || sub.length <= 7) return null

    val instanceId = root["instance_id"] as? String ?: return null
    if (instanceId.isBlank() || instanceId != pairedInstanceId) return null
    val deviceFp = root["device_fp"] as? String ?: return null
    if (!deviceFp.startsWith("sha256:") || deviceFp.length != 7 + 64) return null
    val hexPart = deviceFp.substring(7)
    if (!hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null

    val iss = root["iss"] as? String ?: return null
    if (iss.isBlank()) return null

    val jti = root["jti"] as? String ?: return null
    if (jti.isBlank()) return null

    val iat = exactJsonInteger(root["iat"]) ?: return null
    val exp = exactJsonInteger(root["exp"]) ?: return null

    if (exp <= iat) return null

    RelayAccessJwtLegacy(
        iss = iss,
        sub = sub,
        aud = aud,
        scope = scope,
        deviceFp = deviceFp,
        instanceId = instanceId,
        iat = iat,
        exp = exp,
        jti = jti,
    )
}.getOrNull()

fun isRelayTokenUsableNow(expEpochSec: Long, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
    (nowEpochMs / 1000L) < expEpochSec

fun isRelayTokenWithinRefreshGrace(expEpochSec: Long, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
    (nowEpochMs / 1000L) < (expEpochSec + 30L * 86400L)

fun parseRfc3339ToEpochSeconds(timestamp: String): Long? = runCatching {
    val instant = Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(timestamp))
    if (instant.nano != 0) return null
    instant.epochSecond
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

    val parsedOrigin = parseProductionRelayOrigin(relayOrigin)
        ?: return RelayAccessResponse.Malformed("invalid relay origin")

    val jwt = parseRelayAccessJwtV2(deviceToken, pairedInstanceId)
        ?: return RelayAccessResponse.Malformed("invalid or mismatched jwt v2 token")

    val nowSec = nowEpochMs / 1000L
    if (jwt.iat > nowSec + 60L) {
        return RelayAccessResponse.Malformed("token issued in the future")
    }

    val expiresAtSec = parseRfc3339ToEpochSeconds(expiresAt)
        ?: return RelayAccessResponse.Malformed("invalid rfc3339 expires_at")

    if (expiresAtSec != jwt.exp) {
        return RelayAccessResponse.Malformed("expires_at does not match jwt exp")
    }

    if (!isRelayTokenUsableNow(jwt.exp, nowEpochMs)) {
        return RelayAccessResponse.Malformed("token already expired")
    }

    return RelayAccessResponse.Ready(
        protocolVersion = protocolVersion,
        relayOrigin = parsedOrigin.httpsBase,
        instanceId = instanceId,
        deviceToken = deviceToken,
        expiresAt = expiresAt,
    )
}

fun parseRelayAccessMap(
    root: Map<*, *>,
    pairedInstanceId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
): RelayAccessResponse {
    val keys = root.keys.map { it.toString() }.toSet()

    val protocolVersion = exactJsonInteger(root["protocol_version"])?.takeIf { it == 2L }?.toInt()
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
}

fun parseRelayAccessBody(
    bodyText: String,
    pairedInstanceId: String,
    nowEpochMs: Long = System.currentTimeMillis(),
): RelayAccessResponse = runCatching {
    val root = parseJson(bodyText) as? Map<*, *> ?: return RelayAccessResponse.Malformed("not a json map")
    parseRelayAccessMap(root, pairedInstanceId, nowEpochMs)
}.getOrElse { RelayAccessResponse.Malformed("json parse exception") }

fun decodePairRelayAccess(
    field: PairRelayAccess,
    pairedInstanceId: String,
    expectedOrigin: RelayOrigin,
    nowEpochMs: Long = System.currentTimeMillis(),
): RelayAccessResponse = when (field) {
    PairRelayAccess.Omitted -> RelayAccessResponse.Malformed("relay access omitted")
    PairRelayAccess.PresentNull -> RelayAccessResponse.Malformed("relay_access is null")
    is PairRelayAccess.NonObject -> RelayAccessResponse.Malformed("relay_access must be an object")
    is PairRelayAccess.Object -> {
        when (val parsed = parseRelayAccessMap(field.fields, pairedInstanceId, nowEpochMs)) {
            is RelayAccessResponse.Ready -> {
                val origin = parseProductionRelayOrigin(parsed.relayOrigin)
                if (origin == null ||
                    origin.scheme != expectedOrigin.scheme ||
                    origin.host != expectedOrigin.host ||
                    origin.effectivePort != expectedOrigin.effectivePort
                ) {
                    RelayAccessResponse.Malformed("relay origin mismatch: expected ${expectedOrigin.httpsBase}, got ${parsed.relayOrigin}")
                } else {
                    parsed
                }
            }
            else -> parsed
        }
    }
}

fun fetchRelayAccess(
    client: PlHttpClient,
    pairedInstanceId: String,
    nowEpochMs: Long? = null,
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
        200 -> parseRelayAccessBody(response.bodyText(), pairedInstanceId, nowEpochMs ?: System.currentTimeMillis())
        404 -> RelayAccessResponse.NotFound
        503 -> RelayAccessResponse.Unavailable
        else -> RelayAccessResponse.Failure(response.status, "HTTP ${response.status}")
    }
} catch (e: Exception) {
    RelayAccessResponse.Failure(null, e.javaClass.simpleName)
}

// Validate replacements from authenticated control responses before publication.
data class RelayTokenReplacement(val token: String, val expiresAt: String?) {
    override fun toString(): String = "RelayTokenReplacement(token=<redacted>)"
}

fun parseRelayTokenReplacement(
    root: Map<*, *>,
    instanceId: String,
    currentIsV2: Boolean,
    nowEpochMs: Long,
): RelayTokenReplacement? {
    val token = root["device_token"] as? String ?: return null
    val expiry = root["expires_at"]
    if (expiry != null && expiry !is String) return null
    if (root.containsKey("protocol_version")) {
        if (exactJsonInteger(root["protocol_version"]) != 2L) return null
        val jwt = parseRelayAccessJwtV2(token, instanceId) ?: return null
        val expiresAt = expiry as? String ?: return null
        if (jwt.iat > nowEpochMs / 1000L + 60L || !isRelayTokenUsableNow(jwt.exp, nowEpochMs) ||
            parseRfc3339ToEpochSeconds(expiresAt) != jwt.exp
        ) return null
        return RelayTokenReplacement(token, expiresAt)
    }
    if (currentIsV2) return null
    val legacy = parseLegacyDeviceTokenJwt(token, instanceId) ?: return null
    if (legacy.iat > nowEpochMs / 1000L + 60L || !isRelayTokenUsableNow(legacy.exp, nowEpochMs)) return null
    if (expiry != null && parseRfc3339ToEpochSeconds(expiry as String) != legacy.exp) return null
    return RelayTokenReplacement(token, expiry as? String)
}
