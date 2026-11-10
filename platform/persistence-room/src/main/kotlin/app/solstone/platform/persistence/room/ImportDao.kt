// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ImportDao {
    @Insert
    fun insert(row: ImportItemRow)

    @Update
    fun update(row: ImportItemRow): Int

    @Query("DELETE FROM import_item WHERE id = :id")
    fun delete(id: String): Int

    @Query("SELECT * FROM import_item WHERE id = :id")
    fun byId(id: String): ImportItemRow?

    @Query("SELECT * FROM import_item ORDER BY created_at ASC, id ASC")
    fun all(): List<ImportItemRow>

    /** The single delivery record is replaced in place; it is not an import row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putDelivery(row: ImportDeliveryRow)

    @Query("SELECT * FROM import_delivery WHERE id = 0")
    fun delivery(): ImportDeliveryRow?

    @Query("SELECT * FROM source_intake WHERE source_id = :sourceId")
    fun intakeBySource(sourceId: String): SourceIntakeRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertIntake(row: SourceIntakeRow)

    /** Inserts [row] and counts it once. A row already stored is left as it is. */
    @Transaction
    fun insertIfNew(row: ImportItemRow, nowEpochMs: Long): Boolean {
        if (byId(row.id) != null) return false
        insert(row)
        val existing = intakeBySource(INTAKE_IMPORT)?.toReading()
        val next = advanceIntake(existing, add = 1, materialAt = row.createdAt, now = nowEpochMs, touch = true) ?: return true
        upsertIntake(next.toRow(INTAKE_IMPORT))
        return true
    }
}
