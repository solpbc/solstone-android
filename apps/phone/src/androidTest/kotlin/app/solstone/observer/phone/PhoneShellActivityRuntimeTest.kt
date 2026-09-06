// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellActivityRuntimeTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
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
    fun launchesResumedAndRendersLiveSourceTiles() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(sourceTileMatcher()).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("sourceTile-audio").assertIsDisplayed()
            composeRule.onNodeWithTag("sourceTile-location").assertIsDisplayed()
            assertEquals(2, composeRule.onAllNodes(sourceTileMatcher()).fetchSemanticsNodes().size)
        }
    }

    private fun sourceTileMatcher() = SemanticsMatcher("source tile") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("sourceTile-") == true
    }
}
