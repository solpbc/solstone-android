// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.persistence.room

import androidx.room.*
import app.solstone.core.obsidian.ObsidianCounterOp
import java.util.concurrent.CancellationException

/** One shared lifetime row; source transactions arrive through their durable ordered outbox. */
@Dao
abstract class ObsidianIntakeDao {
    @Query("SELECT * FROM source_intake WHERE source_id = 'obsidian'")
    abstract fun reading(): SourceIntakeRow?
    @Query("SELECT seen_key FROM source_intake_seen WHERE source_id = 'obsidian' AND seen_key >= :start AND seen_key < :end")
    abstract fun markers(start: String, end: String): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM source_intake_seen WHERE source_id = 'obsidian' AND seen_key = :key)")
    abstract fun seen(key: String): Boolean
    @Query("DELETE FROM source_intake_seen WHERE source_id = 'obsidian' AND seen_key = :key")
    abstract fun removeSeen(key: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertSeen(row: SourceIntakeSeenRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveReading(row: SourceIntakeRow)

    @Transaction
    open fun apply(ops: List<ObsidianCounterOp>, now: Long, canCommit: () -> Boolean = { true }) {
        val high = mutableMapOf<String, Long>()
        val priorMarkers = mutableMapOf<String, List<String>>()
        var intake = reading()?.toReading()
        var changed = false
        for (op in ops.sortedWith(compareBy({ it.root }, { it.sequence }))) {
            if (!canCommit()) throw CancellationException("Obsidian counter apply cancelled")
            require(op.sequence > 0 && op.kind in setOf("baseline", "new", "rename"))
            val prefix = "op:${op.root}:"
            val applied = high.getOrPut(op.root) {
                val rows = markers(prefix, prefix + "~")
                priorMarkers[op.root] = rows
                rows.maxOfOrNull { it.removePrefix(prefix).toLong() } ?: 0L
            }
            if (op.sequence <= applied) continue
            val key = "path:${op.root}:${op.pathHash}"
            when (op.kind) {
                "baseline" -> insertSeen(SourceIntakeSeenRow("obsidian", key))
                "new" -> if (!seen(key)) {
                    insertSeen(SourceIntakeSeenRow("obsidian", key))
                    intake = advanceIntake(intake, 1, op.observedMs, now, touch = true)
                    changed = true
                }
                "rename" -> {
                    removeSeen("path:${op.root}:${requireNotNull(op.previousHash)}")
                    insertSeen(SourceIntakeSeenRow("obsidian", key))
                }
            }
            high[op.root] = op.sequence
        }
        if (changed) saveReading(requireNotNull(intake).toRow("obsidian"))
        for ((root, sequence) in high) {
            priorMarkers.getValue(root).forEach(::removeSeen)
            insertSeen(SourceIntakeSeenRow("obsidian", "op:$root:$sequence"))
        }
        if (!canCommit()) throw CancellationException("Obsidian counter apply cancelled")
    }
}
