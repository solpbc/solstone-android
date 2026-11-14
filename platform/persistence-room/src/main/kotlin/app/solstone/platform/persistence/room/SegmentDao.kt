// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.solstone.core.model.QueueState
import app.solstone.core.queue.QueueEvent
import app.solstone.core.queue.transition

@Dao
abstract class SegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertSegment(segment: SegmentRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertFiles(files: List<SegmentFileRow>)

    @Insert
    abstract fun insertEvents(events: List<EventRow>)

    open fun insertSegmentWithFiles(segment: SegmentRow, files: List<SegmentFileRow>) {
        insertSegmentWithFiles(segment, files, emptyList(), System.currentTimeMillis())
    }

    /**
     * Counts audio, screen, camera, and location only on the first insert of [segment].
     * A later call refreshes files and does not count again. [locationFixes] are ignored when empty.
     */
    @Transaction
    open fun insertSegmentWithFiles(
        segment: SegmentRow,
        files: List<SegmentFileRow>,
        locationFixes: List<IntakeFix>,
        nowEpochMs: Long,
    ) {
        val first = segmentById(segment.id) == null
        if (first) insertSegment(segment)
        deleteFilesBySegmentId(segment.id)
        if (files.isNotEmpty()) insertFiles(files)
        if (first) recordSegmentIntake(segment.id, files, locationFixes, nowEpochMs)
    }

    @Query("SELECT * FROM source_intake WHERE source_id = :sourceId")
    abstract fun intakeBySource(sourceId: String): SourceIntakeRow?

    @Query("SELECT * FROM source_intake")
    abstract fun allIntake(): List<SourceIntakeRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertIntake(row: SourceIntakeRow)

    @Query("SELECT * FROM source_intake_fix WHERE source_id = :sourceId")
    abstract fun intakeFix(sourceId: String): SourceIntakeFixRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertIntakeFix(row: SourceIntakeFixRow)

    @Query("SELECT seen_key FROM source_intake_seen WHERE source_id = :sourceId AND seen_key IN (:keys)")
    abstract fun intakeSeenKeys(sourceId: String, keys: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertIntakeSeen(rows: List<SourceIntakeSeenRow>)

    private fun recordSegmentIntake(
        segmentId: String,
        files: List<SegmentFileRow>,
        fixes: List<IntakeFix>,
        nowEpochMs: Long,
    ) {
        val anchor = intakeFix(INTAKE_LOCATION)?.toFix()
        val counted = screenshotsNotYetCounted(segmentId, files)
        for (delta in segmentIntakeDeltas(counted, fixes, anchor, keptAtMs = nowEpochMs)) {
            val existing = intakeBySource(delta.sourceId)?.toReading()
            val next = advanceIntake(existing, delta.add, delta.materialAt, nowEpochMs, touch = true) ?: continue
            upsertIntake(next.toRow(delta.sourceId))
            if (delta.anchor != null) upsertIntakeFix(delta.anchor.toFixRow(INTAKE_LOCATION))
        }
    }

    /**
     * A screenshot counts once, by its bytes. Sending it again after a new pairing, or bringing it
     * in again after the source is turned back on, keeps a new segment but is not a new screenshot.
     * One kept before this record existed is known by another segment holding the same bytes.
     */
    private fun screenshotsNotYetCounted(segmentId: String, files: List<SegmentFileRow>): List<SegmentFileRow> {
        val shots = files.filter { it.sourceId == INTAKE_SCREENSHOTS && it.byteSize > 0L }
        if (shots.isEmpty()) return files
        val keys = shots.map { it.sha256 }.distinct()
        val seen = intakeSeenKeys(INTAKE_SCREENSHOTS, keys).toMutableSet()
        for (key in keys) {
            if (key in seen) continue
            val keptElsewhere = duplicateBySha256(key).any {
                it.sourceId == INTAKE_SCREENSHOTS && it.segmentId != segmentId
            }
            if (keptElsewhere) seen += key
        }
        insertIntakeSeen(keys.map { SourceIntakeSeenRow(INTAKE_SCREENSHOTS, it) })
        val fresh = HashSet<String>()
        return files.filter { row ->
            row.sourceId != INTAKE_SCREENSHOTS || (row.sha256 !in seen && fresh.add(row.sha256))
        }
    }

    @Query("SELECT * FROM segment WHERE state = :state ORDER BY sealed_at ASC, id ASC")
    abstract fun segmentsByState(state: QueueState): List<SegmentRow>

    @Query("SELECT * FROM segment WHERE state = 'EVICTED' AND local_copy_absent = 0 ORDER BY sealed_at ASC, id ASC")
    abstract fun evictedWithLocalCopy(): List<SegmentRow>

    @Query("UPDATE segment SET local_copy_absent = 1 WHERE id = :id AND state = 'EVICTED'")
    abstract fun markLocalCopyAbsent(id: String): Int

    @Query("SELECT * FROM segment WHERE stream = :stream AND state IN ('SEALED','UPLOADING','FAILED') ORDER BY sealed_at ASC, id ASC")
    abstract fun segmentsForDrain(stream: String): List<SegmentRow>

    @Query("SELECT * FROM segment WHERE day = :day ORDER BY sealed_at ASC, id ASC")
    abstract fun segmentsByDay(day: String): List<SegmentRow>

    @Query("SELECT * FROM segment WHERE id = :id")
    abstract fun segmentById(id: String): SegmentRow?

    @Query("SELECT * FROM segment_file WHERE sha256 = :sha256 ORDER BY segment_id ASC, rowId ASC")
    abstract fun duplicateBySha256(sha256: String): List<SegmentFileRow>

    @Query("SELECT * FROM segment_file WHERE segment_id = :segmentId ORDER BY rowId ASC")
    abstract fun filesBySegmentId(segmentId: String): List<SegmentFileRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertReceipt(receipt: WatchBundleReceiptRow)

    @Query("SELECT * FROM watch_bundle_receipt WHERE bundle_id = :bundleId")
    abstract fun receiptByBundleId(bundleId: String): WatchBundleReceiptRow?

    enum class WatchBundleCustodyResult {
        INSERTED,
        DUPLICATE_SAME_SHA,
        CONFLICT_DIFFERENT_SHA,
    }

    @Transaction
    open fun insertWatchBundleReceiptTransaction(
        receipt: WatchBundleReceiptRow,
        segment: SegmentRow,
        files: List<SegmentFileRow>,
    ): WatchBundleCustodyResult {
        val existing = receiptByBundleId(receipt.bundleId)
        if (existing != null) {
            return if (existing.sha256.equals(receipt.sha256, ignoreCase = true)) {
                WatchBundleCustodyResult.DUPLICATE_SAME_SHA
            } else {
                WatchBundleCustodyResult.CONFLICT_DIFFERENT_SHA
            }
        }
        insertReceipt(receipt)
        if (segmentById(segment.id) == null) {
            insertSegment(segment)
        }
        deleteFilesBySegmentId(segment.id)
        if (files.isNotEmpty()) {
            insertFiles(files)
        }
        return WatchBundleCustodyResult.INSERTED
    }

    // `home_instance_id` names the journal this attempt went to, so a later drain can tell a
    // back-off earned at a journal the device has since left from one earned at its journal now.
    @Query("UPDATE segment SET attempt_count = :attempts, last_attempt_at = :at, home_instance_id = :homeInstanceId WHERE id = :id")
    abstract fun recordAttempt(id: String, attempts: Int, at: Long, homeInstanceId: String?): Int

    @Query("UPDATE segment SET last_error = NULL WHERE id = :id")
    abstract fun recordUploaded(id: String): Int

    @Query("UPDATE segment SET last_status_code = :code, last_error = :error WHERE id = :id")
    abstract fun recordFailure(id: String, code: Int?, error: String?): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertSyncState(row: SyncStateRow)

    @Query("SELECT * FROM sync_state WHERE id = 0")
    abstract fun syncState(): SyncStateRow?

    @Query("SELECT COUNT(*) FROM segment WHERE stream = :stream AND state IN ('SEALED','UPLOADING','FAILED') AND (last_error IS NULL OR last_error != 'removed_in_journal')")
    abstract fun pendingCount(stream: String): Int

    @Query(
        "SELECT DISTINCT f.source_id FROM segment_file f " +
            "JOIN segment s ON s.id = f.segment_id " +
            "WHERE s.stream = :stream AND s.state IN ('SEALED','UPLOADING','FAILED') AND (s.last_error IS NULL OR s.last_error != 'removed_in_journal')",
    )
    abstract fun pendingSourceIds(stream: String): List<String>

    @Transaction
    open fun advanceState(id: String, event: QueueEvent): QueueState {
        val current = segmentState(id) ?: throw NoSuchElementException("segment not found: $id")
        val next = transition(current, event)
        updateState(id, next)
        return next
    }

    @Transaction
    open fun finishLegacyRemovedInJournal(id: String): Boolean {
        val row = segmentById(id) ?: return false
        if (row.state != QueueState.FAILED || row.lastError != "removed_in_journal") return false
        updateState(id, QueueState.EVICTED)
        return true
    }

    @Query("SELECT state FROM segment WHERE id = :id")
    abstract fun segmentState(id: String): QueueState?

    @Query("UPDATE segment SET state = :state WHERE id = :id")
    abstract fun updateState(id: String, state: QueueState): Int

    @Query("DELETE FROM segment_file WHERE segment_id = :segmentId")
    protected abstract fun deleteFilesBySegmentId(segmentId: String): Int

    @Query("DELETE FROM segment_file WHERE segment_id IN (:segmentIds)")
    protected abstract fun deleteFilesBySegmentIds(segmentIds: List<String>): Int
}
