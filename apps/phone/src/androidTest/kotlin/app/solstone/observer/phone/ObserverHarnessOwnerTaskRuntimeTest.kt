// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.observer.scaffold.ObserverActivity
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner never lands on operator instrumentation.
 *
 * The shell opens this activity for two owner tasks — `connect a journal` and
 * `manage local storage`. 2.0.0 drew a `Back` button on each, and both the button and the system
 * back gesture went to the harness **menu**: a permission probe, a transport probe, raw start/stop,
 * queue counters, and an evidence browser that prints spool paths, wire segment ids and SHA-256s.
 *
 * ⚠ **A bare launch must still get the menu** — `apps/watch` declares this activity as its
 * `LAUNCHER`, so on that surface the harness is the whole app. That case is the third test here,
 * and it is the one that would catch this fix going too far.
 */
@RunWith(AndroidJUnit4::class)
class ObserverHarnessOwnerTaskRuntimeTest {

    @Before
    fun grantPermissions() {
        // The pair-QR task builds a camera preview; without CAMERA the screen can blow up for
        // reasons that have nothing to do with what this test asserts.
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO").forEach {
            runCatching { automation.grantRuntimePermission(pkg, it) }
        }
    }

    @Test
    fun theConnectJournalTaskShowsNoOperatorMenuAndNoDrawnBack() {
        assertOwnerTask(ObserverActivity.EXTRA_SCAN_PAIR_QR)
    }

    @Test
    fun theManageLocalStorageTaskShowsNoOperatorMenuAndNoDrawnBack() {
        assertOwnerTask(ObserverActivity.EXTRA_SHOW_LOCAL_CACHE)
    }

    @Test
    fun aBareLaunchStillGetsTheOperatorMenu() {
        // Non-vacuity for the two tests above: it proves the menu labels they assert absent are
        // labels this build actually renders somewhere, so their absence is a measurement.
        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val labels = buttonLabels(activity)
                MENU_ONLY_CONTROLS.forEach {
                    assertTrue("bare launch must show \"$it\"; saw $labels", labels.contains(it))
                }
            }
        }
    }

    private fun assertOwnerTask(extra: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, ObserverActivity::class.java).putExtra(extra, true)

        ActivityScenario.launch<ObserverActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                val labels = buttonLabels(activity)
                MENU_ONLY_CONTROLS.forEach {
                    assertFalse(
                        "$extra must not expose operator control \"$it\"; saw $labels",
                        labels.contains(it),
                    )
                }
                // The platform bible gives Android's back to the system gesture. A drawn `Back` on
                // a screen the shell pushed is this harness's own idiom leaking.
                assertFalse("$extra must not draw its own Back; saw $labels", labels.contains("Back"))
            }

            // Leaving the task returns to the shell rather than descending into the harness.
            // ⚠ Driving `onBackPressed()` directly rather than the gesture: on API 33+ the system
            // routes back through the registered `OnBackInvokedCallback` instead, but both paths
            // funnel into the same `handleBack()` predicate, which is what this asserts.
            val finishing = AtomicBoolean(false)
            scenario.onActivity { activity ->
                @Suppress("DEPRECATION")
                activity.onBackPressed()
                finishing.set(activity.isFinishing)
            }
            assertTrue("$extra must finish back to the shell, not show the menu", finishing.get())
        }
    }

    private fun buttonLabels(activity: Activity): List<String> =
        buildList { collectButtons(activity.findViewById(android.R.id.content), this) }

    private fun collectButtons(view: View, into: MutableList<String>) {
        if (view is Button) into += view.text.toString()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectButtons(view.getChildAt(i), into)
        }
    }

    private companion object {
        /**
         * Controls that exist **only** on the operator menu, so finding any of them on an owner
         * task means the owner reached the harness proper.
         */
        val MENU_ONLY_CONTROLS = listOf(
            "Permissions",
            "PL status probe",
            "Start/stop intake",
            "Status + queue/sync",
            "Evidence + export",
        )
    }
}
