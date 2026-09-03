// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellBackFallThroughRuntimeTest {
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
    fun shippingShellDoesNotInterceptBack() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            lateinit var activity: PhoneShellActivity
            scenario.onActivity { launched -> activity = launched }
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            Espresso.pressBackUnconditionally()
            assertTrue(
                "activity did not begin finishing after deck-level back " +
                    "(state=${scenario.state})",
                scenario.state == Lifecycle.State.DESTROYED || activity.isFinishing,
            )
        }
    }
}
