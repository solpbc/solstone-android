// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

/** A failed read is unknown. A valid status that omits the capability means unsupported. */
fun parseJournalCapability(status: Int, body: String, capability: String): Boolean? {
    if (status != 200) return null
    val root = runCatching { parseJson(body) as? Map<*, *> }.getOrNull() ?: return null
    if (!root.containsKey("capabilities")) return false
    val caps = root["capabilities"] as? List<*> ?: return null
    if (caps.any { it !is String }) return null
    return capability in caps
}

fun fetchJournalCapability(client: PlHttpClient, capability: String, fingerprint: String = ""): Boolean? {
    val status = readSystemStatus(client, fingerprint)
    return parseJournalCapability(status.status, status.bodyText(), capability)
}
