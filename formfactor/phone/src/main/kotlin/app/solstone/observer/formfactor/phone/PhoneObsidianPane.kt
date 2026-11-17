// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.formfactor.phone

import app.solstone.core.i18n.phoneText

import app.solstone.core.model.SourceState
import androidx.compose.foundation.layout.padding

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import java.text.NumberFormat
import java.time.ZoneId

data class PhoneObsidianState(
    val configured: Boolean = false, val enabled: Boolean = false, val paused: Boolean = false,
    val settingUp: Boolean = false, val vault: String? = null, val lastChecked: Long? = null,
    val pending: Int = 0, val initialDone: Int = 0, val initialTotal: Int = 0,
    val tooLarge: Int = 0, val unavailable: Int = 0, val fault: String? = null,
    val capability: Boolean? = null, val warningVault: String? = null,
    val warningDevice: String? = null, val warningDate: String? = null,
)
data class PhoneObsidianBinding(
    val state: PhoneObsidianState = PhoneObsidianState(),
    val onChoose: () -> Unit = {}, val onEnable: () -> Unit = {}, val onOff: () -> Unit = {},
    val onPause: () -> Unit = {}, val onResume: () -> Unit = {}, val onRetry: () -> Unit = {},
    val onContinue: () -> Unit = {}, val onCancel: () -> Unit = {},
)
val LocalPhoneObsidian = staticCompositionLocalOf { PhoneObsidianBinding() }

fun obsidianFaultCopy(fault: String?): String? = when (fault) {
    "device_storage" -> phoneText("android.obsidian.choosevaultdevicestorage")
    "not_vault" -> phoneText("android.obsidian.folderisntobsidian")
    "invalid_name" -> phoneText("android.obsidian.vaultsnamecan")
    "access_lost" -> phoneText("android.obsidian.accessvaultlostchoose")
    "turn_off_first" -> phoneText("android.obsidian.turnoffbeforechoosing")
    "wish_not_saved" -> phoneText("android.obsidian.choicecouldntsaved")
    "invalid_note" -> phoneText("android.obsidian.somechangescouldnt")
    "some_notes_unavailable" -> phoneText("android.obsidian.somenotescouldnt")
    null -> null
    else -> phoneText("android.obsidian.vaultcouldntchecked")
}

fun obsidianSourceSubLine(state: PhoneObsidianState): String? = when {
    state.fault != null -> obsidianFaultCopy(state.fault)
    state.paused && state.enabled -> phoneText("android.obsidian.checkscontinuechangeswait")
    state.enabled && state.capability == false -> phoneText("android.obsidian.waitingjournalupdate")
    state.enabled && state.warningVault != null -> phoneText("android.obsidian.anotherdevicehassynced")
    state.enabled && state.initialTotal > state.initialDone -> phoneText("android.obsidian.bringingnotes", "done" to (state.initialDone), "total" to (state.initialTotal))
    else -> null
}

@Composable
internal fun PhoneObsidianPane(modifier: Modifier = Modifier, verdict: SourceState? = null) {
    val binding = LocalPhoneObsidian.current
    val s = binding.state
    val now = LocalPhoneStatusNow.current.nowEpochMs()
    LaunchedEffect(Unit) { binding.onRetry() }
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.SourceDetail("obsidian")) }) {
        // The verdict leads, as on every source page (mobile-shell § 3).
        verdict?.let { SourceVerdict(it, Modifier.padding(top = ShellMetrics.sectionGap)) }
        PaneNote(phoneText("android.obsidian.markdownnotesvaultchoose"))
        if (!s.configured && s.fault == null) {
            PaneNote(phoneText("android.obsidian.choosevaultdevicestorage"))
            TextButton(onClick = binding.onChoose) { Text(phoneText("android.obsidian.choosefolder")) }
        }
        s.vault?.let { PaneFactRow(phoneText("android.obsidian.vault"), it) }
        s.lastChecked?.let { PaneFactRow(phoneText("android.obsidian.lastchecked"), formatRelative(it, now, ZoneId.systemDefault())) }
        if (s.pending > 0) PaneFactRow(phoneText("android.status.waiting.sync"), phoneText("android.obsidian.changes", "count" to (s.pending)))
        if (s.initialTotal > s.initialDone) PaneFactRow(phoneText("android.obsidian.firstsync"), phoneText("android.obsidian.sinitialdonesinitialtotal" , "initialdone" to (s.initialDone), "initialtotal" to (s.initialTotal)))
        PhoneIntakeFactRows("obsidian")
        if (s.tooLarge > 0) PaneNote(phoneText("android.obsidian.toolarge", "count" to (s.tooLarge)))
        if (s.unavailable > 0) PaneFactRow(phoneText("android.obsidian.notavailable"), phoneText("android.obsidian.notes", "count" to (s.unavailable)))
        if (s.enabled && s.capability == false) {
            PaneNote(phoneText("android.obsidian.journalneedsupdatebefore"))
        }
        if (s.warningVault != null) {
            PaneNote(phoneText("android.obsidian.warningvault" , "warningvault" to (s.warningVault), "warningdevice" to (s.warningDevice ?: phoneText("android.obsidian.anotherdevice")), "date" to (s.warningDate?.let { formatStarted(java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli(), now, ZoneId.systemDefault()) } ?: phoneText("android.obsidian.earlierdate"))))
            TextButton(onClick = binding.onContinue) { Text(phoneText("android.obsidian.continue")) }
            TextButton(onClick = binding.onCancel) { Text(phoneText("android.phone.cancel")) }
        }
        obsidianFaultCopy(s.fault)?.let {
            PaneNote(it)
            if (s.fault in setOf("access_lost", "not_vault", "device_storage", "invalid_name")) {
                TextButton(onClick = binding.onChoose) { Text(phoneText("android.obsidian.choosevaultagain")) }
            } else if (s.fault == "turn_off_first") {
                TextButton(onClick = binding.onOff) { Text(phoneText("android.obsidian.turnoffobsidian")) }
            } else if (s.fault == "wish_not_saved") {
                TextButton(onClick = binding.onEnable) { Text(phoneText("android.migration.try.again")) }
            } else if (s.fault != "invalid_note") {
                TextButton(onClick = binding.onRetry) { Text(phoneText("android.migration.try.again")) }
            }
        }
        if (s.enabled) {
            if (s.paused) {
                PaneNote(phoneText("android.obsidian.checkscontinuechangeswait"))
                TextButton(onClick = binding.onResume) { Text(phoneText("android.obsidian.resumeobsidian")) }
            } else TextButton(onClick = binding.onPause) { Text(phoneText("android.obsidian.pauseobsidian")) }
            TextButton(onClick = binding.onOff) { Text(phoneText("android.obsidian.turnoffobsidian")) }
        } else if (s.configured) {
            TextButton(onClick = binding.onEnable) { Text(phoneText("android.obsidian.turnobsidian")) }
            TextButton(onClick = binding.onChoose) { Text(phoneText("android.obsidian.chooseanotherfolder")) }
            PaneNote(phoneText("android.obsidian.turningoffremovesnothing"))
        }
        PaneNote(phoneText("android.obsidian.androidmaydelaybackground"))
    }
}
