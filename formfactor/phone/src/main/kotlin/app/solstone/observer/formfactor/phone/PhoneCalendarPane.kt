// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

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

private val historyChoices = listOf(
    "NONE_BEFORE_TODAY" to "none before today",
    "DAYS_30" to "last 30 days",
    "MONTHS_6" to "six months",
    "YEARS_1" to "one year",
    "ALL" to "all on this phone",
)

@Composable
internal fun PhoneCalendarPane(modifier: Modifier = Modifier) {
    val binding = LocalPhoneCalendar.current
    val state = binding.state
    LaunchedEffect(Unit) {
        if (!state.permissionGranted) binding.onRequestPermission()
        binding.onOpened()
    }
    DisposableEffect(Unit) {
        onDispose { binding.onAbandon() }
    }
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.CalendarSetup) }) {
        PaneNote("calendar brings in events this phone can read, including notes, places, invitees, holidays, and birthdays. they go to your journal.")
        PaneNote("while calendar is on, the solstone app checks it in the background. android may delay those checks.")
        if (!state.permissionGranted) {
            PaneNote("allow calendar access to preview your events. nothing is added until you confirm.")
            TextButton(onClick = binding.onRequestPermission) { Text("allow calendar") }
            TextButton(onClick = binding.onOpenSettings) { Text("open app settings") }
        }
        if (state.emptyCalendars) {
            PaneNote("this phone has no calendars the solstone app can read.")
        }
        PaneFactRow("calendar", if (!state.permissionGranted && state.resume) "needs permission"
            else if (state.enabled) "on" else if (state.resume) "paused" else "off")
        PaneNote("history, with one year ahead")
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
            PaneNote("all is only the history this phone's calendar has stored, not everything in the account.")
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
        if (state.countStatus == "running") PaneNote("counting events…")
        if (state.countStatus == "failed") {
            PaneNote("the preview couldn't finish. check calendar access and available space, then try again.")
            TextButton(onClick = binding.onRetry) { Text("try again") }
        }
        state.spanLabel?.let { PaneFactRow("dates in this preview", it) }
        if (state.countValue != null) {
            PaneFactRow(label = "events in this check", value = state.countValue.toString())
        }
        if (state.confirmEnabled) {
            TextButton(onClick = binding.onPrimary) {
                Text(if (state.enabled) "check now" else if (state.resume) "resume calendar" else "add these to your journal")
            }
        }
        if (state.enabled) TextButton(onClick = binding.onTurnOff) { Text("pause calendar") }
        PaneNote("pausing or switching a calendar off stops new events. what's already in your journal stays.")
        if (state.importing && state.importTotal != null) {
            PaneNote("bringing in ${state.importCompleted} of ${state.importTotal}")
        }
        val checkedAt = state.lastSuccessAt?.let { epoch ->
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epoch))
        }
        PaneNote(if (checkedAt == null) "no complete check yet" else "last check $checkedAt")
        if (state.readFailed) {
            PaneNote("the last check couldn't finish. check calendar access and available space, then try again.")
            TextButton(onClick = binding.onRetry) { Text("try again") }
        }
        if (state.needsAttention) {
            PaneNote("this note needs attention before it can go to your journal.")
        }
        if (binding.heldForJournal) {
            PaneNote("your journal needs an update before these notes can go in.")
        }
    }
}
