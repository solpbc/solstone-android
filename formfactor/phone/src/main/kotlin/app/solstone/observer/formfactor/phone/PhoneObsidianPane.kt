// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.formfactor.phone

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
    "device_storage" -> "choose a vault in device storage. vaults kept only inside Obsidian aren't available here."
    "not_vault" -> "this folder isn't an Obsidian vault. choose your vault's folder."
    "invalid_name" -> "this vault's name can't be used. choose another vault."
    "access_lost" -> "access to this vault was lost. choose the vault again."
    "turn_off_first" -> "turn this off before choosing another vault."
    "wish_not_saved" -> "your choice couldn't be saved. try again."
    "invalid_note" -> "some changes couldn't go into your journal. they stay on this phone."
    "some_notes_unavailable" -> "some notes couldn't be read. they will be checked again."
    null -> null
    else -> "the vault couldn't be checked. try again."
}

fun obsidianSourceSubLine(state: PhoneObsidianState): String? = when {
    state.fault != null -> obsidianFaultCopy(state.fault)
    state.paused && state.enabled -> "checks continue. changes wait on this phone until you resume."
    state.enabled && state.capability == false -> "waiting for a journal update"
    state.enabled && state.warningVault != null -> "another device has synced a vault with this name."
    state.enabled && state.initialTotal > state.initialDone -> "bringing in ${state.initialDone} of ${state.initialTotal} notes"
    else -> null
}

@Composable
internal fun PhoneObsidianPane(modifier: Modifier = Modifier) {
    val binding = LocalPhoneObsidian.current
    val s = binding.state
    val now = LocalPhoneStatusNow.current.nowEpochMs()
    LaunchedEffect(Unit) { binding.onRetry() }
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.SourceDetail("obsidian")) }) {
        PaneNote("markdown notes from the vault you choose go into your journal. the vault stays unchanged.")
        if (!s.configured && s.fault == null) {
            PaneNote("choose a vault in device storage. vaults kept only inside Obsidian aren't available here.")
            TextButton(onClick = binding.onChoose) { Text("choose a folder") }
        }
        s.vault?.let { PaneFactRow("vault", it) }
        s.lastChecked?.let { PaneFactRow("last checked", formatRelative(it, now, ZoneId.systemDefault())) }
        if (s.pending > 0) PaneFactRow("waiting to sync", countNotes(s.pending.toLong(), "change", "changes"))
        if (s.initialTotal > s.initialDone) PaneFactRow("first sync", "${s.initialDone} of ${s.initialTotal} notes")
        PhoneIntakeFactRows("obsidian")
        if (s.tooLarge > 0) PaneNote("${countNotes(s.tooLarge.toLong(), "note is", "notes are")} too large to bring in.")
        if (s.unavailable > 0) PaneFactRow("not available", countNotes(s.unavailable.toLong(), "note", "notes"))
        if (s.enabled && s.capability == false) {
            PaneNote("your journal needs an update before these notes can go in. changes wait on this phone.")
        }
        if (s.warningVault != null) {
            PaneNote("a vault named \"${s.warningVault}\" was last synced from ${s.warningDevice ?: "another device"} on ${s.warningDate?.let { formatStarted(java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli(), now, ZoneId.systemDefault()) } ?: "an earlier date"}. continue from this phone too?")
            TextButton(onClick = binding.onContinue) { Text("continue") }
            TextButton(onClick = binding.onCancel) { Text("cancel") }
        }
        obsidianFaultCopy(s.fault)?.let {
            PaneNote(it)
            if (s.fault in setOf("access_lost", "not_vault", "device_storage", "invalid_name")) {
                TextButton(onClick = binding.onChoose) { Text("choose the vault again") }
            } else if (s.fault == "turn_off_first") {
                TextButton(onClick = binding.onOff) { Text("turn off Obsidian") }
            } else if (s.fault == "wish_not_saved") {
                TextButton(onClick = binding.onEnable) { Text("try again") }
            } else if (s.fault != "invalid_note") {
                TextButton(onClick = binding.onRetry) { Text("try again") }
            }
        }
        if (s.enabled) {
            if (s.paused) {
                PaneNote("checks continue. changes wait on this phone until you resume.")
                TextButton(onClick = binding.onResume) { Text("resume Obsidian") }
            } else TextButton(onClick = binding.onPause) { Text("pause Obsidian") }
            TextButton(onClick = binding.onOff) { Text("turn off Obsidian") }
        } else if (s.configured) {
            TextButton(onClick = binding.onEnable) { Text("turn on Obsidian") }
            TextButton(onClick = binding.onChoose) { Text("choose another folder") }
            PaneNote("turning this off removes nothing from your journal. changes already waiting stay on this phone.")
        }
        PaneNote("android may delay background checks. after force-stopping the app, open it to start checking again.")
    }
}

private fun countNotes(n: Long, one: String, many: String): String = "${NumberFormat.getIntegerInstance().format(n)} ${if (n == 1L) one else many}"
