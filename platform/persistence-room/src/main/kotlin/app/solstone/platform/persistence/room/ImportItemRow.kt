// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
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
    @ColumnInfo(name = "progress_timestamp") val progressTimestamp: String?,
    @ColumnInfo(name = "progress_instance_id") val progressInstanceId: String?,
    @ColumnInfo(name = "backoff_instance_id") val backoffInstanceId: String?,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long?,
    @ColumnInfo(name = "attention_error") val attentionError: String?,
    @ColumnInfo(name = "attention_reason_code") val attentionReasonCode: String?,
    @ColumnInfo(name = "attention_kind") val attentionKind: String?,
    @ColumnInfo(name = "attention_instance_id") val attentionInstanceId: String?,
    @ColumnInfo(name = "attention_journal_version") val attentionJournalVersion: String?,
    @ColumnInfo(name = "review_import_id") val reviewImportId: String? = null,
    @ColumnInfo(name = "review_reason_code") val reviewReasonCode: String? = null,
    @ColumnInfo(name = "review_kind") val reviewKind: String? = null,
    @ColumnInfo(name = "review_instance_id") val reviewInstanceId: String? = null,
    @ColumnInfo(name = "review_expired", defaultValue = "0") val reviewExpired: Boolean = false,
    @ColumnInfo(name = "payload_sha256") val payloadSha256: String? = null,
    @ColumnInfo(name = "upload_id") val uploadId: String? = null,
    @ColumnInfo(name = "upload_instance_id") val uploadInstanceId: String? = null,
    @ColumnInfo(name = "upload_offset", defaultValue = "0") val uploadOffset: Long = 0L,
    @ColumnInfo(name = "upload_resumable", defaultValue = "0") val uploadResumable: Boolean = false,
)

/** When an import last reached a journal, and which journal: one row, id 0. */
@Entity(tableName = "import_delivery")
data class ImportDeliveryRow(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "last_delivered_at") val lastDeliveredAt: Long,
    @ColumnInfo(name = "instance_id") val instanceId: String,
)

/** A lightweight persisted 24-hour recent receipt for a delivered import, scoped to one journal instance. */
@Entity(
    tableName = "import_recent_receipt",
    indices = [Index(value = ["instance_id", "delivered_at"])],
)
data class ImportRecentReceiptRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "filename") val filename: String,
    @ColumnInfo(name = "import_id") val importId: String?,
    @ColumnInfo(name = "instance_id") val instanceId: String,
    @ColumnInfo(name = "delivered_at") val deliveredAt: Long,
)
