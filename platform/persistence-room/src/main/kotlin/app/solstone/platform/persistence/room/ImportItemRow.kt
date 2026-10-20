// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "import_item")
data class ImportItemRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "payload_kind") val payloadKind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val mime: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "progress_path") val progressPath: String?,
    @ColumnInfo(name = "progress_at") val progressAt: Long?,
    @ColumnInfo(name = "progress_instance_id") val progressInstanceId: String?,
    @ColumnInfo(name = "backoff_instance_id") val backoffInstanceId: String?,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long?,
    @ColumnInfo(name = "attention_error") val attentionError: String?,
    @ColumnInfo(name = "attention_reason_code") val attentionReasonCode: String?,
    @ColumnInfo(name = "attention_kind") val attentionKind: String?,
    @ColumnInfo(name = "attention_instance_id") val attentionInstanceId: String?,
    @ColumnInfo(name = "attention_journal_version") val attentionJournalVersion: String?,
)
