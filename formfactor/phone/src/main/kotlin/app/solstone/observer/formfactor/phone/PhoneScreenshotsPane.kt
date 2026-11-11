// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

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
private const val A1 = "while this is on, the solstone app takes in the screenshots you take, and your journal keeps each one at the minute you took it."
private const val A2 = "the solstone app reads only the folder you choose."
private const val A3 = "choose the Screenshots folder"
private const val A4 = "on the next screen, use this folder and allow access."
private const val A6 = "the solstone app looks for new screenshots when you open it, so one can take a while to reach your journal."
private const val A8_ACTION = "choose the folder"
private const val A9 = "this screenshot is too big to come in, so it stays in the folder."
private const val A10 = "this screenshot couldn't be read."
private const val A11 = "this screenshot couldn't come in, so it stays in the folder."
private const val S7 = "from before today"
private const val S9 = "you'll see how many screenshots there are before any come in."
private const val S11 = "choose how far back to start, then bring them in."
private const val S12 = "couldn't count your screenshots."
private const val S14 = "a screenshot already in your journal stays there if you delete it from the folder."
private const val S15 = "what's already in your journal stays when you pause or turn this off."
private const val S16 = "you paused this. screenshots you take while it's paused don't come in."
private const val S17 = "screenshots already in your journal aren't brought in again."
private const val S21 = "waiting for a journal update. your screenshots wait on this device."
private const val S23 = "only what the solstone app can tell is a screenshot comes in."

private val historyChoices = listOf(
    HistoryChoice.LAST_30_DAYS to "the last 30 days",
    HistoryChoice.LAST_6_MONTHS to "the last 6 months",
    HistoryChoice.LAST_YEAR to "the last year",
    HistoryChoice.ALL to "all of it",
    HistoryChoice.NOTHING_BEFORE_TODAY to "nothing from before today",
)

data class PhoneScreenshotsState(
    val accessGranted: Boolean = false,
    val accessLost: Boolean = false,
    val folderName: String = "Screenshots",
    val folderPath: String = "Screenshots",
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
    // S11 is the first set-up's line. Once turned off, given a new folder or paired to a different
    // journal, the tile is `off` and takes the shared off line; the page shows the chooser again.
    state.accessGranted && !state.confirmed && state.firstSetUp -> ScreenshotsTileLine.S11
    else -> null
}

fun screenshotsSourceSubLine(state: PhoneScreenshotsState, paired: Boolean): String? =
    when (screenshotsTileLine(state, paired)) {
        ScreenshotsTileLine.A7 -> "can't read the ${state.folderName} folder."
        ScreenshotsTileLine.S16 -> S16
        ScreenshotsTileLine.S3 -> "the screenshots you take wait on this device until you connect a journal."
        ScreenshotsTileLine.S21 -> S21
        ScreenshotsTileLine.S5 -> "${state.backfillDone} of ${state.backfillTotal} in your journal"
        ScreenshotsTileLine.S2 -> "adds the screenshots you take to your journal while this is on."
        ScreenshotsTileLine.S11 -> S11
        null -> null
    }

@Composable
internal fun PhoneScreenshotsPane(modifier: Modifier = Modifier) {
    val binding = LocalPhoneScreenshots.current
    val s = binding.state
    val now = LocalPhoneStatusNow.current.nowEpochMs()
    PhonePaneScaffold(modifier.semantics { paneTitle = spokenPaneTitle(PhoneRoute.SourceDetail("screenshots")) }) {
        PaneNote(A1)
        // A lost grant has its own reason and button (A8); the first-time setup lines would repeat it.
        if (!s.accessGranted && !s.accessLost) {
            PaneNote(A2)
            TextButton(onClick = binding.onChoose) { Text(A3) }
            PaneNote(A4)
        }
        if (s.accessLost) {
            PaneNote("the solstone app can't read the ${s.folderName} folder anymore. it may have moved. choose it again to keep screenshots coming in.")
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
            if (s.countFailed) {
                PaneNote(S12)
                TextButton(onClick = binding.onRetry) { Text("try again") }
            }
            val label = if (s.counting || s.previewCount == null) "checking…" else confirmLabel(s.history, s.previewCount)
            TextButton(onClick = binding.onConfirm, enabled = !s.counting && s.previewCount != null) { Text(label) }
        }
        // A6 is said in every state, so the owner reads when screenshots arrive before turning this on.
        if (!s.confirmed) PaneNote(A6)
        if (s.confirmed) {
            // The switch is the owner's on or off; a pause is its own control, as `off` and `paused` differ.
            PaneSwitchRow(
                label = "screenshots",
                checked = s.enabled,
                onCheckedChange = { on -> if (!on) binding.onOff() },
            )
            PaneNote(A6)
            if (s.paused) PaneNote(S16)
            // Resume is offered only for this source's own pause: under a pause of the whole app it
            // could not bring screenshots back on its own.
            if (s.enabled && s.sourcePaused) {
                TextButton(onClick = binding.onResume) { Text("resume") }
            } else if (s.enabled && !s.paused && !s.accessLost) {
                TextButton(onClick = binding.onPause) { Text("pause") }
            }
            if (s.backfillTotal > 0 && s.backfillDone < s.backfillTotal) {
                PaneNote("${s.backfillDone} of ${s.backfillTotal} screenshots in your journal")
            }
            if (s.enabled && s.capability == false) PaneNote(S21)
        }
        if (s.refusedSize > 0) PaneNote(A9)
        if (s.refusedUnread > 0) {
            PaneNote(A10)
            TextButton(onClick = binding.onRetry) { Text("try again") }
        }
        if (s.refusedJournal > 0) PaneNote(A11)
        if (s.waiting > 0) PaneFactRow("waiting to go to your journal", s.waiting.toString())
        s.lastChecked?.let { PaneFactRow("last checked", formatRelative(it, now, ZoneId.systemDefault())) }
        if (s.accessGranted) PaneFactRow("folder", s.folderPath)
        PaneNote(S14)
        PaneNote(S15)
        PaneNote(S23)
        PhoneIntakeFactRows("screenshots")
    }
}
