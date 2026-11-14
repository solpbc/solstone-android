// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.QueueState

/**
 * In-memory intake for a hand-written [SegmentDao] double.
 * The database dao is generated from [SegmentDao] itself and does not use this class.
 */
abstract class SegmentDaoWithoutIntake : SegmentDao() {
    private val intake = mutableMapOf<String, SourceIntakeRow>()
    private val fixes = mutableMapOf<String, SourceIntakeFixRow>()
    private val seen = mutableSetOf<Pair<String, String>>()

    override fun intakeBySource(sourceId: String): SourceIntakeRow? = intake[sourceId]

    override fun allIntake(): List<SourceIntakeRow> = intake.values.toList()

    override fun upsertIntake(row: SourceIntakeRow) {
        intake[row.sourceId] = row
    }

    override fun intakeFix(sourceId: String): SourceIntakeFixRow? = fixes[sourceId]

    override fun upsertIntakeFix(row: SourceIntakeFixRow) {
        fixes[row.sourceId] = row
    }

    override fun intakeSeenKeys(sourceId: String, keys: List<String>): List<String> =
        keys.filter { (sourceId to it) in seen }

    override fun insertIntakeSeen(rows: List<SourceIntakeSeenRow>) {
        rows.forEach { seen += it.sourceId to it.seenKey }
    }

    override fun evictedWithLocalCopy(): List<SegmentRow> =
        segmentsByState(QueueState.EVICTED).filterNot { it.localCopyAbsent }

    override fun markLocalCopyAbsent(id: String): Int {
        val row = segmentById(id)?.takeIf { it.state == QueueState.EVICTED } ?: return 0
        insertSegment(row.copy(localCopyAbsent = true))
        return 1
    }
}
