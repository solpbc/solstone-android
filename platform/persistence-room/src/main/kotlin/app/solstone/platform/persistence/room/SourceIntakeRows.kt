// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Monotonic intake for one source. Not rebuilt from delivered rows. */
@Entity(tableName = "source_intake")
data class SourceIntakeRow(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    val total: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "last_at") val lastAt: Long,
)

/** The newest location fix already counted, so the next segment can continue from it. */
@Entity(tableName = "source_intake_fix")
data class SourceIntakeFixRow(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "fix_at") val fixAt: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double?,
)

/** Source identities and replay markers already applied. Survives inventory rewrites. */
@Entity(tableName = "source_intake_seen", primaryKeys = ["source_id", "seen_key"])
data class SourceIntakeSeenRow(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "seen_key") val seenKey: String,
)

fun SourceIntakeRow.toReading(): IntakeReading = IntakeReading(total, startedAt, lastAt)

fun IntakeReading.toRow(sourceId: String): SourceIntakeRow = SourceIntakeRow(sourceId, total, startedAt, lastAt)

fun SourceIntakeFixRow.toFix(): IntakeFix = IntakeFix(fixAt, lat, lon, accuracyM)

fun IntakeFix.toFixRow(sourceId: String): SourceIntakeFixRow =
    SourceIntakeFixRow(sourceId, atEpochMs, lat, lon, accuracyMeters)
