// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.graphics.Insets
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.observer.scaffold.ObserverActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObserverHarnessChromeRuntimeTest {
    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun noActionBarAndSyntheticSystemBarInsetPadsHarnessRoot() {
        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNull(activity.actionBar)
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val insets = WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 63, 0, 0))
                    .build()

                root.dispatchApplyWindowInsets(insets)

                assertEquals(63, root.paddingTop)
            }
        }
    }

    @Test
    fun systemBackReturnsFromSubmenuThenFinishesAtMenu() {
        lateinit var activity: ObserverActivity
        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            scenario.onActivity {
                activity = it
                val root = it.findViewById<View>(android.R.id.content)
                requireButton(root, "Permissions").performClick()
                assertNotNull(findButton(root, "Back"))
            }

            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

            scenario.onActivity {
                val root = it.findViewById<View>(android.R.id.content)
                assertNotNull(findButton(root, "Permissions"))
                assertNull(findButton(root, "Back"))
            }

            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntilFinished(activity)
            assertTrue(activity.isFinishing || activity.isDestroyed)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun legacyOnBackPressedReturnsFromSubmenu() {
        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                requireButton(root, "Permissions").performClick()
                assertNotNull(findButton(root, "Back"))

                activity.onBackPressed()

                assertNotNull(findButton(root, "Permissions"))
                assertNull(findButton(root, "Back"))
                assertFalse(activity.isFinishing)
            }
        }
    }

    private fun requireButton(root: View, label: String): Button = requireNotNull(findButton(root, label))

    private fun findButton(root: View, label: String): Button? {
        if (root is Button && root.text.toString() == label) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findButton(root.getChildAt(index), label)?.let { return it }
        }
        return null
    }

    private fun waitUntilFinished(activity: ObserverActivity) {
        repeat(50) {
            if (activity.isFinishing || activity.isDestroyed) return
            Thread.sleep(20)
        }
    }
}
