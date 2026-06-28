// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.toJson

data class ObserverHealth(
    val name: String,
    val streamType: String,
    val version: String,
    val uptime: Long,
    val lastSuccessfulSync: Long?,
    val pendingQueueDepth: Int,
    val recentErrorCount: Int,
    val lastErrorReason: String?,
)

class ObserverHealthClient(private val http: PlHttpClient) {
    fun report(health: ObserverHealth) {
        val body = toJson(
            mapOf(
                "name" to health.name,
                "stream_type" to health.streamType,
                "version" to health.version,
                "uptime" to health.uptime,
                "last_successful_sync" to health.lastSuccessfulSync,
                "pending_queue_depth" to health.pendingQueueDepth,
                "recent_error_count" to health.recentErrorCount,
                "last_error_reason" to health.lastErrorReason,
            ),
        ).toByteArray(Charsets.UTF_8)
        http.request(
            method = "POST",
            path = HEALTH_PATH,
            headers = mapOf(
                "Content-Type" to "application/json",
                OBSERVER_HANDLE_HEADER to health.name,
                PROTOCOL_VERSION_HEADER to OBSERVER_PROTOCOL_VERSION.toString(),
            ),
            body = body,
        )
    }
}
