// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.model.SourceState
import app.solstone.observer.scaffold.ObserverActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObserverActivityConfigChangeRuntimeTest {
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

    /**
     * AC1 red proof: per-Activity ownership left the first pipeline running after recreate, so Stop hit only the new container.
     */
    @Test
    fun activePipelineSurvivesRecreateAndStopStopsOriginalEngine() {
        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            val first = waitForObserverContainer()
            first.controller.onScannedPairLink(validDirectPairLink())
            assertTrue(first.controller.start())
            waitUntil("first capture emissions") { pendingEvidenceCount(context) > 0 }
            val emittedAfterFirstStart = pendingEvidenceCount(context)

            scenario.recreate()

            val second = waitForObserverContainer()
            assertSame(first, second)
            waitUntil("pipeline still running after recreate") {
                first.controller.diagnostics().state == SourceState.ON
            }
            assertEquals(emittedAfterFirstStart, pendingEvidenceCount(context))

            second.controller.stop()

            waitUntil("original engine stopped") {
                first.controller.diagnostics().state == SourceState.OFF
            }
        }
    }
}
