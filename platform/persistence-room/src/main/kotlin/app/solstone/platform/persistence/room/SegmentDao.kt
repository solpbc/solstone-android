// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.solstone.core.model.QueueState
import app.solstone.core.queue.EvictionApplyResult
import app.solstone.core.queue.EvictionEvent
import app.solstone.core.queue.EvictionResult
import app.solstone.core.queue.QueueEvent
import app.solstone.core.queue.SourceDeleteResult
import app.solstone.core.queue.transition

@Dao
abstract class SegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertSegment(segment: SegmentRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertFiles(files: List<SegmentFileRow>)

    @Insert
    abstract fun insertEvents(events: List<EventRow>)

    @Transaction
    open fun insertSegmentWithFiles(segment: SegmentRow, files: List<SegmentFileRow>) {
        insertSegment(segment)
        deleteFilesBySegmentId(segment.id)
        if (files.isNotEmpty()) insertFiles(files)
    }

    @Query("SELECT * FROM segment WHERE state = :state ORDER BY sealed_at ASC, id ASC")
    abstract fun segmentsByState(state: QueueState): List<SegmentRow>

    @Query("SELECT * FROM segment WHERE day = :day ORDER BY sealed_at ASC, id ASC")
    abstract fun segmentsByDay(day: String): List<SegmentRow>

    @Query("SELECT * FROM segment_file WHERE sha256 = :sha256 ORDER BY segment_id ASC, rowId ASC")
    abstract fun duplicateBySha256(sha256: String): List<SegmentFileRow>

    @Transaction
    open fun advanceState(id: String, event: QueueEvent): QueueState {
        val current = segmentState(id) ?: throw NoSuchElementException("segment not found: $id")
        val next = transition(current, event)
        updateState(id, next)
        return next
    }

    @Transaction
    open fun applyEvictions(result: EvictionResult): EvictionApplyResult {
        val ids = result.evictions.map { it.segmentId }
        ids.forEach { advanceState(it, QueueEvent.EVICT) }
        val deletedRows = if (ids.isEmpty()) 0 else deleteFilesBySegmentIds(ids)
        if (result.events.isNotEmpty()) {
            insertEvents(result.events.map { it.toRow() })
        }
        return EvictionApplyResult(
            evictedSegmentIds = ids,
            deletedFileRows = deletedRows,
            eventsInserted = result.events.size,
        )
    }

    @Transaction
    open fun deleteSource(sourceId: String): SourceDeleteResult =
        SourceDeleteResult(sourceId, deleteFilesBySource(sourceId))

    @Query("SELECT state FROM segment WHERE id = :id")
    protected abstract fun segmentState(id: String): QueueState?

    @Query("UPDATE segment SET state = :state WHERE id = :id")
    protected abstract fun updateState(id: String, state: QueueState): Int

    @Query("DELETE FROM segment_file WHERE segment_id = :segmentId")
    protected abstract fun deleteFilesBySegmentId(segmentId: String): Int

    @Query("DELETE FROM segment_file WHERE segment_id IN (:segmentIds)")
    protected abstract fun deleteFilesBySegmentIds(segmentIds: List<String>): Int

    @Query("DELETE FROM segment_file WHERE source_id = :sourceId")
    protected abstract fun deleteFilesBySource(sourceId: String): Int

    private fun EvictionEvent.toRow(): EventRow =
        EventRow(
            segmentId = segmentId,
            kind = kind,
            atEpochMs = atEpochMs,
            detail = detail,
        )
}
