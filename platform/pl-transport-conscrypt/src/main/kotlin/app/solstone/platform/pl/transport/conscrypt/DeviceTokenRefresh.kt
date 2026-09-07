// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

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
    val instanceId: String?,
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
    val legacy = parseLegacyDeviceTokenJwt(token, "") ?: return null
    return DecodedCurrentToken(isV2 = false, instanceId = null, exp = legacy.exp)
}

fun refreshDeviceToken(
    currentToken: String,
    relayOrigin: String,
    poster: HttpsPoster,
    nowEpochMs: Long = System.currentTimeMillis(),
): DeviceTokenRefresh {
    return try {
        val origin = parseProductionRelayOrigin(relayOrigin) ?: return DeviceTokenRefresh.TransientError
        val decoded = decodeCurrentToken(currentToken) ?: return DeviceTokenRefresh.TransientError
        if (!isRelayTokenWithinRefreshGrace(decoded.exp, nowEpochMs)) {
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
                val newToken = root["device_token"] as? String ?: return DeviceTokenRefresh.TransientError
                val protocolVersionNum = root["protocol_version"] as? Number
                val expiresAt = root["expires_at"] as? String

                val isExplicitV2 = protocolVersionNum != null
                if (isExplicitV2) {
                    if (protocolVersionNum?.toInt() != 2) return DeviceTokenRefresh.TransientError
                }

                // Check if newToken is a v2 JWT
                val newTokenPayload = inspectRelayTokenPayload(newToken)
                val isV2Token = (newTokenPayload?.get("ver") as? Number)?.toInt() == 2

                if (isExplicitV2 || isV2Token) {
                    val targetInstanceId = decoded.instanceId
                        ?: (newTokenPayload?.get("instance_id") as? String)
                        ?: return DeviceTokenRefresh.TransientError
                    val v2 = parseRelayAccessJwtV2(newToken, targetInstanceId) ?: return DeviceTokenRefresh.TransientError
                    if (v2.iat > (nowEpochMs / 1000L) + 60L) return DeviceTokenRefresh.TransientError
                    if (!isRelayTokenUsableNow(v2.exp, nowEpochMs)) return DeviceTokenRefresh.TransientError
                    if (expiresAt != null) {
                        val expSec = parseRfc3339ToEpochSeconds(expiresAt) ?: return DeviceTokenRefresh.TransientError
                        if (expSec != v2.exp) return DeviceTokenRefresh.TransientError
                    }
                    DeviceTokenRefresh.Refreshed(newToken, expiresAt)
                } else {
                    if (decoded.isV2) {
                        DeviceTokenRefresh.TransientError
                    } else {
                        val legacy = parseLegacyDeviceTokenJwt(newToken, "") ?: return DeviceTokenRefresh.TransientError
                        if (!isRelayTokenUsableNow(legacy.exp, nowEpochMs)) return DeviceTokenRefresh.TransientError
                        DeviceTokenRefresh.Refreshed(newToken, expiresAt)
                    }
                }
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
