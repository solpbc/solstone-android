// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellCaptureOwnerRuntimeTest {
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
    fun captureOwnerPresentOnlyWhileResumed() {
        val container = obtainObserverContainer()
        assertFalse(container.captureAuthority.isVisibleOwnerPresent())
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            assertTrue(container.captureAuthority.isVisibleOwnerPresent())
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(container.captureAuthority.isVisibleOwnerPresent())
            assertSame(container, waitForObserverContainer())
        }
        assertFalse(container.captureAuthority.isVisibleOwnerPresent())
    }
}
