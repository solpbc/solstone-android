// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.solstone.observer.harness.SourceToggleResult
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.scaffold.ObserverAppContainer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellToggleActuationRuntimeTest {
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
    fun toggleThroughTheSwitchActuatesTheEngine() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            waitUntil("audio engine running") { audioEngine(container).condition().running }
            val index = audioIndex(container)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("sourceSwitch-audio", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithTag("sourceSwitch-audio", useUnmergedTree = true).performClick()
            waitUntil("audio engine stopped") { !container.sources.engines[index].condition().running }
            assertFalse(container.sources.engines[index].condition().running)

            composeRule.onNodeWithTag("sourceSwitch-audio", useUnmergedTree = true).performClick()
            waitUntil("audio engine running after on") { container.sources.engines[index].condition().running }
            assertTrue(container.sources.engines[index].condition().running)
        }
    }

    @Test
    fun setWishAppliesAndActuatesBothDirections() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        val token = container.captureAuthority.acquire()
        var ensureObservingNs = 0L
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val started = System.nanoTime()
            container.controller.ensureObserving()
            ensureObservingNs = System.nanoTime() - started
        }
        val ensureObservingMs = ensureObservingNs / 1_000_000.0
        Log.i(COST_TAG, "ensureObserving_ms=$ensureObservingMs")
        println("ensureObserving_ms=$ensureObservingMs")

        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            waitUntil("audio engine running") { audioEngine(container).condition().running }
            val index = audioIndex(container)

            val offResult = container.sources.setWish("audio", SourceWish.Off)
            assertEquals(SourceToggleResult.Applied, offResult)
            assertFalse(container.sources.engines[index].condition().running)

            val started = System.nanoTime()
            val onResult = container.sources.setWish("audio", SourceWish.On)
            val setWishNs = System.nanoTime() - started
            val setWishMs = setWishNs / 1_000_000.0
            Log.i(COST_TAG, "setWish_on_ms=$setWishMs")
            println("setWish_on_ms=$setWishMs")
            assertEquals(SourceToggleResult.Applied, onResult)
            assertTrue(container.sources.engines[index].condition().running)
        }
        container.captureAuthority.release(token)
    }

    @Test
    fun setWishOnBeforePipelineReturnsAwaitingObserver() {
        val container = obtainObserverContainer()
        val result = container.sources.setWish("audio", SourceWish.On)
        assertEquals(SourceToggleResult.AwaitingObserver, result)
        assertFalse(audioEngine(container).condition().running)
    }

    private fun audioIndex(container: ObserverAppContainer): Int {
        val index = container.sources.snapshot().sources.indexOfFirst { it.sourceId == "audio" }
        check(index >= 0) { "audio source missing from snapshot" }
        return index
    }

    private fun audioEngine(container: ObserverAppContainer) =
        container.sources.engines[audioIndex(container)]

    private companion object {
        const val COST_TAG = "PhoneShellCost"
    }
}
