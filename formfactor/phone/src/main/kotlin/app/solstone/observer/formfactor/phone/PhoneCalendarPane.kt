// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

import app.solstone.core.model.SourceState

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PhoneCalendarRow(
    val id: Long,
    val name: String,
    val selected: Boolean,
    val count: Int?,
)

data class PhoneCalendarPaneState(
    val permissionGranted: Boolean = false,
    val rows: List<PhoneCalendarRow> = emptyList(),
    val history: String = "DAYS_30",
    val countValue: Int? = null,
    val confirmEnabled: Boolean = false,
    val importing: Boolean = false,
    val importCompleted: Int = 0,
    val importTotal: Int? = null,
    val lastSuccessAt: Long? = null,
    val readFailed: Boolean = false,
    val needsAttention: Boolean = false,
    val emptyCalendars: Boolean = false,
    val countStatus: String? = null,
    val spanLabel: String? = null,
    val resume: Boolean = false,
    val enabled: Boolean = false,
)

data class PhoneCalendarBinding(
    val state: PhoneCalendarPaneState = PhoneCalendarPaneState(),
    val heldForJournal: Boolean = false,
    val onHistory: (String) -> Unit = {},
    val onSelected: (Long, Boolean) -> Unit = { _, _ -> },
    val onPrimary: () -> Unit = {},
    val onAbandon: () -> Unit = {},
    val onRequestPermission: () -> Unit = {},
    val onOpened: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onTurnOff: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

val LocalPhoneCalendar = staticCompositionLocalOf { PhoneCalendarBinding() }

private val historyChoices get() = listOf(
    "NONE_BEFORE_TODAY" to phoneText("android.calendar.none.before.today"),
    "DAYS_30" to phoneText("android.calendar.last.n30.days"),
    "MONTHS_6" to phoneText("android.calendar.six.months"),
    "YEARS_1" to phoneText("android.calendar.one.year"),
    "ALL" to phoneText("android.calendar.all.phone"),
)

@Composable
internal fun PhoneCalendarPane(modifier: Modifier = Modifier, verdict: SourceState? = null) {
    val binding = LocalPhoneCalendar.current
    val state = binding.state
    val sharedAtOpen = otherDevices("calendar", verdict).isNotEmpty()
    LaunchedEffect(Unit) {
        if (!sharedAtOpen && !state.permissionGranted) binding.onRequestPermission()
        binding.onOpened()
    }
    DisposableEffect(Unit) {
        onDispose { binding.onAbandon() }
    }
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.CalendarSetup) }) {
        SharedSourceCard("calendar", verdict)
        // The verdict leads, as on every source page (mobile-shell § 3).
        verdict?.let { SourceVerdict(it, Modifier.padding(top = ShellMetrics.sectionGap)) }
        PaneNote(phoneText("android.calendar.calendar.brings.events.phone.can"))
        PaneNote(phoneText("android.calendar.while.calendar.solstone.app.checks"))
        if (!state.permissionGranted) {
            PaneNote(phoneText("android.calendar.allow.calendar.access.preview.events"))
            TextButton(onClick = binding.onRequestPermission) { Text(phoneText("android.calendar.allow.calendar")) }
            TextButton(onClick = binding.onOpenSettings) { Text(phoneText("android.calendar.open.app.settings")) }
        }
        if (state.emptyCalendars) {
            PaneNote(phoneText("android.calendar.phone.has.no.calendars.solstone"))
        }
        // The verdict above says the state; this row is kept only where no verdict is drawn.
        if (verdict == null) {
            PaneFactRow(phoneText("android.phone.calendar"), if (!state.permissionGranted && state.resume) phoneText("android.calendar.needs.permission")
                else if (state.enabled) phoneText("android.push.on") else if (state.resume) phoneText("android.source.paused") else phoneText("android.push.off"))
        }
        PhoneIntakeFactRows("calendar")
        SharedSourceFacts("calendar", verdict)
        PaneNote(phoneText("android.calendar.history.with.one.year.ahead"))
        PaneCard {
            historyChoices.forEach { (kind, label) ->
                Row(Modifier.fillMaxWidth().selectable(selected = state.history == kind,
                    enabled = state.permissionGranted, role = Role.RadioButton,
                    onClick = { binding.onHistory(kind) }).padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.history == kind, onClick = null, enabled = state.permissionGranted)
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
        }
        if (state.history == "ALL") {
            PaneNote(phoneText("android.calendar.all.only.history.phone.s"))
        }
        if (state.rows.isNotEmpty()) {
            PaneCard {
                state.rows.forEach { row ->
                    PaneSwitchRow(
                        label = row.name,
                        checked = row.selected,
                        onCheckedChange = { binding.onSelected(row.id, it) },
                        subLine = row.count?.toString(),
                    )
                }
            }
        }
        if (state.countStatus == "running") PaneNote(phoneText("android.calendar.counting.events"))
        if (state.countStatus == "failed") {
            PaneNote(phoneText("android.calendar.preview.couldn.t.finish.check"))
            TextButton(onClick = binding.onRetry) { Text(phoneText("android.migration.try.again")) }
        }
        state.spanLabel?.let { PaneFactRow(phoneText("android.calendar.dates.preview"), it) }
        if (state.countValue != null) {
            PaneFactRow(label = phoneText("android.calendar.events.check"), value = state.countValue.toString())
        }
        if (state.confirmEnabled) {
            TextButton(onClick = binding.onPrimary) {
                Text(if (state.enabled) phoneText("android.calendar.check.now") else if (state.resume) phoneText("android.calendar.resume.calendar") else phoneText("android.calendar.add.these.journal"))
            }
        }
        if (state.enabled) TextButton(onClick = binding.onTurnOff) { Text(phoneText("android.calendar.pause.calendar")) }
        PaneNote(phoneText("android.calendar.what.s.already.journal.stays"))
        if (state.importing && state.importTotal != null) {
            PaneNote(phoneText("android.calendar.bringing.state.importcompleted.state.importtotal", "done" to (state.importCompleted), "total" to (state.importTotal)))
        }
        val checkedAt = state.lastSuccessAt?.let { epoch ->
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch))
        }
        PaneNote(if (checkedAt == null) phoneText("android.calendar.no.complete.check.yet") else phoneText("android.calendar.last.check.checkedat" , "checkedat" to (checkedAt)))
        if (state.readFailed) {
            PaneNote(phoneText("android.calendar.last.check.couldn.t.finish"))
            TextButton(onClick = binding.onRetry) { Text(phoneText("android.migration.try.again")) }
        }
        if (state.needsAttention) {
            PaneNote(phoneText("android.calendar.note.needs.attention.before.can"))
        }
        if (binding.heldForJournal) {
            PaneNote(phoneText("android.calendar.journal.needs.update.before.these"))
        }
    }
}
