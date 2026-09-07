// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.parseRelayTokenReplacement
import app.solstone.core.pl.inspectRelayTokenPayload
import app.solstone.core.pl.isRelayTokenUsableNow
import app.solstone.core.pl.isRelayTokenWithinRefreshGrace
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.parseLegacyDeviceTokenJwt
import app.solstone.core.pl.parseProductionRelayOrigin
import app.solstone.core.pl.parseRelayAccessJwtV2
import app.solstone.core.pl.parseRfc3339ToEpochSeconds
import app.solstone.core.pl.toJson

sealed interface DeviceTokenRefresh {
    data class Refreshed(val deviceToken: String, val expiresAt: String?) : DeviceTokenRefresh {
        override fun toString(): String =
            "Refreshed(deviceToken=<redacted>, expiresAt=$expiresAt)"
    }

    data object ReconnectNeeded : DeviceTokenRefresh
    data object TransientError : DeviceTokenRefresh
}

private data class DecodedCurrentToken(
    val isV2: Boolean,
    val instanceId: String,
    val exp: Long,
)

private fun decodeCurrentToken(token: String): DecodedCurrentToken? {
    val root = inspectRelayTokenPayload(token) ?: return null
    val ver = (root["ver"] as? Number)?.toInt()
    if (ver == 2) {
        val instanceId = root["instance_id"] as? String ?: return null
        val v2 = parseRelayAccessJwtV2(token, instanceId) ?: return null
        return DecodedCurrentToken(isV2 = true, instanceId = instanceId, exp = v2.exp)
    }
    val instanceId = root["instance_id"] as? String ?: return null
    val legacy = parseLegacyDeviceTokenJwt(token, instanceId) ?: return null
    return DecodedCurrentToken(isV2 = false, instanceId = instanceId, exp = legacy.exp)
}

fun refreshDeviceToken(
    currentToken: String,
    relayOrigin: String,
    poster: HttpsPoster,
    nowEpochMs: Long? = null,
): DeviceTokenRefresh {
    return try {
        val origin = parseProductionRelayOrigin(relayOrigin) ?: return DeviceTokenRefresh.TransientError
        val decoded = decodeCurrentToken(currentToken) ?: return DeviceTokenRefresh.TransientError
        if (!isRelayTokenWithinRefreshGrace(decoded.exp, nowEpochMs ?: System.currentTimeMillis())) {
            return DeviceTokenRefresh.ReconnectNeeded
        }

        val requestPayload = mapOf(
            "device_token" to currentToken,
            "protocol_version" to 2,
        )
        val body = toJson(requestPayload).toByteArray(Charsets.UTF_8)
        val response = poster.post("${origin.httpsBase}/token/refresh", body, JSON_HEADERS)
        when (response.status) {
            200 -> {
                val root = parseJson(response.bodyText()) as? Map<*, *> ?: return DeviceTokenRefresh.TransientError
                val replacement = parseRelayTokenReplacement(root, decoded.instanceId, decoded.isV2, nowEpochMs ?: System.currentTimeMillis())
                    ?: return DeviceTokenRefresh.TransientError
                DeviceTokenRefresh.Refreshed(replacement.token, replacement.expiresAt)
            }
            401 -> {
                val root = runCatching { parseJson(response.bodyText()) as? Map<*, *> }.getOrNull()
                if (root?.get("reason") == "expired") {
                    DeviceTokenRefresh.ReconnectNeeded
                } else {
                    DeviceTokenRefresh.TransientError
                }
            }
            403,
            404,
            -> DeviceTokenRefresh.ReconnectNeeded
            else -> DeviceTokenRefresh.TransientError
        }
    } catch (_: Exception) {
        DeviceTokenRefresh.TransientError
    }
}
