// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson

class ObserverAuthException(val status: Int) : RuntimeException("observer registration auth failed: $status")

class ObserverRegistration(private val http: PlHttpClient) {
    fun register(platform: String, hostname: String, streamType: String, version: String): RegisteredObserver {
        val body = ObserverRegisterRequest(
            platform = platform,
            hostname = hostname,
            streamType = streamType,
            version = version,
        ).toJson().toByteArray(Charsets.UTF_8)
        val response = http.request(
            method = "POST",
            path = REGISTER_PATH,
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
        )
        if (response.status != 200) {
            if (response.status == 401 || response.status == 403) {
                throw ObserverAuthException(response.status)
            }
            throw IllegalStateException("observer registration failed with status ${response.status}")
        }
        return RegisteredObserver.fromJson(response.bodyText())
    }
}

data class RegisteredObserver(
    val handle: String,
    val prefix: String,
    val stream: String,
    val ingestUrl: String,
    val protocolVersion: Int,
) {
    companion object {
        fun fromJson(text: String): RegisteredObserver {
            val root = parseJson(text) as? Map<*, *> ?: throw IllegalArgumentException("observer registration response must be an object")
            return RegisteredObserver(
                handle = requiredString(root, "key"),
                prefix = root["prefix"] as? String ?: "",
                stream = requiredString(root, "name"),
                ingestUrl = requiredString(root, "ingest_url"),
                protocolVersion = (root["protocol_version"] as? Number)?.toInt() ?: OBSERVER_PROTOCOL_VERSION,
            )
        }

        private fun requiredString(root: Map<*, *>, key: String): String =
            root[key] as? String ?: throw IllegalArgumentException("observer registration response missing $key")
    }
}

private data class ObserverRegisterRequest(
    val platform: String,
    val hostname: String,
    val streamType: String,
    val version: String,
) {
    fun toJson(): String = toJson(
        mapOf(
            "platform" to platform,
            "hostname" to hostname,
            "stream_type" to streamType,
            "version" to version,
        ),
    )
}
