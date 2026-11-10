// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.obsidian

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson

/** A text-free, journal-independent handoff from the source transaction to the shared intake store. */
data class ObsidianCounterOp(
    val root: String, val sequence: Long, val kind: String, val pathHash: String,
    val previousHash: String?, val observedMs: Long,
) {
    val key: String get() = sequence.toString().padStart(20, '0')
}

/** Called inside the same transaction that changes a known-note identity or accepts a new note. */
internal fun enqueueObsidianCounter(ledger: ObsidianLedger, root: String, kind: String,
    pathHash: String, previousHash: String? = null, observedMs: Long = 0,
) {
    val sequence = (ledger.get(root, "meta", "counter_sequence")?.toLong() ?: 0) + 1
    check(sequence > 0)
    ledger.put(root, "meta", "counter_sequence", sequence.toString())
    ledger.put(root, "counter_ops", sequence.toString().padStart(20, '0'), toJson(linkedMapOf(
        "kind" to kind, "path" to pathHash, "previous" to previousHash, "observed" to observedMs)))
}

fun pendingObsidianCounters(ledger: ObsidianLedger, limit: Int = 500): List<ObsidianCounterOp> {
    require(limit > 0)
    val out = mutableListOf<ObsidianCounterOp>()
    for (root in ledger.roots().sorted()) {
        for (key in ledger.keys(root, "counter_ops").sorted().take(limit - out.size)) {
            val m = parseJson(requireNotNull(ledger.get(root, "counter_ops", key))) as Map<*, *>
            out += ObsidianCounterOp(root, key.toLong(), m["kind"] as String, m["path"] as String,
                m["previous"] as String?, (m["observed"] as Number).toLong())
        }
        if (out.size == limit) break
    }
    return out
}

fun retireObsidianCounters(ledger: ObsidianLedger, applied: List<ObsidianCounterOp>) = ledger.transaction {
    for (op in applied) ledger.remove(op.root, "counter_ops", op.key)
}
