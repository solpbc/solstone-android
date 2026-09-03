// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellStatusCaptureRuntimeTest {
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
    fun debugCapturesRenderDeterministicallyWithoutReadingTheSharedRunner() {
        PhoneStatusSupplier.override = { error("captured status must not read the supplier") }

        assertCapture("loading", expectedPillText = null)
        assertCapture("failed", expectedPillText = null)
        assertCapture("unpaired", expectedPillText = "not paired")
        assertCapture("paired-offline", expectedPillText = "offline · 1 waiting")
        assertCapture("paired-caught-up", expectedPillText = "connected")
    }

    @Test
    fun retainedViewModelRefreshesOnLaterResumeAndReplacesPriorStatus() {
        val reads = AtomicInteger(0)
        val paired = AtomicBoolean(false)
        val fail = AtomicBoolean(false)
        PhoneStatusSupplier.override = {
            reads.incrementAndGet()
            if (fail.get()) throw IllegalStateException("refresh failed")
            if (paired.get()) {
                HarnessBacklogStatus(HarnessPlStatus.Reachable(200), 0, emptyList())
            } else {
                HarnessBacklogStatus(HarnessPlStatus.NotPaired, 0, emptyList())
            }
        }

        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            awaitPill("not paired")
            assertEquals(1, reads.get())

            scenario.recreate()
            awaitPill("not paired")
            assertEquals(2, reads.get())

            paired.set(true)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitPill("connected")
            assertEquals(3, reads.get())

            fail.set(true)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            composeRule.waitUntil(10_000) { reads.get() == 4 }
            composeRule.onNodeWithTag("statusPill").assertDoesNotExist()
        }
    }

    private fun assertCapture(raw: String, expectedPillText: String?) {
        val intent = Intent(context, PhoneShellActivity::class.java)
            .putExtra(CAPTURE_STATUS_EXTRA, raw)
        ActivityScenario.launch<PhoneShellActivity>(intent).use {
            if (expectedPillText == null) {
                composeRule.onNodeWithTag("statusPill").assertDoesNotExist()
            } else {
                awaitPill(expectedPillText)
            }
        }
    }

    private fun awaitPill(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("statusPill").assertIsDisplayed()
    }

    private companion object {
        const val CAPTURE_STATUS_EXTRA = "solstone.design.default-detail-status"
    }
}
