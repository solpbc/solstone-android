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
    fun recoveryAffordanceMatchesTheFaultClass() {
        val expectedRetryable = setOf(
            ReasonCode.SERVICE_KILLED,
            ReasonCode.REBOOTED,
            ReasonCode.FOREGROUND_TYPE_NOT_HELD,
            ReasonCode.FOREGROUND_START_NOT_ALLOWED,
            ReasonCode.PERSISTENCE_FAILED,
        )
        ReasonCode.entries.forEach { reason ->
            render(loadState = loaded(source("audio", reason)))
            val rule = sourceDetailRule(reason)

            if (reason in expectedRetryable) {
                assertTrue("expected retryHonest=true for $reason", rule.retryHonest)
                val label = rule.action!!.label
                composeRule.onNodeWithText(label).assertIsDisplayed()
                composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed().assertIsEnabled()
            } else {
                org.junit.Assert.assertFalse("expected retryHonest=false for $reason", rule.retryHonest)
                if (rule.action == null) {
                    composeRule.onNodeWithTag(ACTION_TEST_TAG).assertDoesNotExist()
                } else if (rule.action.kind != SourceDetailActionKind.RETRY) {
                    composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsEnabled()
                } else {
                    composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsNotEnabled()
                }
            }
        }
    }

    @Test
    fun nonRetryRecoveryActionsAreEnabled() {
        ReasonCode.values()
            .filter { reason ->
                sourceDetailRule(reason).action?.kind != null &&
                    sourceDetailRule(reason).action?.kind != SourceDetailActionKind.RETRY
            }
            .forEach { reason ->
                render(loadState = loaded(source("audio", reason)))

                composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed().assertIsEnabled()
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
    fun connectJournalActionIsEnabledAndInvokesConnectJournalOnly() {
        var starts = 0
        var connects = 0
        render(
            loadState = loaded(source("audio", ReasonCode.UNPAIRED)),
            onStartObserving = { starts += 1 },
            onConnectJournal = { connects += 1 },
        )

        composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(1, connects)
        assertEquals(0, starts)
    }

    @Test
    fun grantPermissionsActionInvokesPermissionFlowOnly() {
        var starts = 0
        var grants = 0
        var connects = 0
        var managesStorage = 0
        render(
            loadState = loaded(source("audio", ReasonCode.PERMISSION_REVOKED)),
            onStartObserving = { starts += 1 },
            onGrantPermissions = { grants += 1 },
            onConnectJournal = { connects += 1 },
            onManageLocalStorage = { managesStorage += 1 },
        )

        composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(1, grants)
        assertEquals(0, starts)
        assertEquals(0, connects)
        assertEquals(0, managesStorage)
    }

    @Test
    fun pairAgainActionInvokesPairingOnly() {
        var starts = 0
        var grants = 0
        var connects = 0
        var managesStorage = 0
        render(
            loadState = loaded(source("audio", ReasonCode.AUTH_REVOKED)),
            onStartObserving = { starts += 1 },
            onGrantPermissions = { grants += 1 },
            onConnectJournal = { connects += 1 },
            onManageLocalStorage = { managesStorage += 1 },
        )

        composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(1, connects)
        assertEquals(0, starts)
        assertEquals(0, grants)
        assertEquals(0, managesStorage)
    }

    @Test
    fun manageLocalStorageActionInvokesLocalCacheControlsOnly() {
        var starts = 0
        var grants = 0
        var connects = 0
        var managesStorage = 0
        render(
            loadState = loaded(source("audio", ReasonCode.STORAGE_FULL)),
            onStartObserving = { starts += 1 },
            onGrantPermissions = { grants += 1 },
            onConnectJournal = { connects += 1 },
            onManageLocalStorage = { managesStorage += 1 },
        )

        composeRule.onNodeWithTag(ACTION_TEST_TAG).assertIsDisplayed().assertIsEnabled().performClick()

        assertEquals(1, managesStorage)
        assertEquals(0, starts)
        assertEquals(0, grants)
        assertEquals(0, connects)
    }

    @Test
    fun homeTileControlReflectsStoredValueOnFirstComposition() {
        homeTileStore.setHasTile("audio", true)

        render(loadState = loaded(source("audio", ReasonCode.NONE)))

        composeRule.onNodeWithTag(HOME_TILE_CONTROL_TEST_TAG).assertIsDisplayed().assertIsOn()
    }

    @Test
    fun settingUpSubLineReadsUnpairedWhenAHigherPriorityReasonMasksUnpaired() {
        // The unpaired sub-line was selected as `observer.reason != UNPAIRED`, so any
        // higher-priority reason — here a stale heartbeat — made an unpaired device claim it was
        // "connecting to your journal" while the status pill read `not paired`.
        render(
            loadState = loadedWith(
                ObserverStatus(SourceState.NEEDS_ATTENTION, ReasonCode.SERVICE_KILLED, paired = false),
                settingUp("audio"),
            ),
        )

        composeRule.onNodeWithText("getting ready…").assertIsDisplayed()
        composeRule.onNodeWithText("getting ready — connecting to your journal.").assertDoesNotExist()
    }

    @Test
    fun settingUpSubLineNamesTheJournalOnceTheDeviceIsPaired() {
        render(
            loadState = loadedWith(
                ObserverStatus(SourceState.SETTING_UP, ReasonCode.NONE, paired = true),
                settingUp("audio"),
            ),
        )

        composeRule.onNodeWithText("getting ready — connecting to your journal.").assertIsDisplayed()
    }

    private fun render(
        sourceId: String = "audio",
        loadState: LoadState<SourcesReadModel>,
        onStartObserving: () -> Unit = {},
        onGrantPermissions: () -> Unit = {},
        onConnectJournal: () -> Unit = {},
        onManageLocalStorage: () -> Unit = {},
    ) {
        val input = DetailInput(
            sourceId,
            loadState,
            onStartObserving,
            onGrantPermissions,
            onConnectJournal,
            onManageLocalStorage,
        )
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
                        onGrantPermissions = detailInput.onGrantPermissions,
                        onConnectJournal = detailInput.onConnectJournal,
                        onManageLocalStorage = detailInput.onManageLocalStorage,
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
    val onGrantPermissions: () -> Unit = {},
    val onConnectJournal: () -> Unit = {},
    val onManageLocalStorage: () -> Unit = {},
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

private fun loadedWith(observer: ObserverStatus, vararg sources: SourceStatus) = LoadState.Loaded(
    SourcesReadModel(observer = observer, sources = sources.toList()),
)

private fun settingUp(sourceId: String) = SourceStatus(
    sourceId = sourceId,
    wish = SourceWish.On,
    state = SourceState.SETTING_UP,
    reason = ReasonCode.NONE,
)
