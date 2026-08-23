// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShellBackFallThroughRuntimeTest {
    @Test
    fun shippingShellDoesNotInterceptBack() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            Espresso.pressBackUnconditionally()
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}
