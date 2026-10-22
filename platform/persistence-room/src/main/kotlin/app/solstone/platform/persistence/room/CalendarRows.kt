// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row, id 0. Note bodies are not stored here. */
@Entity(tableName = "calendar_settings")
data class CalendarSettingsRow(
    @PrimaryKey val id: Int = 0,
    val enabled: Boolean,
    @ColumnInfo(name = "count_generation") val countGeneration: Long,
    @ColumnInfo(name = "consent_token") val consentToken: Long,
    @ColumnInfo(name = "confirmed_generation") val confirmedGeneration: Long?,
    @ColumnInfo(name = "history_kind") val historyKind: String,
    @ColumnInfo(name = "confirmed_history_kind") val confirmedHistoryKind: String?,
    @ColumnInfo(name = "lower_bound_epoch_day") val lowerBoundEpochDay: Long?,
    @ColumnInfo(name = "confirmed_lower_bound_epoch_day") val confirmedLowerBoundEpochDay: Long?,
    @ColumnInfo(name = "fallback_namespace") val fallbackNamespace: String,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long?,
    @ColumnInfo(name = "import_slice_cursor") val importSliceCursor: Long?,
    @ColumnInfo(name = "import_done") val importDone: Boolean,
    @ColumnInfo(name = "import_total") val importTotal: Int?,
    @ColumnInfo(name = "import_completed") val importCompleted: Int,
    @ColumnInfo(name = "last_read_failed") val lastReadFailed: Boolean,
)

@Entity(tableName = "calendar_calendar")
data class CalendarCalendarRow(
    @PrimaryKey @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "owner_account") val ownerAccount: String?,
    @ColumnInfo(name = "account_type") val accountType: String?,
    val selected: Boolean,
    val confirmed: Boolean,
    val visible: Boolean,
)

/** A preview count. It holds no note bodies. */
@Entity(tableName = "calendar_count")
data class CalendarCountRow(
    @PrimaryKey val generation: Long,
    @ColumnInfo(name = "selection_fingerprint") val selectionFingerprint: String,
    val span: String,
    val count: Int,
    val status: String,
    @ColumnInfo(name = "per_calendar") val perCalendar: String,
    @ColumnInfo(name = "import_cursor") val importCursor: Int? = null,
)

@Entity(tableName = "calendar_identity", primaryKeys = ["event", "occurrence"])
data class CalendarIdentityRow(
    val event: String,
    val occurrence: String,
    @ColumnInfo(name = "published_fingerprint") val publishedFingerprint: String?,
    @ColumnInfo(name = "published_changed_at") val publishedChangedAt: String?,
    @ColumnInfo(name = "published_changed_at_epoch_ms") val publishedChangedAtEpochMs: Long?,
    @ColumnInfo(name = "pending_fingerprint") val pendingFingerprint: String?,
    @ColumnInfo(name = "pending_changed_at") val pendingChangedAt: String?,
    @ColumnInfo(name = "pending_changed_at_epoch_ms") val pendingChangedAtEpochMs: Long?,
    @ColumnInfo(name = "pending_canonical") val pendingCanonical: String?,
    @ColumnInfo(name = "pending_intent_id") val pendingIntentId: String?,
    @ColumnInfo(name = "last_title") val lastTitle: String?,
    @ColumnInfo(name = "last_calendar") val lastCalendar: String?,
    val holders: String,
    @ColumnInfo(name = "winning_holder") val winningHolder: Long?,
    @ColumnInfo(name = "name_holder") val nameHolder: Long?,
    @ColumnInfo(name = "miss_count") val missCount: Int,
    @ColumnInfo(name = "first_miss_at") val firstMissAt: Long?,
    @ColumnInfo(name = "last_change") val lastChange: String?,
    @ColumnInfo(name = "touched_at") val touchedAt: Long?,
    @ColumnInfo(name = "last_start_epoch_ms") val lastStartEpochMs: Long? = null,
    @ColumnInfo(name = "provider_event_ids") val providerEventIds: String = "",
)

/** Hash reference to private durable bytes staged before a segment row exists. */
@Entity(tableName = "calendar_intent")
data class CalendarIntentRow(
    @PrimaryKey val id: String,
    val day: String,
    val segment: String,
    val body: String,
    val sealed: Boolean,
    @ColumnInfo(name = "identity_keys") val identityKeys: String,
)

@Entity(tableName = "calendar_capability", primaryKeys = ["instance_id", "ca_fingerprint"])
data class CalendarCapabilityRow(
    @ColumnInfo(name = "instance_id") val instanceId: String,
    @ColumnInfo(name = "ca_fingerprint") val caFingerprint: String,
    @ColumnInfo(name = "calendar_notes") val calendarNotes: Int,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
)

/** Counted content is private and cannot drain until its generation is confirmed. */
@Entity(tableName = "calendar_preview", primaryKeys = ["generation", "identity_key", "calendar_id"])
data class CalendarPreviewRow(
    val generation: Long,
    @ColumnInfo(name = "identity_key") val identityKey: String,
    @ColumnInfo(name = "calendar_id") val calendarId: Long,
    @ColumnInfo(name = "start_epoch_ms") val startEpochMs: Long,
    @ColumnInfo(name = "provider_event_ids") val providerEventIds: String,
    val body: String,
)

data class CalendarPreviewCount(val calendar_id: Long, val count: Int)
