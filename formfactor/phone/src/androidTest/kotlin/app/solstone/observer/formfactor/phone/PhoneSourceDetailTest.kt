// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSourceDetailTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var detailInput by mutableStateOf(
        DetailInput(
            sourceId = "audio",
            loadState = LoadState.Loading,
            onStartObserving = {},
        ),
    )
    private var contentSet = false
    private val homeTileStore = TestPhoneHomeTileStore()

    @Test
    fun detailUsesSelectedRowReasonFromSharedSnapshot() {
        render(
            sourceId = "audio",
            loadState = loaded(
                source("audio", ReasonCode.PERMISSION_REVOKED),
                source("location", ReasonCode.STORAGE_FULL),
            ),
        )

        composeRule.onNodeWithText(sourceDetailRule(ReasonCode.PERMISSION_REVOKED).diagnosis!!)
            .assertIsDisplayed()
        composeRule.onNodeWithText(sourceDetailRule(ReasonCode.STORAGE_FULL).diagnosis!!)
            .assertDoesNotExist()
    }

    @Test
    fun templateOrdersVerdictReasonActionAndFacts() {
        render(loadState = loaded(source("audio", ReasonCode.PERMISSION_REVOKED)))

        val verdict = composeRule.onNodeWithTag(VERDICT_TEST_TAG).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val reason = composeRule.onNodeWithTag(REASON_TEST_TAG).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val action = composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val facts = composeRule.onNodeWithTag(FACTS_TEST_TAG).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        assertTrue(verdict < reason)
        assertTrue(reason < action)
        assertTrue(action < facts)
    }

    @Test
    fun retryAffordanceAppearsOnlyForServiceKilledAndRebooted() {
        val retryLabel = sourceDetailRule(ReasonCode.SERVICE_KILLED).action!!.label
        ReasonCode.values().forEach { reason ->
            render(loadState = loaded(source("audio", reason)))

            if (sourceDetailRule(reason).retryHonest) {
                composeRule.onNodeWithText(retryLabel).assertIsDisplayed()
                composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed().assertIsEnabled()
            } else {
                composeRule.onNodeWithText(retryLabel).assertDoesNotExist()
            }
        }
    }

    @Test
    fun disabledStubActionsAreDisplayedAndHaveNoClickPath() {
        ReasonCode.values()
            .filter { reason ->
                sourceDetailRule(reason).action != null && !sourceDetailRule(reason).retryHonest
            }
            .forEach { reason ->
                render(loadState = loaded(source("audio", reason)))

                composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed().assertIsNotEnabled()
            }
    }

    @Test
    fun providerSilentShowsDiagnosisWithoutAction() {
        render(loadState = loaded(source("audio", ReasonCode.PROVIDER_SILENT)))

        composeRule.onNodeWithText(sourceDetailRule(ReasonCode.PROVIDER_SILENT).diagnosis!!)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ACTION_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun loadingFailedAndMissingRowsShowOnlyHomeTileControl() {
        render(loadState = LoadState.Loading)
        assertOnlyHomeTileControl()

        render(loadState = LoadState.Failed(IllegalStateException("failed")))
        assertOnlyHomeTileControl()

        render(
            sourceId = "camera",
            loadState = loaded(source("audio", ReasonCode.PERMISSION_REVOKED)),
        )
        assertOnlyHomeTileControl()
    }

    @Test
    fun startObservingActionInvokesRequiredCallback() {
        var starts = 0
        render(
            loadState = loaded(source("audio", ReasonCode.SERVICE_KILLED)),
            onStartObserving = { starts += 1 },
        )

        composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed().performClick()

        assertEquals(1, starts)
    }

    @Test
    fun homeTileControlReflectsStoredValueOnFirstComposition() {
        homeTileStore.setHasTile("audio", true)

        render(loadState = loaded(source("audio", ReasonCode.NONE)))

        composeRule.onNodeWithTag(HOME_TILE_CONTROL_TEST_TAG).assertIsDisplayed().assertIsOn()
    }

    private fun render(
        sourceId: String = "audio",
        loadState: LoadState<SourcesReadModel>,
        onStartObserving: () -> Unit = {},
    ) {
        val input = DetailInput(sourceId, loadState, onStartObserving)
        if (contentSet) {
            composeRule.runOnIdle { detailInput = input }
        } else {
            detailInput = input
            composeRule.setContent {
                PhoneTheme {
                    PhoneSourceDetail(
                        loadState = detailInput.loadState,
                        sourceId = detailInput.sourceId,
                        homeTileStore = homeTileStore,
                        onStartObserving = detailInput.onStartObserving,
                    )
                }
            }
            contentSet = true
        }
        composeRule.waitForIdle()
    }

    private fun assertOnlyHomeTileControl() {
        composeRule.onNodeWithText("give this a tile on home").assertIsDisplayed()
        composeRule.onNodeWithTag(VERDICT_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(REASON_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(ACTION_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(FACTS_TEST_TAG).assertDoesNotExist()
    }
}

private data class DetailInput(
    val sourceId: String,
    val loadState: LoadState<SourcesReadModel>,
    val onStartObserving: () -> Unit,
)

private class TestPhoneHomeTileStore : PhoneHomeTileStore {
    private val tiles = mutableMapOf<String, Boolean>()

    override fun hasTile(sourceId: String): Boolean = tiles[sourceId] ?: false

    override fun setHasTile(sourceId: String, hasTile: Boolean) {
        tiles[sourceId] = hasTile
    }
}

private fun source(sourceId: String, reason: ReasonCode) = SourceStatus(
    sourceId = sourceId,
    wish = SourceWish.On,
    state = SourceState.NEEDS_ATTENTION,
    reason = reason,
)

private fun loaded(vararg sources: SourceStatus) = LoadState.Loaded(
    SourcesReadModel(
        observer = ObserverStatus(SourceState.NEEDS_ATTENTION, ReasonCode.NONE),
        sources = sources.toList(),
    ),
)
