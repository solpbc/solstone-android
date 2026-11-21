// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

data class SharedSourceRow(val detail: String?, val state: String?, val at: Instant?)
data class SharedSourceDevice(val cid: String, val label: String, val type: String?, val rows: List<SharedSourceRow>)
data class KnownSources(val revision: String, val sources: Map<String, List<SharedSourceDevice>>) {
    companion object {
        fun parse(body: String): KnownSources {
            val root = parseJson(body) as Map<*, *>
            val self = (root["self_cid"] as String).also { require(it.isNotBlank()) }
            val sources = mutableMapOf<String, MutableList<SharedSourceDevice>>()
            for (value in root["devices"] as List<*>) {
                val device = value as Map<*, *>
                val cid = device["cid"] as String
                if (cid == self) continue
                val label = device["display_label"] as String
                val seen = timestamp(device["device_last_seen_at"])
                val entries = (device["sources"] as List<*>).map { it as Map<*, *> }
                for ((kind, rows) in entries.groupBy { it["source"] as String }) {
                    val ordered = rows.map { row ->
                        val synced = timestamp(row["last_synced_at"])
                        val at = when (row["origin"]) {
                            "observed" -> synced
                            "declared" -> listOfNotNull(seen, synced, timestamp(row["declared_at"])).maxOrNull()
                            else -> null
                        }
                        SharedSourceRow(row["detail"] as? String, row["state"] as? String, at)
                    }.sortedWith(compareByDescending<SharedSourceRow> { it.at }.thenBy { it.detail })
                    sources.getOrPut(kind) { mutableListOf() }.add(SharedSourceDevice(cid, label, device["device_type"] as? String, ordered))
                }
            }
            val order = compareByDescending<SharedSourceDevice> { it.rows.first().at }
                .thenBy { it.label }.thenBy { it.cid }
            return KnownSources(root["sources_rev"] as String, sources.mapValues { (_, devices) -> devices.sortedWith(order).distinctBy { it.cid } })
        }

        private fun timestamp(raw: Any?): Instant? = (raw as? String)?.let(Instant::parse)
    }
}

// Share only a currently executing read for the same credential. No result survives the request.
private val statusReads = ConcurrentHashMap<String, FutureTask<HttpResponse>>()
fun readSystemStatus(client: PlHttpClient, fingerprint: String = ""): HttpResponse {
    val read = { client.request("GET", "/api/system/status", mapOf("Cache-Control" to "no-cache"), null, 64 * 1024) }
    if (fingerprint.isEmpty()) return read()
    val task = FutureTask(read)
    val existing = statusReads.putIfAbsent(fingerprint, task)
    if (existing != null) return existing.response()
    return try {
        task.run()
        task.response()
    } finally {
        statusReads.remove(fingerprint, task)
    }
}

private fun FutureTask<HttpResponse>.response(): HttpResponse = try {
    get()
} catch (e: java.util.concurrent.ExecutionException) {
    throw e.cause ?: e
}
