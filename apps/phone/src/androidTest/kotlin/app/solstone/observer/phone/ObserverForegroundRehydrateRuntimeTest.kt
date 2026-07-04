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
import app.solstone.platform.fgs.ObserverForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObserverForegroundRehydrateRuntimeTest {
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
     * AC8 red proof: without Application rehydrator registration, sticky foreground restarts could not dispatch rehydrate.
     */
    @Test
    fun applicationRegistersForegroundServiceRehydratorAndDispatchIsHonestWhenBlocked() {
        ActivityScenario.launch(ObserverActivity::class.java).use {
            val container = waitForObserverContainer()
            assertNotNull(ObserverForegroundService.rehydrator)

            val testVisibleOwner = container.captureAuthority.acquire()
            container.captureAuthority.release(testVisibleOwner)
            container.controller.ensureObserving()

            ObserverForegroundService.dispatchRehydrate(ObserverForegroundService.rehydrator)

            waitUntil("blocked rehydrate diagnostics") {
                container.controller.diagnostics().state == SourceState.NEEDS_ATTENTION
            }
            assertEquals(SourceState.NEEDS_ATTENTION, container.controller.diagnostics().state)
        }
    }
}
