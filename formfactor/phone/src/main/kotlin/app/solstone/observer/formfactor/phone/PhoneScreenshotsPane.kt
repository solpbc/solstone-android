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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.solstone.core.screenshots.HistoryChoice
import app.solstone.core.screenshots.confirmLabel
import java.time.ZoneId

/** Locked owner copy. The id is the row in the screenshots copy file. */
private val A1 get() = phoneText("android.screenshots.whilesolstoneapptakes")
private val A2 get() = phoneText("android.screenshots.solstoneappreadsonly")
private val A3 get() = phoneText("android.screenshots.choosefolder")
private val A4 get() = phoneText("android.screenshots.allowaccess")
private val A6 get() = phoneText("android.screenshots.solstoneapplooksnew")
private val A6B get() = phoneText("android.screenshots.solstoneapplooksnew2")
private val A8_ACTION get() = phoneText("android.screenshots.choosefolder2")
private val A9 get() = phoneText("android.screenshots.screenshottoobigcome")
private val A10 get() = phoneText("android.screenshots.screenshotcouldntread")
private val A11 get() = phoneText("android.screenshots.screenshotcouldntcome")
private val S7 get() = phoneText("android.screenshots.beforetoday")
private val S9 get() = phoneText("android.screenshots.llseehowmany")
private val S11 get() = phoneText("android.screenshots.choosehowfarback")
private val S12 get() = phoneText("android.screenshots.couldntcountscreenshots")
private val S14 get() = phoneText("android.screenshots.screenshotalreadyjournalstays")
private val S15 get() = phoneText("android.screenshots.whatsalreadyjournal")
private val S16 get() = phoneText("android.screenshots.pausedscreenshotstakewhile")
private val S17 get() = phoneText("android.screenshots.screenshotsalreadyjournalaren")
private val S21 get() = phoneText("android.screenshots.waitingjournalupdatescreenshots")
private val S23 get() = phoneText("android.screenshots.onlywhatsolstoneapp")

private val historyChoices get() = listOf(
    HistoryChoice.LAST_30_DAYS to phoneText("android.screenshots.last30days"),
    HistoryChoice.LAST_6_MONTHS to phoneText("android.screenshots.last6months"),
    HistoryChoice.LAST_YEAR to phoneText("android.screenshots.lastyear"),
    HistoryChoice.ALL to phoneText("android.screenshots.all"),
    HistoryChoice.NOTHING_BEFORE_TODAY to phoneText("android.screenshots.nothingbeforetoday"),
)

data class PhoneScreenshotsState(
    val accessGranted: Boolean = false,
    val accessLost: Boolean = false,
    val folderName: String = phoneText("android.screenshots.foldername"),
    val folderPath: String = phoneText("android.screenshots.foldername2"),
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val sourcePaused: Boolean = false,
    val confirmed: Boolean = false,
    val reconfirm: Boolean = false,
    /** Never turned on yet: the tile's state word is `ready to set up`, not `off`. */
    val firstSetUp: Boolean = true,
    val history: HistoryChoice = HistoryChoice.LAST_30_DAYS,
    val counting: Boolean = false,
    val countFailed: Boolean = false,
    val previewCount: Int? = null,
    val backfillDone: Int = 0,
    val backfillTotal: Int = 0,
    val lastChecked: Long? = null,
    val waiting: Int = 0,
    val capability: Boolean? = null,
    val refusedSize: Int = 0,
    val refusedUnread: Int = 0,
    val refusedJournal: Int = 0,
)

data class PhoneScreenshotsBinding(
    val state: PhoneScreenshotsState = PhoneScreenshotsState(),
    val onChoose: () -> Unit = {},
    val onHistory: (HistoryChoice) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onOff: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
)

val LocalPhoneScreenshots = staticCompositionLocalOf { PhoneScreenshotsBinding() }

/** Which locked line the tile shows, by its id in the screenshots copy file. */
enum class ScreenshotsTileLine { A7, S16, S3, S21, S5, S2, S11 }

/**
 * Unpaired, nothing goes to a journal, so S3 says where screenshots wait before any progress or
 * journal-version line can.
 */
fun screenshotsTileLine(state: PhoneScreenshotsState, paired: Boolean): ScreenshotsTileLine? = when {
    state.accessLost -> ScreenshotsTileLine.A7
    state.paused && state.enabled -> ScreenshotsTileLine.S16
    state.enabled && !paired -> ScreenshotsTileLine.S3
    state.enabled && state.capability == false -> ScreenshotsTileLine.S21
    state.enabled && state.backfillTotal > 0 && state.backfillDone < state.backfillTotal -> ScreenshotsTileLine.S5
    state.enabled -> ScreenshotsTileLine.S2
    // S11 is the first set-up's line, and a new pairing starts the first set-up again. Once turned
    // off or given a new folder, the tile is `off` and takes the shared off line; the page shows the
    // chooser again.
    state.accessGranted && !state.confirmed && state.firstSetUp -> ScreenshotsTileLine.S11
    else -> null
}

fun screenshotsSourceSubLine(state: PhoneScreenshotsState, paired: Boolean): String? =
    when (screenshotsTileLine(state, paired)) {
        ScreenshotsTileLine.A7 -> phoneText("android.screenshots.cantreadfolder" , "foldername" to (state.folderName))
        ScreenshotsTileLine.S16 -> S16
        ScreenshotsTileLine.S3 -> phoneText("android.screenshots.screenshotstakewaitdevice")
        ScreenshotsTileLine.S21 -> S21
        ScreenshotsTileLine.S5 -> phoneText("android.screenshots.backfillinyourjournal", "done" to state.backfillDone, "total" to state.backfillTotal)
        ScreenshotsTileLine.S2 -> phoneText("android.screenshots.addsscreenshotstakejournal")
        ScreenshotsTileLine.S11 -> S11
        null -> null
    }

@Composable
internal fun PhoneScreenshotsPane(modifier: Modifier = Modifier, verdict: SourceState? = null) {
    val binding = LocalPhoneScreenshots.current
    val s = binding.state
    val now = LocalPhoneStatusNow.current.nowEpochMs()
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.SourceDetail("screenshots")) }) {
        // The verdict leads, as on every source page (mobile-shell § 3).
        verdict?.let { SourceVerdict(it, Modifier.padding(top = ShellMetrics.sectionGap)) }
        PaneNote(A1)
        // A lost grant has its own reason and button (A8); the first-time setup lines would repeat it.
        if (!s.accessGranted && !s.accessLost) {
            PaneNote(A2)
            TextButton(onClick = binding.onChoose) { Text(A3) }
            PaneNote(A4)
        }
        if (s.accessLost) {
            PaneNote(phoneText("android.screenshots.cantreadanymore" , "foldername" to (s.folderName)))
            TextButton(onClick = binding.onChoose) { Text(A8_ACTION) }
        }
        if (s.accessGranted && !s.confirmed) {
            PaneNote(S7)
            PaneCard {
                historyChoices.forEach { (choice, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = s.history == choice,
                            role = Role.RadioButton,
                            onClick = { binding.onHistory(choice) },
                        ).padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.history == choice, onClick = null)
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
            }
            PaneNote(S9)
            if (s.reconfirm) PaneNote(S17)
            val label = if (s.counting || s.previewCount == null) phoneText("android.shelf.checking") else confirmLabel(s.history, s.previewCount)
            TextButton(onClick = binding.onConfirm, enabled = !s.counting && s.previewCount != null) { Text(label) }
        }
        // Before the source is on no background check runs, so the page names only opening the app.
        if (!s.confirmed) PaneNote(A6)
        if (s.confirmed) {
            // The switch is the owner's on or off; a pause is its own control, as `off` and `paused` differ.
            PaneSwitchRow(
                label = phoneText("android.screenshots.screenshots"),
                checked = s.enabled,
                onCheckedChange = { on -> if (!on) binding.onOff() },
            )
            // While the source is on, the background check runs too (proven on hardware), so A6b.
            PaneNote(A6B)
            if (s.paused) PaneNote(S16)
            // Resume is offered only for this source's own pause: under a pause of the whole app it
            // could not bring screenshots back on its own.
            if (s.enabled && s.sourcePaused) {
                TextButton(onClick = binding.onResume) { Text(phoneText("android.screenshots.resume")) }
            } else if (s.enabled && !s.paused && !s.accessLost) {
                TextButton(onClick = binding.onPause) { Text(phoneText("android.screenshots.pause")) }
            }
            if (s.backfillTotal > 0 && s.backfillDone < s.backfillTotal) {
                PaneNote(phoneText("android.screenshots.backfillscreenshots", "done" to s.backfillDone, "total" to s.backfillTotal))
            }
            if (s.enabled && s.capability == false) PaneNote(S21)
        }
        if (s.accessGranted && s.countFailed) {
            PaneNote(S12)
            TextButton(onClick = binding.onRetry) { Text(phoneText("android.migration.try.again")) }
        }
        if (s.refusedSize > 0) PaneNote(A9)
        if (s.refusedUnread > 0) {
            PaneNote(A10)
            if (!s.countFailed) {
                TextButton(onClick = binding.onRetry) { Text(phoneText("android.migration.try.again")) }
            }
        }
        if (s.refusedJournal > 0) PaneNote(A11)
        if (s.waiting > 0) PaneFactRow(phoneText("android.screenshots.waitinggojournal"), s.waiting.toString())
        s.lastChecked?.let { PaneFactRow(phoneText("android.obsidian.lastchecked"), formatRelative(it, now, ZoneId.systemDefault())) }
        if (s.accessGranted) PaneFactRow(phoneText("android.screenshots.folder"), s.folderPath)
        PaneNote(S14)
        PaneNote(S15)
        PaneNote(S23)
        PhoneIntakeFactRows("screenshots")
    }
}
