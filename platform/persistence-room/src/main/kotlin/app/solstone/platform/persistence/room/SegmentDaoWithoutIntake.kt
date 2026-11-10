// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

/**
 * In-memory intake for a hand-written [SegmentDao] double.
 * The database dao is generated from [SegmentDao] itself and does not use this class.
 */
abstract class SegmentDaoWithoutIntake : SegmentDao() {
    private val intake = mutableMapOf<String, SourceIntakeRow>()
    private val fixes = mutableMapOf<String, SourceIntakeFixRow>()

    override fun intakeBySource(sourceId: String): SourceIntakeRow? = intake[sourceId]

    override fun allIntake(): List<SourceIntakeRow> = intake.values.toList()

    override fun upsertIntake(row: SourceIntakeRow) {
        intake[row.sourceId] = row
    }

    override fun intakeFix(sourceId: String): SourceIntakeFixRow? = fixes[sourceId]

    override fun upsertIntakeFix(row: SourceIntakeFixRow) {
        fixes[row.sourceId] = row
    }
}
