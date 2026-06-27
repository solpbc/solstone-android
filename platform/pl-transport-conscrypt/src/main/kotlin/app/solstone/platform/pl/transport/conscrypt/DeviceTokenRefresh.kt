// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson

sealed interface DeviceTokenRefresh {
    data class Refreshed(val deviceToken: String, val expiresAt: String?) : DeviceTokenRefresh
    data object ReconnectNeeded : DeviceTokenRefresh
    data object TransientError : DeviceTokenRefresh
}

fun refreshDeviceToken(currentToken: String, relayOrigin: String, poster: HttpsPoster): DeviceTokenRefresh =
    try {
        val normalizedOrigin = normalizeRelayOrigin(relayOrigin)
        val body = toJson(mapOf("device_token" to currentToken)).toByteArray(Charsets.UTF_8)
        val response = poster.post("$normalizedOrigin/token/refresh", body, JSON_HEADERS)
        when (response.status) {
            200 -> {
                val root = parseJson(response.bodyText()) as? Map<*, *> ?: return DeviceTokenRefresh.TransientError
                val deviceToken = root["device_token"] as? String ?: return DeviceTokenRefresh.TransientError
                DeviceTokenRefresh.Refreshed(deviceToken, root["expires_at"] as? String)
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
