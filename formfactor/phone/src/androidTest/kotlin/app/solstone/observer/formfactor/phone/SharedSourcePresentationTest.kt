// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.pl.KnownSources
import app.solstone.core.pl.SharedSourceDevice
import app.solstone.core.pl.SharedSourceRow
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class SharedSourcePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val time = Instant.parse("2026-11-20T12:00:00Z")
    private val desk = SharedSourceDevice("a", "Desk", "desktop", listOf(
        SharedSourceRow("Notes", "paused", time), SharedSourceRow("Work", "on", time),
    ))
    private val known = KnownSources("r1", mapOf("obsidian" to listOf(desk), "calendar" to listOf(desk)))

    @Test fun tileSpeaksRemoteFactsAgesOfflineAndKeepsOnlyAvailableActions() {
        var state by mutableStateOf(SourceState.READY_TO_SET_UP)
        var now by mutableStateOf(time.toEpochMilli())
        compose.setContent {
            CompositionLocalProvider(LocalPhoneSharedSources provides PhoneSharedSourceInfo(known),
                LocalPhoneStatusNow provides PhoneStatusNow { now }) {
                PhoneSourceTile(SourceStatus("obsidian", SourceWish.Off, state, ReasonCode.NONE), 0, 1, {}, {})
            }
        }
        fun tile() = compose.onNodeWithTag("sourceTile-obsidian").fetchSemanticsNode().config
        assertEquals("from another device. Desk · paused · just now", tile()[SemanticsProperties.StateDescription])
        assertEquals(1, tile()[SemanticsActions.CustomActions].size)
        compose.runOnIdle { state = SourceState.OFF; now += 240_000 }
        assertEquals("from another device. Desk · paused · 4 minutes ago", tile()[SemanticsProperties.StateDescription])
        assertEquals(2, tile()[SemanticsActions.CustomActions].size)
        compose.runOnIdle { state = SourceState.ON }
        assertEquals("on", tile()[SemanticsProperties.StateDescription])
    }

    @Test fun calendarShowsDistinctDevicesUntilLocalSelectionStarts() {
        val other = desk.copy(cid = "b", label = "Laptop", rows = listOf(SharedSourceRow(null, "on", null)))
        val two = known.copy(sources = mapOf("calendar" to listOf(desk, other)))
        var calendar by mutableStateOf(PhoneCalendarPaneState())
        compose.setContent {
            CompositionLocalProvider(LocalPhoneSharedSources provides PhoneSharedSourceInfo(two),
                LocalPhoneCalendar provides PhoneCalendarBinding(state = calendar)) {
                PhoneCalendarPane(verdict = SourceState.READY_TO_SET_UP)
            }
        }
        compose.onNodeWithText("from 2 other devices").assertExists()
        compose.onNodeWithText("Laptop").assertExists()
        compose.runOnIdle {
            calendar = PhoneCalendarPaneState(permissionGranted = true, rows = listOf(PhoneCalendarRow(1, "Home", false, null)))
        }
        compose.onNodeWithText("from 2 other devices").assertDoesNotExist()
        compose.onNodeWithText("Home").assertExists()
    }

    @Test fun addMoreOpensLocalSetupWithBothVaultsAndConfiguredPageShowsFacts() {
        var open by mutableStateOf(false)
        var local by mutableStateOf(SourceState.READY_TO_SET_UP)
        compose.setContent {
            CompositionLocalProvider(LocalPhoneSharedSources provides PhoneSharedSourceInfo(known),
                LocalPhoneStatusNow provides PhoneStatusNow { time.toEpochMilli() }) {
                if (open) PhoneObsidianPane(verdict = local)
                else PhoneAddMorePane(onOpenSource = { open = true }, sources = listOf(
                    SourceStatus("obsidian", SourceWish.Off, local, ReasonCode.NONE)))
            }
        }
        compose.onNodeWithText("from another device", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("addMoreRow-obsidian").performClick()
        compose.onNodeWithText("Notes · paused · just now").assertExists()
        compose.onNodeWithText("Work · just now").assertExists()
        compose.onNodeWithText("choose a folder").assertExists()
        compose.runOnIdle { local = SourceState.ON }
        compose.onNodeWithText("also on").assertExists()
        compose.onNodeWithText("Desk · paused · just now").assertExists()
    }
}
