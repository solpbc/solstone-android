// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.obsidian

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.util.UUID
import java.util.concurrent.CancellationException

/** Stable reads awaiting one complete comparison. Never a presence inventory or a latest-text mirror. */
class ObsidianReadStage(private val ledger: ObsidianLedger, private val root: String) {
    val token: String = ledger.transaction {
        val active = ledger.get(root, "meta", "read_stage_active")
        if (active != null && active != ledger.get(root, "meta", "read_stage_applied")) active
        else {
            clearReads()
            UUID.randomUUID().toString().also { ledger.put(root, "meta", "read_stage_active", it) }
        }
    }
    fun fill(entries: List<ObsidianEntry>, read: (ObsidianEntry) -> ObsidianRead,
        now: () -> Long, cancelled: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        check(ledger.get(root, "meta", "read_stage_active") == token) { "vault read stage changed" }
        for ((i, entry) in entries.withIndex()) {
            if (cancelled()) throw CancellationException("vault read stage cancelled")
            if (cached(entry) == null) {
                val value = read(entry)
                if (cancelled()) throw CancellationException("vault read stage cancelled")
                val observed = (value as? ObsidianRead.Stable)?.observedMs ?: now()
                val encoded = toJson(linkedMapOf("document" to entry.documentId, "mtime" to entry.modifiedMs,
                    "size" to entry.size, "observed" to observed,
                    "kind" to when (value) { is ObsidianRead.Stable -> "stable"; ObsidianRead.Unavailable -> "unavailable"; ObsidianRead.TooLarge -> "large" },
                    "text" to (value as? ObsidianRead.Stable)?.text, "id" to (value as? ObsidianRead.Stable)?.fileId))
                ledger.transaction { ledger.put(root, "read_stage", entry.path, encoded) }
            }
            progress(i + 1, entries.size)
        }
    }
    fun read(entry: ObsidianEntry): ObsidianRead = requireNotNull(cached(entry)) { "vault read stage incomplete" }
    private fun cached(entry: ObsidianEntry): ObsidianRead? {
        if (ledger.get(root, "meta", "read_stage_active") != token) return null
        val encoded = ledger.get(root, "read_stage", entry.path) ?: return null
        val m = parseJson(encoded) as Map<*, *>
        if (m["document"] != entry.documentId || (m["mtime"] as? Number)?.toLong() != entry.modifiedMs ||
            (m["size"] as? Number)?.toLong() != entry.size) return null
        return when (m["kind"]) {
            "stable" -> ObsidianRead.Stable(m["text"] as String, m["id"] as String?, (m["observed"] as Number).toLong())
            "unavailable" -> ObsidianRead.Unavailable
            "large" -> ObsidianRead.TooLarge
            else -> error("invalid vault read stage")
        }
    }
    fun clearApplied() = ledger.transaction {
        if (ledger.get(root, "meta", "read_stage_applied") == token && ledger.get(root, "meta", "read_stage_active") == token) {
            clearReads(); ledger.remove(root, "meta", "read_stage_active")
        }
    }
    private fun clearReads() { ledger.keys(root, "read_stage").forEach { ledger.remove(root, "read_stage", it) } }
}
