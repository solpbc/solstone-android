// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.calendar

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.time.LocalDate
import app.solstone.core.calendar.CalendarFileBytes
import app.solstone.core.calendar.CalendarIdentityRecord
import app.solstone.core.calendar.CalendarIntentRecord
import app.solstone.core.calendar.CalendarNote
import app.solstone.core.calendar.HistoryKind
import app.solstone.core.calendar.MoveLookup
import app.solstone.core.calendar.NormalizedInstance
import app.solstone.core.calendar.ScanOutcome
import app.solstone.core.calendar.SeenEvent
import app.solstone.core.calendar.completeSeal
import app.solstone.core.calendar.confirmedLowerBound
import app.solstone.core.calendar.defaultLowerBound
import app.solstone.core.calendar.dropCalendar
import app.solstone.core.calendar.epochDayOf
import app.solstone.core.calendar.horizonExclusiveEnd
import app.solstone.core.calendar.nextDeepSlice
import app.solstone.core.calendar.normalizeInstance
import app.solstone.core.calendar.occurrenceKey
import app.solstone.core.calendar.reconcileScan
import app.solstone.core.calendar.reopenSteps
import app.solstone.core.calendar.routineReadWindow
import app.solstone.core.calendar.stageNotes
import app.solstone.platform.persistence.room.CalendarCalendarRow
import app.solstone.platform.persistence.room.CalendarCountRow
import app.solstone.platform.persistence.room.CalendarSettingsRow
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

data class CalendarUiRow(
    val id: Long,
    val name: String,
    val selected: Boolean,
    val count: Int?,
)

data class CalendarUiState(
    val permissionGranted: Boolean = false,
    val rows: List<CalendarUiRow> = emptyList(),
    val history: String = HistoryKind.DAYS_30.name,
    val countStatus: String? = null,
    val countValue: Int? = null,
    val confirmEnabled: Boolean = false,
    val resume: Boolean = false,
    val importCompleted: Int = 0,
    val importTotal: Int? = null,
    val importing: Boolean = false,
    val lastSuccessAt: Long? = null,
    val readFailed: Boolean = false,
    val needsAttention: Boolean = false,
    val emptyCalendars: Boolean = false,
    val spanLabel: String? = null,
    val enabled: Boolean = false,
)

private data class CountSpan(val kind: String, val lower: Long, val upper: Long, val observedAt: Long, val zone: String, val calendars: Set<Long> = emptySet()) {
    fun encode(): String = toJson(linkedMapOf("kind" to kind, "lower" to lower, "upper" to upper,
        "observed_at" to observedAt, "zone" to zone, "calendars" to calendars.sorted()))
    val label: String get() {
        val z = ZoneId.of(zone)
        return "${Instant.ofEpochMilli(lower).atZone(z).toLocalDate()} to ${Instant.ofEpochMilli(upper - 1).atZone(z).toLocalDate()}"
    }
    companion object {
        fun decode(raw: String): CountSpan? = runCatching {
            val m = parseJson(raw) as Map<*, *>
            CountSpan(m["kind"] as String, (m["lower"] as Number).toLong(), (m["upper"] as Number).toLong(),
                (m["observed_at"] as Number).toLong(), m["zone"] as String, (m["calendars"] as? List<*>)?.map { (it as Number).toLong() }?.toSet().orEmpty())
        }.getOrNull()
    }
}

/** One ordered writer. Durable counted records are separate from sendable intents. */
class CalendarRuntime(
    private val ledger: CalendarLedger,
    private val reader: CalendarReader,
    private val sealer: (CalendarFileBytes) -> Boolean,
    private val segmentExists: (CalendarIntentRecord) -> Boolean,
    private val permission: () -> Boolean,
    private val wishOn: () -> Boolean,
    private val setEnabled: (Boolean) -> Boolean,
    private val schedule: (Boolean) -> Unit,
    private val needsAttention: () -> Boolean = { false },
    private val onSealed: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val ticket = AtomicLong(0)
    @Volatile var ui: CalendarUiState = CalendarUiState()
        private set
    @Volatile var onChanged: () -> Unit = {}

    fun invalidate(): Long = ticket.incrementAndGet()
    fun currentTicket(): Long = ticket.get()

    fun refreshPreview() {
        if (permission()) refreshCalendars()
        publish()
    }

    fun setHistory(kind: HistoryKind) {
        val s = ledger.settings()
        if (s.historyKind == kind.name) return
        ledger.saveSettings(s.copy(historyKind = kind.name, countGeneration = s.countGeneration + 1,
            consentToken = s.consentToken + 1))
        publish()
    }

    fun setSelected(id: Long, selected: Boolean) {
        val rows = ledger.calendars()
        val row = rows.firstOrNull { it.providerId == id } ?: return
        if (row.selected == selected) return
        val s = ledger.settings()
        val next = rows.map { if (it.providerId == id) it.copy(selected = selected,
            confirmed = if (!selected) false else it.confirmed) else it }
        ledger.saveConfiguration(s.copy(countGeneration = s.countGeneration + 1,
            consentToken = s.consentToken + 1), next)
        if (!selected && row.confirmed) ledger.saveIdentities(dropCalendar(ledger.identities(), id))
        publish()
    }

    fun count(expectedTicket: Long = ticket.get()) {
        val s = ledger.settings()
        if (ledger.countFor(s.countGeneration)?.status == "confirmed") { publish(); return }
        val generation = s.countGeneration
        val token = s.consentToken
        val t = expectedTicket
        val ids = ledger.calendars().filter { it.selected }.map { it.providerId }.toSet()
        val fingerprint = calendarSelectionFingerprint(ids, s.historyKind)
        ledger.clearPreview(generation)
        // Nothing switched on is a choice, not a failed read: there is nothing to count yet.
        if (ids.isEmpty()) { publish(); return }
        ledger.saveCount(CalendarCountRow(generation, fingerprint, "", 0, "running", ""))
        publish()
        if (!permission() || !still(token, t)) {
            finishCount(generation, fingerprint, "", "failed")
            return
        }
        val z = zone()
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
        val kind = HistoryKind.valueOf(s.historyKind)
        val end = horizonExclusiveEnd(today, z)
        val lower = if (kind == HistoryKind.ALL) reader.earliestStart(ids) ?: run {
            finishCount(generation, fingerprint, "", "failed"); return
        } else defaultLowerBound(kind, today, z)
        val span = CountSpan(kind.name, minOf(lower, defaultLowerBound(HistoryKind.NONE_BEFORE_TODAY, today, z)), end, now, z.id, ids)
        var cursor = span.lower
        while (cursor < end) {
            if (!still(token, t)) { finishCount(generation, fingerprint, span.encode(), "cancelled"); return }
            val slice = nextDeepSlice(cursor, z, end) ?: break
            val read = reader.read(slice.first, slice.second, ids, s.fallbackNamespace) { !still(token, t) }
            if (read !is CalendarScan.Ok || !still(token, t)) {
                finishCount(generation, fingerprint, span.encode(), if (still(token, t)) "failed" else "cancelled")
                return
            }
            val seen = read.instances.mapNotNull(::normalizeInstance).map { it.toSeen(z) }
                .filter { kind == HistoryKind.ALL || it.startEpochMs >= lower }
            // Body files are synced before the small, indexed snapshot rows are committed.
            ledger.appendPreview(generation, seen)
            cursor = slice.second
        }
        finishCount(generation, fingerprint, span.encode(), if (still(token, t)) "ready" else "cancelled")
        ledger.prune()
    }

    fun confirm(expectedTicket: Long = ticket.get()): Boolean {
        val s = ledger.settings()
        if (!still(s.consentToken, expectedTicket)) return false
        val rows = ledger.calendars()
        val ids = rows.filter { it.selected }.map { it.providerId }.toSet()
        val count = ledger.countFor(s.countGeneration) ?: return false
        val span = CountSpan.decode(count.span) ?: return false
        if (!permission() || ids.isEmpty() || span.kind != s.historyKind ||
            !confirmReady(count.status, count.generation, s.countGeneration, count.selectionFingerprint,
                calendarSelectionFingerprint(ids, s.historyKind))) return false
        val kind = HistoryKind.valueOf(span.kind)
        val next = s.copy(enabled = true, confirmedGeneration = count.generation,
            confirmedHistoryKind = span.kind, confirmedLowerBoundEpochDay = if (kind == HistoryKind.ALL) null else
                epochDayOf(span.lower, ZoneId.of(span.zone)), importDone = false,
            importTotal = count.count, importCompleted = 0, importSliceCursor = null, lastReadFailed = false)
        ledger.confirmImport(count.copy(status = "confirmed", importCursor = 0), next, rows.map { it.copy(confirmed = it.selected) })
        if (!setEnabled(true)) { ledger.confirmImport(count, s, rows); publish(); return false }
        schedule(true)
        publish()
        return finishImports(next.consentToken, expectedTicket)
    }

    fun resume(expectedTicket: Long = ticket.get()): Boolean {
        val s = ledger.settings()
        if (!still(s.consentToken, expectedTicket)) return false
        if (!resumeUnchanged(s.confirmedGeneration != null, selectionMatches(), spanMatches()) || !permission()) return false
        ledger.saveSettings(s.copy(enabled = true))
        if (!setEnabled(true)) { ledger.saveSettings(s); publish(); return false }
        schedule(true)
        return runCheck(expectedTicket)
    }

    fun primary(expectedTicket: Long = ticket.get()): Boolean = if (resumeUnchanged(ledger.settings().confirmedGeneration != null,
        selectionMatches(), spanMatches())) resume(expectedTicket) else confirm(expectedTicket)

    fun abandon() {
        val s = ledger.settings()
        if (s.confirmedGeneration == null) {
            if (wishOn()) setEnabled(false)
            schedule(false)
            ledger.saveSettings(s.copy(enabled = false, countGeneration = s.countGeneration + 1,
                consentToken = s.consentToken + 1))
        } else if (!selectionMatches() || !spanMatches()) {
            ledger.saveConfiguration(s.copy(historyKind = s.confirmedHistoryKind ?: s.historyKind,
                countGeneration = s.countGeneration + 1, consentToken = s.consentToken + 1),
                ledger.calendars().map { it.copy(selected = it.confirmed) })
        }
        publish()
    }

    fun turnOff() {
        val s = ledger.settings()
        ledger.saveSettings(s.copy(enabled = false, consentToken = s.consentToken + 1))
        setEnabled(false)
        schedule(ledger.pendingImports().isNotEmpty())
        publish()
    }

    fun onPermissionResult(granted: Boolean) {
        if (!granted) {
                val s = ledger.settings()
            ledger.saveSettings(s.copy(consentToken = s.consentToken + 1))
            if (s.confirmedGeneration == null && wishOn()) setEnabled(false)
            schedule(ledger.pendingImports().isNotEmpty())
            publish()
            return
        }
        refreshCalendars()
        publish()
    }

    fun suspendChecks() { schedule(ledger.pendingImports().isNotEmpty()); publish() }

    fun onProcessStart() {
        val s = ledger.settings()
        if (!sealOutstanding(s.consentToken, ticket.get()) || !finishImports(s.consentToken, ticket.get())) recordFailure() else ledger.prune()
        schedule(shouldScheduleCalendarWork(s.confirmedGeneration != null && s.enabled, wishOn(), permission()) || ledger.pendingImports().isNotEmpty())
        publish()
    }

    fun runCheck(expectedTicket: Long = ticket.get()): Boolean {
        val s = ledger.settings()
        if (!still(s.consentToken, expectedTicket)) return false
        val token = s.consentToken
        val t = expectedTicket
        if (!sealOutstanding(token, t) || !finishImports(token, t)) return false
        if (!shouldScheduleCalendarWork(s.confirmedGeneration != null && s.enabled, wishOn(), permission())) {
            schedule(ledger.pendingImports().isNotEmpty())
            publish()
            return true
        }
        val confirmed = ledger.settings()
        val span = confirmed.confirmedGeneration?.let { ledger.countFor(it) }?.span?.let(CountSpan::decode)
            ?: return false
        val ids = ledger.calendars().filter { it.confirmed }.map { it.providerId }.toSet()
        if (ids.isEmpty()) return true
        val z = zone()
        val now = clock()
        val window = routineReadWindow(now, z, confirmed.lastSuccessAt)
        val all = mutableListOf<SeenEvent>()
        var cursor = window.first
        while (cursor < window.second) {
            val slice = nextDeepSlice(cursor, z, window.second) ?: break
            val read = reader.read(slice.first, slice.second, ids, confirmed.fallbackNamespace) { !still(token, t) }
            if (read !is CalendarScan.Ok || !still(token, t)) {
                if (still(token, t)) recordFailure()
                return false
            }
            all += read.instances.mapNotNull(::normalizeInstance).map { it.toSeen(ZoneId.of(span.zone)) }
            cursor = slice.second
        }
        val result = reconcileScan(ledger.identities(), all, ids, ScanOutcome.COMPLETE, now, z, token,
            ledger.settings().consentToken, if (span.kind == HistoryKind.ALL.name) Long.MIN_VALUE else span.lower,
            detectMissing = { it.lastStartEpochMs?.let { start -> start >= window.first } == true },
            moveLookup = { reader.lookup(it, ids, confirmed.fallbackNamespace, ZoneId.of(span.zone)) { !still(token, t) } })
        if (result.discarded || !still(token, t)) { if (still(token, t)) recordFailure(); return false }
        val next = ledger.settings().copy(lastReadFailed = false)
        if (!publishNotes(result.identities, result.notes, token, t, next)) return false
        if (!still(token, t)) return false
        ledger.saveSettings(ledger.settings().copy(lastSuccessAt = now, lastReadFailed = false))
        ledger.prune()
        publish()
        return true
    }

    private fun finishImports(token: Long, t: Long): Boolean {
        if (!sealOutstanding(token, t)) return false
        // These records were already read and explicitly approved. Publishing them needs no
        // Calendar Provider access, and pause/revocation never erases the waiting batch.
        for (queued in ledger.pendingImports()) {
            var batch = queued
            val span = CountSpan.decode(batch.span) ?: return false
            val ids = span.calendars.ifEmpty { ledger.calendars().filter { it.confirmed }.map { it.providerId }.toSet() }
            while (true) {
                if (!still(token, t)) return false
                val offset = batch.importCursor ?: break
                val page = ledger.previewPage(batch.generation, offset)
                val s = ledger.settings()
                if (page.isEmpty()) {
                    if (offset != batch.count) { recordFailure(); return false }
                    val current = batch.generation == s.confirmedGeneration
                    ledger.saveImportProgress(batch.copy(importCursor = null), s.copy(
                        importDone = if (current) true else s.importDone,
                        importCompleted = if (current) batch.count else s.importCompleted,
                        lastSuccessAt = maxOf(s.lastSuccessAt ?: Long.MIN_VALUE, span.observedAt)))
                    break
                }
                val result = reconcileScan(ledger.identities(), page, ids, ScanOutcome.COMPLETE,
                    span.observedAt, ZoneId.of(span.zone), token, s.consentToken, Long.MIN_VALUE,
                    detectMissing = { false })
                if (result.discarded || !still(token, t)) return false
                val processed = page.map { it.event to occurrenceKey(it.occurrence) }.toSet().size
                val progress = batch.copy(importCursor = offset + processed)
                val next = s.copy(importCompleted = if (batch.generation == s.confirmedGeneration) offset + processed else s.importCompleted)
                if (!publishNotes(result.identities, result.notes, token, t, next, progress)) return false
                batch = progress
                publish()
            }
        }
        ledger.prune()
        publish()
        return true
    }

    private fun publishNotes(identities: List<CalendarIdentityRecord>, notes: List<CalendarNote>,
        token: Long, t: Long, next: CalendarSettingsRow, count: CalendarCountRow? = null): Boolean {
        if (!still(token, t)) return false
        val staged = stageNotes(identities, ledger.intents(), notes)
        if (!still(token, t)) return false
        if (!ledger.stage(staged.identities, staged.intents, next, count) { still(token, t) }) return false
        if (!sealOutstanding(token, t)) { if (still(token, t)) recordFailure(); return false }
        return true
    }

    private fun sealOutstanding(token: Long, t: Long): Boolean {
        var identities = ledger.identities()
        var intents = ledger.intents()
        for (step in reopenSteps(intents, segmentExists)) {
            if (!still(token, t)) return false
            if (step.seal) {
                val file = CalendarFileBytes("calendar_${step.intent.id}.jsonl", step.intent.bytes,
                    app.solstone.core.calendar.CALENDAR_MEDIA_TYPE, step.intent.day, step.intent.segment,
                    app.solstone.core.sources.CALENDAR_STREAM)
                if (!sealer(file) || !segmentExists(step.intent)) return false
            }
            val completed = completeSeal(identities, intents, step.intent.id)
            identities = completed.identities
            intents = completed.intents
            if (!ledger.stage(identities, intents, canCommit = { still(token, t) })) return false
            if (step.seal) onSealed()
        }
        return true
    }

    fun recordFailure() {
        val s = ledger.settings()
        ledger.countFor(s.countGeneration)?.takeIf { it.status == "running" }?.let { ledger.saveCount(it.copy(status = "failed")) }
        ledger.saveSettings(s.copy(lastReadFailed = true))
        publish()
    }

    private fun refreshCalendars() {
        val listed = reader.readCalendars() ?: run { recordFailure(); return }
        val existing = ledger.calendars().associateBy { it.providerId }
        val added = listed.any { it.id !in existing }
        // A calendar the app has not seen before starts off; the owner switches on the ones they want.
        // A calendar already stored keeps the owner's earlier choice.
        val merged = listed.map { c -> existing[c.id]?.copy(displayName = c.name,
            ownerAccount = c.ownerAccount, accountType = c.accountType, visible = c.visible)
            ?: CalendarCalendarRow(c.id, c.name, c.ownerAccount, c.accountType, false, false, c.visible) } +
            existing.values.filter { prior -> listed.none { it.id == prior.providerId } }
        val s = ledger.settings()
        ledger.saveConfiguration(if (added) s.copy(countGeneration = s.countGeneration + 1,
            consentToken = s.consentToken + 1) else s, merged)
    }

    private fun finishCount(generation: Long, fingerprint: String, span: String, status: String) {
        val current = ledger.settings()
        ledger.saveCount(CalendarCountRow(generation, fingerprint, span, ledger.previewCount(generation),
            if (current.countGeneration == generation) status else "cancelled", encodePerCalendar(ledger.previewCounts(generation))))
        publish()
    }

    private fun publish() {
        val s = ledger.settings()
        val rows = ledger.calendars()
        val count = ledger.countFor(s.countGeneration)
        val span = count?.span?.let(CountSpan::decode)
        val per = decodePerCalendar(count?.perCalendar.orEmpty())
        val ready = confirmReady(count?.status, count?.generation, s.countGeneration,
            count?.selectionFingerprint, calendarSelectionFingerprint(rows.filter { it.selected }.map { it.providerId }.toSet(), s.historyKind)) &&
            span?.kind == s.historyKind && rows.any { it.selected }
        val resume = resumeUnchanged(s.confirmedGeneration != null, selectionMatches(), spanMatches())
        ui = CalendarUiState(permission(), rows.map { CalendarUiRow(it.providerId, it.displayName, it.selected, per[it.providerId] ?: if (ready) 0 else null) },
            s.historyKind, count?.status, if (ready) count?.count else null, permission() && (ready || resume), resume,
            s.importCompleted, s.importTotal, s.enabled && !s.importDone, s.lastSuccessAt, s.lastReadFailed,
            needsAttention(), permission() && rows.isEmpty(), if (ready || resume) span?.label else null, s.enabled && wishOn())
        onChanged()
    }

    private fun selectionMatches(): Boolean = ledger.calendars().all { it.selected == it.confirmed }
    private fun spanMatches(): Boolean = ledger.settings().let { it.historyKind == it.confirmedHistoryKind }
    private fun still(token: Long, t: Long): Boolean = ticket.get() == t &&
        ledger.settings().consentToken == token && !Thread.currentThread().isInterrupted
}

private fun NormalizedInstance.toSeen(consentZone: ZoneId): SeenEvent = SeenEvent(event, occurrence,
    calendarId, calendarName, if (note.allDay == true) LocalDate.parse(note.start).atStartOfDay(consentZone).toInstant().toEpochMilli()
        else startEpochMs, note, providerEventIds)
