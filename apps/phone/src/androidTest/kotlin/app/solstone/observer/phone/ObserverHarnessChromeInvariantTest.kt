// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.observer.scaffold.ObserverActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chrome gate.
 *
 * solstone-android 0.2.0 shipped to a contract tester with the harness menu drawn behind the status
 * bar and a platform ActionBar. "Permissions" and "Scan pair QR" were physically unreachable, and the
 * menu was shorter than the viewport so the ScrollView could not scroll them into view. Every gate was
 * green, because nothing asserted anything about what was actually on the screen.
 *
 * This asserts the INVARIANT — "a human can actually reach every control" — not one instance of one
 * cause. Two limbs, each catching a distinct failure class, plus a non-vacuity check:
 *
 *   1. content-under-system-bars — every visible control sits inside window - (systemBars u cutout).
 *   2. arbitrary-overlay         — every visible control is hit-testable at its own center.
 *   3. non-vacuity               — the sweep really found the controls it claims to have checked.
 *
 * It is cause-agnostic on purpose: it never names ActionBar, so it also catches a Toolbar, a display
 * cutout, a taller status bar, an overlay, or a screen someone adds next year.
 *
 * Both limbs are mutation-proven, not merely green — see the ship record. Removing
 * applySystemBarInsetPadding() reddens limb 1 on every screen.
 *
 * No screenshots here, deliberately. Per-screen frames come off the REAL Galaxy A36 via
 * `make hitl-phone` (artifacts/hitl/) — real hardware on the primary quality target is the more
 * useful artifact, and this test already fails with the exact offending Rects. GMD captures ARE
 * retrievable (AGP surfaces them under
 * apps/phone/build/outputs/managed_device_android_test_additional_output/), so adding emulator
 * frames later is a plumbing job, not a blocker — we just judged them redundant.
 */
@RunWith(AndroidJUnit4::class)
class ObserverHarnessChromeInvariantTest {

    /** Every harness screen, and the control labels each one is REQUIRED to put on screen. */
    private val screens = listOf(
        Screen(entry = null, name = "menu", required = MENU_ITEMS),
        Screen(entry = "Permissions", name = "permissions", required = listOf("Request permissions", "Back")),
        Screen(entry = "Scan pair QR", name = "scan-pair-qr", required = listOf("Back")),
        Screen(entry = "PL status probe", name = "pl-status-probe", required = listOf("Probe", "Back")),
        Screen(entry = "Start/stop observing", name = "start-stop", required = listOf("Start", "Stop", "Back")),
        Screen(entry = "Status + queue/sync", name = "status-queue-sync", required = listOf("Refresh", "Sync now", "Back")),
        Screen(entry = "Evidence + export", name = "evidence-export", required = listOf("Back")),
    )

    @Before
    fun grantPermissions() {
        // "Scan pair QR" builds a camera preview. Without CAMERA the screen can blow up for reasons
        // that have nothing to do with chrome, which would make this gate red for the wrong reason.
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach { permission ->
            runCatching { automation.grantRuntimePermission(pkg, permission) }
        }
    }

    @Test
    fun everyHarnessScreenKeepsAllControlsReachable() {
        // The invariant is defined against edge-to-edge window metrics (API 30+). The device gate runs
        // pixel5api35, and targetSdk is 35, so this is the real configuration — never skipped there.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

        val checked = mutableListOf<String>()

        ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
            screens.forEach { screen ->
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    screen.entry?.let { requireControl(root, it).performClick() }
                }
                // let the screen settle before we measure it
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()

                scenario.onActivity { activity ->
                    assertScreenIsUsable(activity, screen)
                    checked += screen.name
                }

                // back out to the menu for the next entry
                if (screen.entry != null) {
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<View>(android.R.id.content)
                        requireControl(root, "Back").performClick()
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
            }
        }

        // Non-vacuity, at the sweep level: a gate that quietly checked nothing is worse than no gate.
        assertEquals("every harness screen must be swept", screens.map { it.name }, checked)
    }

    private fun assertScreenIsUsable(activity: Activity, screen: Screen) {
        val metrics = activity.windowManager.currentWindowMetrics
        val window = Rect(metrics.bounds)
        val insets = metrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val safe = Rect(
            window.left + insets.left,
            window.top + insets.top,
            window.right - insets.right,
            window.bottom - insets.bottom,
        )

        val decor = activity.window.decorView
        val content = activity.findViewById<View>(android.R.id.content)

        // NOTE — a "content fills the window" limb was designed, built, and then DELETED, because it
        // is vacuous on this platform. Measured on pixel5api35 with the ActionBar deliberately
        // restored: content == window == Rect(0,0,1080,2340) while a WindowDecorActionBar was live.
        // Under edge-to-edge the decor lets content span the full window regardless of chrome, so the
        // check can never fail and would have been pure false assurance. The reachability limbs below
        // are what actually catch the 0.2.0 class of bug. (A regression that merely re-adds the
        // ActionBar without breaking reachability is caught by ObserverHarnessChromeRuntimeTest.)

        // Walk every visible interactive control on this screen.
        val controls = interactiveControls(content)

        // Non-vacuity, at the screen level.
        val labels = controls.map { it.label }
        screen.required.forEach { required ->
            assertTrue(
                "[${screen.name}] non-vacuity: expected control \"$required\" was not found among $labels — " +
                    "the screen changed, or the control never made it on screen at all",
                labels.contains(required),
            )
        }

        controls.forEach { control ->
            val rect = onScreen(control.view)

            // LIMB 1 — content-under-system-bars. This is the one that catches the real bug.
            assertTrue(
                "[${screen.name}] limb 1: control \"${control.label}\" is outside the usable area — " +
                    "it is under the status bar, the nav bar, or a display cutout " +
                    "(control=$rect safe=$safe). This is exactly the 0.2.0 tester-blocking bug.",
                safe.contains(rect),
            )

            // LIMB 2 — arbitrary-overlay.
            val cx = rect.centerX()
            val cy = rect.centerY()
            val hit = hitTest(decor, cx, cy)
            assertTrue(
                "[${screen.name}] limb 2: control \"${control.label}\" is not hit-testable at its own " +
                    "center ($cx,$cy) — something is drawn over it (topmost view there: ${describe(hit)})",
                hit != null && (hit === control.view || isDescendantOf(hit, control.view)),
            )
        }
    }

    /** Topmost visible view containing the point, honouring draw order (last child drawn = on top). */
    private fun hitTest(root: View, x: Int, y: Int): View? {
        if (root.visibility != View.VISIBLE) return null
        if (!onScreen(root).contains(x, y)) return null
        if (root is ViewGroup) {
            for (index in root.childCount - 1 downTo 0) {
                hitTest(root.getChildAt(index), x, y)?.let { return it }
            }
        }
        return root
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun interactiveControls(root: View): List<Control> {
        val found = mutableListOf<Control>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view.isClickable && view.isShown) {
                found += Control(view, label(view))
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return found
    }

    private fun requireControl(root: View, label: String): View =
        requireNotNull(interactiveControls(root).firstOrNull { it.label == label }?.view) {
            "no control labelled \"$label\" on screen; found ${interactiveControls(root).map { it.label }}"
        }

    private fun label(view: View): String =
        (view as? android.widget.TextView)?.text?.toString() ?: view.javaClass.simpleName

    private fun describe(view: View?): String =
        view?.let { "${it.javaClass.simpleName}(${label(it)})" } ?: "nothing"

    private fun onScreen(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private data class Control(val view: View, val label: String)

    private data class Screen(val entry: String?, val name: String, val required: List<String>)

    private companion object {
        val MENU_ITEMS = listOf(
            "Permissions",
            "Scan pair QR",
            "PL status probe",
            "Start/stop observing",
            "Status + queue/sync",
            "Evidence + export",
        )
    }
}
