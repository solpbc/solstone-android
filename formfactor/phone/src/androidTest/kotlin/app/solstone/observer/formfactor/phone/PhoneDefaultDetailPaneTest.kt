// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.window.core.layout.WindowSizeClass
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class PhoneDefaultDetailPaneTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pairedOfflineShowsWaitingSourceAndOpeningItReplacesDefaultDetail() {
        val audio = source("audio")
        val snapshot = snapshot(paired = true, online = false, pendingCount = 2, waiting = listOf(audio))

        setWideContent(
            status = snapshot.status,
            detailStatus = PhoneDefaultDetailStatus.Paired(snapshot),
            sources = listOf(audio),
        )

        composeRule.onNodeWithTag("phoneDefaultDetail").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneDefaultStatusHeading").assertIsDisplayed()
        composeRule.onNodeWithText("on this device").assertIsDisplayed()
        composeRule.onNodeWithTag("waitingRow-audio").performClick()

        composeRule.onNodeWithTag(VERDICT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("phoneDefaultDetail").assertDoesNotExist()
    }

    @Test
    fun pairedCaughtUpUsesTheSameDefaultCarrier() {
        val snapshot = snapshot(paired = true, online = true, pendingCount = 0)

        setWideContent(
            status = snapshot.status,
            detailStatus = PhoneDefaultDetailStatus.Paired(snapshot),
        )

        composeRule.onNodeWithTag("phoneDefaultDetail").assertIsDisplayed()
        composeRule.onNodeWithText("all caught up").assertIsDisplayed()
        composeRule.onNodeWithText("everything is in your journal").assertIsDisplayed()
    }

    @Test
    fun unpairedShowsTheJournalSetupBesideTheDeckAndConnectsOnce() {
        var connections = 0

        setWideContent(
            status = PhoneStatusModel(false, false, 0, false),
            detailStatus = PhoneDefaultDetailStatus.Unpaired,
            onConnectJournal = { connections += 1 },
        )

        composeRule.onNodeWithTag("deck").assertIsDisplayed()
        composeRule.onNodeWithTag("yourJournalConnect").assertIsDisplayed().performClick()
        assertEquals(1, connections)
    }

    @Test
    fun loadingAndFailureAreGapHonestAndFailureCanRetryAgain() {
        var detailStatus by mutableStateOf<PhoneDefaultDetailStatus>(PhoneDefaultDetailStatus.Loading)
        var retries = 0
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(WIDE_SIZE)) {
                PhoneObserverScreen(
                    loadState = loadedSources(),
                    status = null,
                    defaultDetailStatus = detailStatus,
                    onRefreshStatus = {
                        retries += 1
                        detailStatus = PhoneDefaultDetailStatus.Loading
                    },
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                )
            }
        }

        composeRule.onNodeWithTag("phoneDefaultDetailLoading").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneDefaultStatusHeading").assertIsDisplayed()
        composeRule.onNodeWithText("connected").assertDoesNotExist()
        composeRule.onNodeWithText("not paired").assertDoesNotExist()

        composeRule.runOnIdle { detailStatus = PhoneDefaultDetailStatus.Failed }
        composeRule.onNodeWithTag("phoneDefaultDetailFailed").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneDefaultStatusHeading").assertIsDisplayed()
        composeRule.onNodeWithText("status unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneDefaultDetailFailedRetry").performClick()
        assertEquals(1, retries)
        composeRule.onNodeWithTag("phoneDefaultDetailLoading").assertIsDisplayed()

        composeRule.runOnIdle { detailStatus = PhoneDefaultDetailStatus.Failed }
        composeRule.onNodeWithTag("phoneDefaultDetailFailedRetry").assertIsDisplayed()
    }

    @Test
    fun rootSelectionsReplaceDetailAndBackReturnsToLiveDefault() {
        var detailStatus by mutableStateOf<PhoneDefaultDetailStatus>(
            PhoneDefaultDetailStatus.Paired(snapshot(paired = true, online = true, pendingCount = 0)),
        )
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(WIDE_SIZE)) {
                PhoneObserverScreen(
                    loadState = loadedSources(),
                    status = PhoneStatusModel(true, true, 0, false),
                    defaultDetailStatus = detailStatus,
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                )
            }
        }

        composeRule.onNodeWithTag("importTile").performClick()
        composeRule.onNodeWithTag("addMoreTile").performClick()
        composeRule.runOnIdle { detailStatus = PhoneDefaultDetailStatus.Unpaired }

        Espresso.pressBack()

        composeRule.onNodeWithTag("yourJournalConnect").assertIsDisplayed()
        composeRule.onNodeWithTag("phoneDefaultDetail").assertIsDisplayed()
    }

    @Test
    fun defaultDetailIsAbsentAt599DpAndPresentAt600Dp() {
        var adaptiveInfo by mutableStateOf(adaptiveInfo(widthDp = 599))
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(WIDE_SIZE)) {
                PhoneObserverScreen(
                    loadState = loadedSources(),
                    status = null,
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                    windowAdaptiveInfo = adaptiveInfo,
                )
            }
        }
        composeRule.onNodeWithTag("phoneDefaultDetail").assertDoesNotExist()

        composeRule.runOnIdle {
            adaptiveInfo = adaptiveInfo(widthDp = 600)
        }
        composeRule.onNodeWithTag("phoneDefaultDetail").assertIsDisplayed()
    }

    private fun setWideContent(
        status: PhoneStatusModel,
        detailStatus: PhoneDefaultDetailStatus,
        sources: List<SourceStatus> = emptyList(),
        onConnectJournal: () -> Unit = {},
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(WIDE_SIZE)) {
                PhoneObserverScreen(
                    loadState = loadedSources(sources),
                    status = status,
                    defaultDetailStatus = detailStatus,
                    onToggle = { _, _ -> },
                    onStartObserving = {},
                    onConnectJournal = onConnectJournal,
                )
            }
        }
    }

    private companion object {
        val WIDE_SIZE = DpSize(800.dp, 800.dp)

        fun adaptiveInfo(widthDp: Int) = WindowAdaptiveInfo(
            windowSizeClass = WindowSizeClass(widthDp, 800),
            windowPosture = Posture(),
        )
    }
}

private fun source(id: String): SourceStatus = SourceStatus(
    sourceId = id,
    wish = SourceWish.On,
    state = SourceState.ON,
    reason = ReasonCode.NONE,
)

private fun snapshot(
    paired: Boolean,
    online: Boolean,
    pendingCount: Int,
    waiting: List<SourceStatus> = emptyList(),
): PhoneStatusSnapshot = PhoneStatusSnapshot(
    status = PhoneStatusModel(
        paired = paired,
        online = online,
        pendingCount = pendingCount,
        hasContentPending = waiting.isNotEmpty(),
    ),
    waiting = waiting,
)

private fun loadedSources(sources: List<SourceStatus> = emptyList()): LoadState<SourcesReadModel> = LoadState.Loaded(
    SourcesReadModel(
        observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
        sources = sources,
    ),
)
