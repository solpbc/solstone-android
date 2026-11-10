// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.obsidian

import app.solstone.core.pl.parseJson
import java.time.OffsetDateTime

/** A failed read is unknown; only a valid older status establishes lack of support. */
fun parseObsidianCapability(status: Int, body: String): Boolean? {
    if (status != 200) return null
    val root = runCatching { parseJson(body) as? Map<*, *> }.getOrNull() ?: return null
    if (!root.containsKey("capabilities")) return false
    val caps = root["capabilities"] as? List<*> ?: return null
    if (caps.any { it !is String }) return null
    return "obsidian_notes" in caps
}

data class ExistingObsidianVault(val vault: String, val device: String?, val lastSyncedAt: String)

/** null is a failed list, never an empty successful list that permits first delivery. */
fun parseOtherObsidianVaults(status: Int, body: String): List<ExistingObsidianVault>? {
    if (status == 404) return emptyList()
    if (status != 200) return null
    return runCatching {
        val root = parseJson(body) as? Map<*, *> ?: return null
        val listed = root["vaults"] as? List<*> ?: return null
        listed.map { entry ->
            val m = entry as? Map<*, *> ?: return null
            val vault = m["vault"] as? String ?: return null
            if (vault.toByteArray(Charsets.UTF_8).size !in 1..128 || vault.any { Character.isISOControl(it) }) return null
            val device = m["device"]
            if (device != null && device !is String) return null
            val date = m["last_synced_at"] as? String ?: return null
            OffsetDateTime.parse(date)
            ExistingObsidianVault(vault, device as String?, date)
        }
    }.getOrNull()
}

/** A source-only resume can acknowledge one global stop, never a later stop. */
fun obsidianOwnerStopHolds(currentWitness: String?, acknowledgedWitness: String?): Boolean =
    currentWitness != null && currentWitness != acknowledgedWitness
