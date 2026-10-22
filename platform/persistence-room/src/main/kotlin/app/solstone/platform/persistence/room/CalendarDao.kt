// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_settings WHERE id = 0")
    fun settings(): CalendarSettingsRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSettings(row: CalendarSettingsRow)

    @Query("SELECT * FROM calendar_calendar ORDER BY provider_id ASC")
    fun calendars(): List<CalendarCalendarRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCalendars(rows: List<CalendarCalendarRow>)

    @Query("DELETE FROM calendar_calendar WHERE provider_id NOT IN (:ids)")
    fun deleteCalendarsExcept(ids: List<Long>)

    @Query("SELECT * FROM calendar_count WHERE generation = :generation")
    fun countFor(generation: Long): CalendarCountRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCount(row: CalendarCountRow)

    @Query("SELECT * FROM calendar_identity")
    fun identities(): List<CalendarIdentityRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertIdentities(rows: List<CalendarIdentityRow>)

    @Query("DELETE FROM calendar_identity")
    fun clearIdentities()

    @Query("SELECT * FROM calendar_intent")
    fun intents(): List<CalendarIntentRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertIntents(rows: List<CalendarIntentRow>)

    @Query("SELECT * FROM calendar_capability WHERE instance_id = :instanceId AND ca_fingerprint = :caFingerprint")
    fun capability(instanceId: String, caFingerprint: String): CalendarCapabilityRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCapability(row: CalendarCapabilityRow)

    @Query("DELETE FROM calendar_preview WHERE generation = :generation")
    fun clearPreview(generation: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertPreview(rows: List<CalendarPreviewRow>)

    @Query("SELECT COUNT(DISTINCT identity_key) FROM calendar_preview WHERE generation = :generation")
    fun previewCount(generation: Long): Int

    @Query("SELECT calendar_id, COUNT(*) AS count FROM calendar_preview WHERE generation = :generation GROUP BY calendar_id")
    fun previewCounts(generation: Long): List<CalendarPreviewCount>

    @Query("SELECT DISTINCT identity_key FROM calendar_preview WHERE generation = :generation ORDER BY identity_key LIMIT :limit OFFSET :offset")
    fun previewKeys(generation: Long, offset: Int, limit: Int): List<String>

    @Query("SELECT * FROM calendar_preview WHERE generation = :generation AND identity_key IN (:keys) ORDER BY identity_key, calendar_id")
    fun previewRows(generation: Long, keys: List<String>): List<CalendarPreviewRow>

    @Query("SELECT body FROM calendar_preview")
    fun previewBodyRefs(): List<String>

    @Query("SELECT body FROM calendar_intent WHERE sealed = 0")
    fun intentBodyRefs(): List<String>

    @Query("SELECT pending_canonical FROM calendar_identity WHERE pending_canonical IS NOT NULL")
    fun pendingBodyRefs(): List<String>

    @Query("DELETE FROM calendar_intent WHERE sealed = 1")
    fun clearSealedIntents()

    @Query("SELECT * FROM calendar_count WHERE import_cursor IS NOT NULL ORDER BY generation")
    fun pendingImports(): List<CalendarCountRow>

    @Query("SELECT COUNT(*) FROM calendar_count WHERE import_cursor IS NOT NULL")
    fun pendingImportCount(): Int

    @Query("DELETE FROM calendar_preview WHERE generation IN (SELECT generation FROM calendar_count WHERE status = 'confirmed' AND import_cursor IS NULL)")
    fun clearCompletedPreviews()

    @Transaction
    fun saveImportProgress(count: CalendarCountRow, settings: CalendarSettingsRow) {
        upsertCount(count)
        upsertSettings(settings)
    }

    @Transaction
    fun confirmImport(count: CalendarCountRow, settings: CalendarSettingsRow, calendars: List<CalendarCalendarRow>) {
        upsertCount(count)
        saveConfiguration(settings, calendars)
    }

    @Query("DELETE FROM calendar_preview WHERE generation != :current AND generation != :confirmed AND generation NOT IN (SELECT generation FROM calendar_count WHERE import_cursor IS NOT NULL)")
    fun clearObsoletePreviews(current: Long, confirmed: Long)

    @Transaction
    fun saveConfiguration(settings: CalendarSettingsRow, calendars: List<CalendarCalendarRow>) {
        upsertSettings(settings)
        if (calendars.isNotEmpty()) upsertCalendars(calendars)
        deleteCalendarsExcept(calendars.map { it.providerId }.ifEmpty { listOf(-1L) })
    }

    @Transaction
    fun stage(identities: List<CalendarIdentityRow>, intents: List<CalendarIntentRow>, settings: CalendarSettingsRow?, count: CalendarCountRow?, canCommit: () -> Boolean): Boolean {
        if (!canCommit()) return false
        replaceIdentities(identities)
        if (intents.isNotEmpty()) upsertIntents(intents)
        if (settings != null) upsertSettings(settings)
        if (count != null) upsertCount(count)
        return true
    }

    @Transaction
    fun replaceIdentities(rows: List<CalendarIdentityRow>) {
        clearIdentities()
        if (rows.isNotEmpty()) upsertIdentities(rows)
    }
}
