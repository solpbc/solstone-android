// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.solstone.core.model.QueueState

@Entity(tableName = "segment")
data class SegmentRow(
    @PrimaryKey val id: String,
    val day: String,
    val stream: String,
    val segment: String,
    val state: QueueState,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "sealed_at") val sealedAt: Long,
    @ColumnInfo(name = "home_instance_id") val homeInstanceId: String?,
    @ColumnInfo(name = "observer_handle") val observerHandle: String?,
    @ColumnInfo(name = "server_key") val serverKey: String? = null,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_status_code") val lastStatusCode: Int? = null,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long? = null,
    @ColumnInfo(name = "dedupe_checked_at") val dedupeCheckedAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

@Entity(tableName = "segment_file")
data class SegmentFileRow(
    // Room storage identity only; segment/source/name can repeat when a source reuses payload names.
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "segment_id") val segmentId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val name: String,
    val sha256: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "capture_start_epoch_ms") val captureStartEpochMs: Long,
    @ColumnInfo(name = "capture_end_epoch_ms") val captureEndEpochMs: Long,
)

@Entity(tableName = "event")
data class EventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "segment_id") val segmentId: String?,
    val kind: String,
    @ColumnInfo(name = "at_epoch_ms") val atEpochMs: Long,
    val detail: String?,
)

@Entity(tableName = "sync_state")
data class SyncStateRow(
    // Room singleton-row mechanic only; not a domain column.
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "pending_count") val pendingCount: Int,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long?,
    @ColumnInfo(name = "last_failure_at") val lastFailureAt: Long?,
)
