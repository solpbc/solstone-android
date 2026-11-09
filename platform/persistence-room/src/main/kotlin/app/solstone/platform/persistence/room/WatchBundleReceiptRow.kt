// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_bundle_receipt")
data class WatchBundleReceiptRow(
    @PrimaryKey
    @ColumnInfo(name = "bundle_id")
    val bundleId: String,
    @ColumnInfo(name = "sha256")
    val sha256: String,
    @ColumnInfo(name = "seal_generation")
    val sealGeneration: Long,
    @ColumnInfo(name = "segment_id")
    val segmentId: String,
    @ColumnInfo(name = "committed_at")
    val committedAt: Long,
)
