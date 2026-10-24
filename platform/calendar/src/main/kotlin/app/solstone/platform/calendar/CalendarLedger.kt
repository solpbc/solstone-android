// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.calendar

import app.solstone.core.calendar.CalendarIdentityRecord
import app.solstone.core.calendar.SeenEvent
import app.solstone.core.calendar.encodeCalendarLine
import app.solstone.core.calendar.decodeCalendarLine
import app.solstone.core.calendar.occurrenceKey
import app.solstone.platform.persistence.room.CalendarPreviewRow
import java.security.MessageDigest
import app.solstone.core.calendar.CalendarIntentRecord
import app.solstone.platform.persistence.room.CalendarCalendarRow
import app.solstone.platform.persistence.room.CalendarCountRow
import app.solstone.platform.persistence.room.CalendarDao
import app.solstone.platform.persistence.room.CalendarIdentityRow
import app.solstone.platform.persistence.room.CalendarIntentRow
import app.solstone.platform.persistence.room.CalendarSettingsRow
import java.util.UUID

interface CalendarLedger {
    fun settings(): CalendarSettingsRow
    fun saveSettings(row: CalendarSettingsRow)
    fun calendars(): List<CalendarCalendarRow>
    fun saveCalendars(rows: List<CalendarCalendarRow>)
    fun identities(): List<CalendarIdentityRecord>
    fun saveIdentities(rows: List<CalendarIdentityRecord>)
    fun intents(): List<CalendarIntentRecord>
    fun saveIntents(rows: List<CalendarIntentRecord>)
    fun countFor(generation: Long): CalendarCountRow?
    fun saveCount(row: CalendarCountRow)
    fun saveConfiguration(settings: CalendarSettingsRow, calendars: List<CalendarCalendarRow>)
    fun stage(identities: List<CalendarIdentityRecord>, intents: List<CalendarIntentRecord>, settings: CalendarSettingsRow? = null, count: CalendarCountRow? = null, canCommit: () -> Boolean = { true }): Boolean
    fun clearPreview(generation: Long)
    fun appendPreview(generation: Long, rows: List<SeenEvent>)
    fun previewCount(generation: Long): Int
    fun previewCounts(generation: Long): Map<Long, Int>
    fun previewPage(generation: Long, offset: Int): List<SeenEvent>
    fun prune()
    fun pendingImports(): List<CalendarCountRow>
    fun confirmImport(count: CalendarCountRow, settings: CalendarSettingsRow, calendars: List<CalendarCalendarRow>)
    fun saveImportProgress(count: CalendarCountRow, settings: CalendarSettingsRow)
}

fun defaultCalendarSettings(): CalendarSettingsRow = CalendarSettingsRow(
    enabled = false,
    countGeneration = 1,
    consentToken = 1,
    confirmedGeneration = null,
    historyKind = "DAYS_30",
    confirmedHistoryKind = null,
    lowerBoundEpochDay = null,
    confirmedLowerBoundEpochDay = null,
    fallbackNamespace = UUID.randomUUID().toString(),
    lastSuccessAt = null,
    importSliceCursor = null,
    importDone = true,
    importTotal = null,
    importCompleted = 0,
    lastReadFailed = false,
)

class RoomCalendarLedger(
    private val dao: CalendarDao,
    private val payloads: CalendarPayloads,
) : CalendarLedger {
    override fun settings(): CalendarSettingsRow =
        dao.settings() ?: defaultCalendarSettings().also { dao.upsertSettings(it) }

    override fun saveSettings(row: CalendarSettingsRow) {
        dao.upsertSettings(row.copy(id = 0))
    }

    override fun calendars(): List<CalendarCalendarRow> = dao.calendars()

    override fun saveCalendars(rows: List<CalendarCalendarRow>) {
        if (rows.isEmpty()) {
            dao.deleteCalendarsExcept(listOf(-1L))
        } else {
            dao.upsertCalendars(rows)
            dao.deleteCalendarsExcept(rows.map { it.providerId })
        }
    }

    override fun identities(): List<CalendarIdentityRecord> = dao.identities().map { it.toRecord().let { row -> row.copy(pendingCanonical = row.pendingCanonical?.let { payloads.read(it).toString(Charsets.UTF_8) }) } }

    override fun saveIdentities(rows: List<CalendarIdentityRecord>) {
        dao.replaceIdentities(rows.map { storedIdentity(it) })
    }

    override fun intents(): List<CalendarIntentRecord> = dao.intents().filter { !it.sealed }.map { it.toRecord(payloads.read(it.body)) }

    override fun saveIntents(rows: List<CalendarIntentRecord>) {
        if (rows.isNotEmpty()) dao.upsertIntents(rows.map { storedIntent(it) })
    }

    override fun countFor(generation: Long): CalendarCountRow? = dao.countFor(generation)

    override fun saveCount(row: CalendarCountRow) { dao.upsertCount(row) }

    override fun saveConfiguration(settings: CalendarSettingsRow, calendars: List<CalendarCalendarRow>) =
        dao.saveConfiguration(settings, calendars)

    private fun storedIdentity(row: CalendarIdentityRecord): CalendarIdentityRow = row.toRow().copy(
        pendingCanonical = row.pendingCanonical?.let { payloads.put(it.toByteArray(Charsets.UTF_8)) },
    )

    private fun storedIntent(row: CalendarIntentRecord): CalendarIntentRow = row.toRow(payloads.put(row.bytes))

    override fun stage(identities: List<CalendarIdentityRecord>, intents: List<CalendarIntentRecord>, settings: CalendarSettingsRow?, count: CalendarCountRow?, canCommit: () -> Boolean): Boolean {
        // Every referenced payload is synced before the single metadata transaction.
        val storedIdentities = identities.map(::storedIdentity)
        val storedIntents = intents.map(::storedIntent)
        return dao.stage(storedIdentities, storedIntents, settings, count, canCommit)
    }

    override fun clearPreview(generation: Long) = dao.clearPreview(generation)

    override fun appendPreview(generation: Long, rows: List<SeenEvent>) {
        dao.upsertPreview(rows.map { row ->
            val key = MessageDigest.getInstance("SHA-256")
                .digest((row.event + "\u0000" + occurrenceKey(row.occurrence)).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            CalendarPreviewRow(generation, key, row.calendarId, row.startEpochMs,
                encodeHolders(row.providerEventIds), payloads.put(encodeCalendarLine(row.note).toByteArray(Charsets.UTF_8)))
        })
    }

    override fun previewCount(generation: Long): Int = dao.previewCount(generation)
    override fun previewCounts(generation: Long): Map<Long, Int> = dao.previewCounts(generation).associate { it.calendar_id to it.count }

    override fun previewPage(generation: Long, offset: Int): List<SeenEvent> {
        val keys = dao.previewKeys(generation, offset, 64)
        if (keys.isEmpty()) return emptyList()
        val selected = mutableListOf<CalendarPreviewRow>()
        var bytes = 0L
        for (holders in dao.previewRows(generation, keys).groupBy { it.identityKey }.values) {
            val size = holders.sumOf { payloads.size(it.body) }
            if (selected.isNotEmpty() && bytes + size > app.solstone.core.calendar.CALENDAR_MAX_BYTES) break
            selected += holders
            bytes += size
        }
        return selected.map { row ->
            val note = decodeCalendarLine(payloads.read(row.body).toString(Charsets.UTF_8))
            SeenEvent(note.event, note.occurrence, row.calendarId, note.calendar, row.startEpochMs,
                note, decodeHolders(row.providerEventIds))
        }
    }

    override fun pendingImports(): List<CalendarCountRow> = dao.pendingImports()
    override fun confirmImport(count: CalendarCountRow, settings: CalendarSettingsRow, calendars: List<CalendarCalendarRow>) =
        dao.confirmImport(count, settings, calendars)
    override fun saveImportProgress(count: CalendarCountRow, settings: CalendarSettingsRow) = dao.saveImportProgress(count, settings)

    override fun prune() {
        dao.clearSealedIntents()
        val current = settings()
        dao.clearObsoletePreviews(current.countGeneration, current.confirmedGeneration ?: -1L)
        dao.clearCompletedPreviews()
        payloads.prune((dao.previewBodyRefs() + dao.intentBodyRefs() + dao.pendingBodyRefs()).toSet())
    }
}

private fun CalendarIdentityRow.toRecord(): CalendarIdentityRecord = CalendarIdentityRecord(
    event = event,
    occurrence = occurrence,
    publishedFingerprint = publishedFingerprint,
    publishedChangedAt = publishedChangedAt,
    publishedChangedAtEpochMs = publishedChangedAtEpochMs,
    pendingFingerprint = pendingFingerprint,
    pendingChangedAt = pendingChangedAt,
    pendingChangedAtEpochMs = pendingChangedAtEpochMs,
    pendingCanonical = pendingCanonical,
    pendingIntentId = pendingIntentId,
    lastTitle = lastTitle,
    lastCalendar = lastCalendar,
    holders = decodeHolders(holders),
    winningHolder = winningHolder,
    missCount = missCount,
    firstMissAt = firstMissAt,
    lastChange = lastChange,
    touchedAt = touchedAt,
    nameHolder = nameHolder,
    lastStartEpochMs = lastStartEpochMs,
    providerEventIds = decodeHolders(providerEventIds),
    lastAccount = lastAccount,
)

private fun CalendarIdentityRecord.toRow(): CalendarIdentityRow = CalendarIdentityRow(
    event = event,
    occurrence = occurrence,
    publishedFingerprint = publishedFingerprint,
    publishedChangedAt = publishedChangedAt,
    publishedChangedAtEpochMs = publishedChangedAtEpochMs,
    pendingFingerprint = pendingFingerprint,
    pendingChangedAt = pendingChangedAt,
    pendingChangedAtEpochMs = pendingChangedAtEpochMs,
    pendingCanonical = pendingCanonical,
    pendingIntentId = pendingIntentId,
    lastTitle = lastTitle,
    lastCalendar = lastCalendar,
    holders = encodeHolders(holders),
    winningHolder = winningHolder,
    nameHolder = nameHolder,
    missCount = missCount,
    firstMissAt = firstMissAt,
    lastChange = lastChange,
    touchedAt = touchedAt,
    lastStartEpochMs = lastStartEpochMs,
    providerEventIds = encodeHolders(providerEventIds),
    lastAccount = lastAccount,
)

private fun CalendarIntentRow.toRecord(payload: ByteArray): CalendarIntentRecord = CalendarIntentRecord(
    id = id,
    day = day,
    segment = segment,
    bytes = payload,
    sealed = sealed,
    identityKeys = decodeIdentityKeys(identityKeys),
)

private fun CalendarIntentRecord.toRow(bodyReference: String): CalendarIntentRow = CalendarIntentRow(
    id = id,
    day = day,
    segment = segment,
    body = bodyReference,
    sealed = sealed,
    identityKeys = encodeIdentityKeys(identityKeys),
)

internal fun encodeHolders(ids: Set<Long>): String = ids.sorted().joinToString(",")

internal fun decodeHolders(raw: String): Set<Long> =
    raw.split(',').mapNotNull { it.toLongOrNull() }.toSet()

internal fun encodeIdentityKeys(keys: List<Pair<String, String>>): String =
    keys.joinToString("\n") { "${it.first}\u0000${it.second}" }

internal fun decodeIdentityKeys(raw: String): List<Pair<String, String>> {
    if (raw.isEmpty()) return emptyList()
    return raw.split('\n').map { line ->
        val parts = line.split('\u0000', limit = 2)
        parts[0] to parts.getOrElse(1) { "" }
    }
}
