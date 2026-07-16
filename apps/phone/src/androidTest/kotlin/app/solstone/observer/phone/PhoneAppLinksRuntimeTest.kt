// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.observer.scaffold.ObserverActivity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneAppLinksRuntimeTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(application)
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun packageManagerIncludesPhoneForPairAppLinkOnly() {
        val pairMatches = application.packageManager.queryIntentActivities(
            implicitView("https://go.solstone.app/p#garbage").setPackage(application.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val wrongPathMatches = application.packageManager.queryIntentActivities(
            implicitView("https://go.solstone.app/not-p#garbage").setPackage(application.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertTrue(pairMatches.any { it.activityInfo.packageName == application.packageName })
        assertFalse(wrongPathMatches.any { it.activityInfo.packageName == application.packageName })
    }

    @Test
    fun coldPairAppLinkWithGarbageFragmentShowsInvalidWithoutCamera() {
        ActivityScenario.launch<ObserverActivity>(explicitView("https://go.solstone.app/p#garbage")).use { scenario ->
            waitForText(scenario, "Invalid pair link")
            scenario.onActivity { activity ->
                assertFalse(allViews(activity).any { it.javaClass.simpleName.contains("QrPreviewView") })
            }
        }
    }

    @Test
    fun explicitlyDeliveredWrongPathShowsInvalidLink() {
        ActivityScenario.launch<ObserverActivity>(explicitView("https://go.solstone.app/not-p#garbage")).use { scenario ->
            waitForText(scenario, "Invalid pair link")
        }
    }

    @Test
    fun warmSystemDeliveryUsesSingleTopInstanceAndOnNewIntentPath() {
        val createdCount = AtomicInteger()
        val callbacks = observerCreatedCounter(createdCount)
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            val scenario = ActivityScenario.launch(ObserverActivity::class.java)
            lateinit var original: ObserverActivity
            scenario.onActivity { activity ->
                original = activity
                activity.startActivity(explicitView("https://go.solstone.app/p#garbage"))
            }

            waitForText(scenario, "Invalid pair link")
            scenario.onActivity { activity ->
                assertEquals(System.identityHashCode(original), System.identityHashCode(activity))
                assertEquals(1, createdCount.get())
                activity.finish()
            }
            waitUntil("warm activity destroyed") { original.isDestroyed }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    @Test
    fun recreateDoesNotConsumeSurvivingViewIntentAgain() {
        ActivityScenario.launch<ObserverActivity>(explicitView("https://go.solstone.app/p#garbage")).use { scenario ->
            waitForText(scenario, "Invalid pair link")

            scenario.recreate()

            waitForText(scenario, "Permissions")
            scenario.onActivity { activity ->
                assertFalse(texts(activity).contains("Invalid pair link"))
            }
        }
    }

    @Test
    fun viewIntentWithoutDataLandsOnMenu() {
        val intent = Intent(Intent.ACTION_VIEW).setClass(application, ObserverActivity::class.java)
        ActivityScenario.launch<ObserverActivity>(intent).use { scenario ->
            waitForText(scenario, "Permissions")
        }
    }

    private fun explicitView(uri: String): Intent =
        implicitView(uri).setClass(application, ObserverActivity::class.java)

    private fun implicitView(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

    private fun observerCreatedCounter(count: AtomicInteger) =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is ObserverActivity) count.incrementAndGet()
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    private fun waitForText(scenario: ActivityScenario<ObserverActivity>, expected: String) {
        waitUntil(expected) {
            var present = false
            scenario.onActivity { present = expected in texts(it) }
            present
        }
    }

    private fun texts(activity: Activity): List<String> =
        allViews(activity).filterIsInstance<TextView>().map { it.text.toString() }

    private fun allViews(activity: Activity): List<View> {
        val views = mutableListOf<View>()
        fun visit(view: View) {
            views += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(activity.findViewById(android.R.id.content))
        return views
    }
}
