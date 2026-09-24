// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.escapeJson
import app.solstone.core.pl.parseJson
import java.util.Base64

sealed interface VapidKeyResult {
    data class Key(val publicKey: ByteArray) : VapidKeyResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key) return false
            return publicKey.contentEquals(other.publicKey)
        }
        override fun hashCode(): Int = publicKey.contentHashCode()
    }
    data object NoPush : VapidKeyResult
    data object Invalid : VapidKeyResult
    data class Failed(val status: Int?, val message: String?) : VapidKeyResult
}

sealed interface RegisterPushResult {
    data object Registered : RegisterPushResult
    data object NoPush : RegisterPushResult
    data object Refused : RegisterPushResult
    data object NotLinked : RegisterPushResult
    data class Failed(val status: Int?, val message: String?) : RegisterPushResult
}

sealed interface DeletePushResult {
    data object Deleted : DeletePushResult
    data object NoPush : DeletePushResult
    data object NotLinked : DeletePushResult
    data class Failed(val status: Int?, val message: String?) : DeletePushResult
}

fun fetchVapidKey(client: PlHttpClient): VapidKeyResult = try {
    val response = client.request(
        method = "GET",
        path = "/api/push/vapid-key",
        headers = mapOf("Accept" to "application/json"),
        body = null,
    )
    when (response.status) {
        200 -> {
            val root = runCatching { parseJson(response.bodyText()) as? Map<*, *> }.getOrNull()
            val pubKeyStr = root?.get("public_key") as? String
            if (pubKeyStr == null || pubKeyStr.length != 87) {
                VapidKeyResult.Invalid
            } else {
                val bytes = runCatching { decodeBase64Url(pubKeyStr) }.getOrNull()
                if (bytes != null && bytes.size == 65 && bytes[0] == 0x04.toByte()) {
                    VapidKeyResult.Key(bytes)
                } else {
                    VapidKeyResult.Invalid
                }
            }
        }
        404 -> VapidKeyResult.NoPush
        else -> VapidKeyResult.Failed(response.status, response.bodyText())
    }
} catch (e: Exception) {
    VapidKeyResult.Failed(null, e.message)
}

fun registerPush(
    client: PlHttpClient,
    endpoint: String,
    p256dh: String,
    auth: String,
    pushKey: ByteArray,
): RegisterPushResult = try {
    val pushKeyStr = Base64.getUrlEncoder().withoutPadding().encodeToString(pushKey)
    val bodyJson = "{\"platform\":\"android\",\"endpoint\":\"${escapeJson(endpoint)}\",\"p256dh\":\"${escapeJson(p256dh)}\",\"auth\":\"${escapeJson(auth)}\",\"push_key\":\"${escapeJson(pushKeyStr)}\"}"
    val response = client.request(
        method = "POST",
        path = "/api/push/register",
        headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
        body = bodyJson.toByteArray(Charsets.UTF_8),
    )
    when (response.status) {
        200, 201 -> RegisterPushResult.Registered
        400 -> RegisterPushResult.Refused
        403 -> RegisterPushResult.NotLinked
        404 -> RegisterPushResult.NoPush
        else -> RegisterPushResult.Failed(response.status, response.bodyText())
    }
} catch (e: Exception) {
    RegisterPushResult.Failed(null, e.message)
}

fun deletePushRegistration(
    client: PlHttpClient,
    endpoint: String,
): DeletePushResult = try {
    val bodyJson = "{\"platform\":\"android\",\"endpoint\":\"${escapeJson(endpoint)}\"}"
    val response = client.request(
        method = "DELETE",
        path = "/api/push/register",
        headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
        body = bodyJson.toByteArray(Charsets.UTF_8),
    )
    when (response.status) {
        204 -> DeletePushResult.Deleted
        403 -> DeletePushResult.NotLinked
        404 -> DeletePushResult.NoPush
        else -> DeletePushResult.Failed(response.status, response.bodyText())
    }
} catch (e: Exception) {
    DeletePushResult.Failed(null, e.message)
}

fun isAcceptableEndpoint(endpoint: String): Boolean {
    if (endpoint != endpoint.trim()) return false
    if (!endpoint.startsWith("https://")) return false
    val after = endpoint.substring("https://".length)
    val host = after.takeWhile { it != '/' && it != '?' && it != '#' }
    if (host.isEmpty() || host.any { it.isWhitespace() }) return false
    return true
}

private fun decodeBase64Url(s: String): ByteArray {
    val pad = (4 - (s.length % 4)) % 4
    val padded = s + "=".repeat(pad)
    return Base64.getUrlDecoder().decode(padded)
}
