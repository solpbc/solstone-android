// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.model.QueueState
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.testing.validDirectPairLink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellStatusRuntimeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun emptyDatabaseStatusPaneHasNoWaitingRows() {
        val container = preparedContainer()
        pair(container)

        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            openStatusPane()
            assertEquals(0, composeRule.onAllNodes(waitingRowMatcher()).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun pendingRegisteredAndOrphanSourcesRenderExactlyTwoWaitingRows() {
        val container = preparedSeededContainer(::seedTwoSourceBacklog)
        pair(container)

        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            openStatusPane()
            assertEquals(2, composeRule.onAllNodes(waitingRowMatcher()).fetchSemanticsNodes().size)
            composeRule.onNodeWithTag("waitingRow-audio").assertIsDisplayed()
            composeRule.onNodeWithTag("waitingRow-legacy-import").assertIsDisplayed().assertTextEquals("legacy-import")
        }
    }

    @Test
    fun waitingAudioRowOpensMountedSourceDetailPane() {
        val container = preparedSeededContainer(::seedTwoSourceBacklog)
        pair(container)

        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            openStatusPane()
            composeRule.onNodeWithTag("waitingRow-audio").assertIsDisplayed().performClick()
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(sourceDetailPaneMatcher(), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNode(sourceDetailPaneMatcher(), useUnmergedTree = true).assertExists()
            composeRule.onNode(audioHeadingMatcher()).assertIsDisplayed()
        }
    }

    @Test
    fun connectedPillRendersConnected() {
        val container = preparedContainer()
        pair(container)

        assertPillText("connected")
    }

    @Test
    fun syncingPillRendersLivePendingCount() {
        val container = preparedSeededContainer {
            seedEvidence(context, "syncing-one", listOf("audio"), MAIN_STREAM, QueueState.SEALED)
            seedEvidence(context, "syncing-two", listOf("location"), MAIN_STREAM, QueueState.UPLOADING)
        }
        requireNotNull(container.flavor.heartbeatControl).setFresh(false)
        pair(container)
        requireNotNull(container.flavor.heartbeatControl).setFresh(true)

        assertPillText("2 syncing")
    }

    @Test
    fun offlinePillRendersLivePendingCount() {
        val container = preparedSeededContainer {
            seedEvidence(context, "offline-one", listOf("audio"), MAIN_STREAM, QueueState.SEALED)
            seedEvidence(context, "offline-two", listOf("location"), MAIN_STREAM, QueueState.UPLOADING)
            seedEvidence(context, "offline-three", listOf("camera"), MAIN_STREAM, QueueState.FAILED)
        }
        pair(container)
        requireNotNull(container.flavor.heartbeatControl).setFresh(false)

        assertPillText("offline · 3 waiting")
    }

    @Test
    fun notPairedPillRendersNotPaired() {
        val container = preparedSeededContainer {
            seedEvidence(context, "unpaired-one", listOf("audio"), MAIN_STREAM, QueueState.SEALED)
            seedEvidence(context, "unpaired-two", listOf("location"), MAIN_STREAM, QueueState.UPLOADING)
            seedEvidence(context, "unpaired-three", listOf("camera"), MAIN_STREAM, QueueState.FAILED)
            seedEvidence(context, "unpaired-four", emptyList(), MAIN_STREAM, QueueState.SEALED)
        }
        assertPillText("not paired")
    }

    @Test
    fun statusActionIsAbsentWhileSupplierIsLatchBlocked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        PhoneStatusSupplier.override = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "status supplier was not released" }
            HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList())
        }
        preparedContainer()

        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                composeRule.onNodeWithTag("statusPill").assertDoesNotExist()
            } finally {
                release.countDown()
            }
            awaitStatusPill()
        }
    }

    @Test
    fun statusActionIsAbsentWhenSupplierThrows() {
        val readFinished = CountDownLatch(1)
        PhoneStatusSupplier.override = {
            readFinished.countDown()
            throw IllegalStateException("status read failed")
        }
        preparedContainer()

        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            assertTrue(readFinished.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("statusPill").assertDoesNotExist()
            composeRule.onNodeWithText("connected").assertDoesNotExist()
            composeRule.onNodeWithText("not paired").assertDoesNotExist()
            composeRule.onNodeWithTag("statusState", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    private fun preparedContainer() = prepareContainer(obtainObserverContainer())

    private fun preparedSeededContainer(seed: () -> Unit) =
        prepareContainer(seededObserverContainer(seed))

    private fun prepareContainer(container: ObserverAppContainer): ObserverAppContainer {
        assertTrue(waitForRecovery(container))
        container.sources.setWish("audio", app.solstone.observer.harness.SourceWish.Off)
        container.sources.setWish("location", app.solstone.observer.harness.SourceWish.Off)
        requireNotNull(container.flavor.heartbeatControl).setFresh(true)
        return container
    }

    private fun pair(container: ObserverAppContainer) {
        assertTrue(container.controller.onScannedPairLink(validDirectPairLink()) != null)
    }

    private fun seedTwoSourceBacklog() {
        seedEvidence(
            context = context,
            id = "two-source-backlog",
            sourceIds = listOf("audio", "legacy-import"),
            stream = MAIN_STREAM,
            state = QueueState.SEALED,
        )
    }

    private fun assertPillText(expected: String) {
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            awaitStatusPill()
            val state = composeRule.onNodeWithTag("statusState", useUnmergedTree = true)
            state
                .assertIsDisplayed()
                .assertTextEquals(expected)
        }
    }

    private fun openStatusPane() {
        awaitStatusPill()
        composeRule.onNodeWithTag("statusPill").assertIsDisplayed().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("statusPane").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("statusPane").assertIsDisplayed()
    }

    private fun awaitStatusPill() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("statusPill").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("statusPill").assertIsDisplayed()
    }

    private fun waitingRowMatcher() = SemanticsMatcher("waiting row") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("waitingRow-") == true
    }

    private fun sourceDetailPaneMatcher() = SemanticsMatcher("audio source detail pane") { node ->
        node.config.getOrNull(SemanticsProperties.PaneTitle) == "audio" &&
            node.hasDescendantWithTag("sourceDetailHomeTile")
    }

    private fun audioHeadingMatcher() = SemanticsMatcher("audio heading") { node ->
        node.config.contains(SemanticsProperties.Heading) &&
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "audio" } == true
    }
}

private fun SemanticsNode.hasDescendantWithTag(testTag: String): Boolean =
    children.any { child ->
        child.config.getOrNull(SemanticsProperties.TestTag) == testTag ||
            child.hasDescendantWithTag(testTag)
    }
