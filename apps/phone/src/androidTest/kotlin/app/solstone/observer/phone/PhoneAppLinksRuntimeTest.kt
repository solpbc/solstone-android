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
import app.solstone.platform.work.syncStores
import app.solstone.core.identity.GraphMutationResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneAppLinksRuntimeTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(application)
        assertTrue(syncStores(application).publisher.forget() is GraphMutationResult.Cleared)
        syncStores(application).journalConfirmationStore.settle()
    }

    @After
    fun tearDown() {
        assertTrue(syncStores(application).publisher.forget() is GraphMutationResult.Cleared)
        syncStores(application).journalConfirmationStore.settle()
        resetObserverRuntime()
    }

    @Test
    fun packageManagerIncludesPhoneForPairAndJournalLinksOnly() {
        val pairMatches = application.packageManager.queryIntentActivities(
            implicitView("https://go.solstone.app/p#garbage").setPackage(application.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val journalMatches = application.packageManager.queryIntentActivities(
            implicitView("https://go.solstone.app/j#abc").setPackage(application.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val wrongPathMatches = application.packageManager.queryIntentActivities(
            implicitView("https://go.solstone.app/not-p#garbage").setPackage(application.packageName),
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertTrue(pairMatches.any { it.activityInfo.packageName == application.packageName })
        assertTrue(journalMatches.any { it.activityInfo.packageName == application.packageName })
        assertFalse(wrongPathMatches.any { it.activityInfo.packageName == application.packageName })
    }

    @Test
    fun coldPairAppLinkWithGarbageFragmentShowsInvalidWithoutCamera() {
        ActivityScenario.launch<PhoneShellActivity>(explicitView("https://go.solstone.app/p#garbage")).use { scenario ->
            assertInvalidPasteEntry()
            scenario.onActivity { activity ->
                assertTrue(activity is PhoneShellActivity)
                assertFalse(allViews(activity).any { it.javaClass.simpleName.contains("QrPreviewView") })
            }
        }
    }

    @Test
    fun explicitlyDeliveredWrongPathShowsInvalidLink() {
        ActivityScenario.launch<PhoneShellActivity>(explicitView("https://go.solstone.app/not-p#garbage")).use { scenario ->
            assertInvalidPasteEntry()
            scenario.onActivity { activity ->
                assertTrue(activity is PhoneShellActivity)
            }
        }
    }

    @Test
    fun warmSystemDeliveryUsesSingleTopInstanceAndOnNewIntentPath() {
        val createdCount = AtomicInteger()
        val callbacks = observerCreatedCounter(createdCount)
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            val scenario = ActivityScenario.launch(PhoneShellActivity::class.java)
            lateinit var original: PhoneShellActivity
            scenario.onActivity { activity ->
                original = activity
                activity.startActivity(explicitView("https://go.solstone.app/p#garbage"))
            }

            assertInvalidPasteEntry()

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
        ActivityScenario.launch<PhoneShellActivity>(explicitView("https://go.solstone.app/p#garbage")).use { scenario ->
            assertInvalidPasteEntry()
            scenario.onActivity { activity ->
                assertTrue(activity is PhoneShellActivity)
                assertFalse(texts(activity).any { it == "Permissions" })
            }

            scenario.recreate()

            assertInvalidPasteEntry()

            scenario.onActivity { activity ->
                assertTrue(activity is PhoneShellActivity)
                assertFalse(texts(activity).any { it == "Permissions" })
            }
        }
    }

    @Test
    fun viewIntentWithoutDataLandsOnMenu() {
        val intent = Intent(Intent.ACTION_VIEW).setClass(application, PhoneShellActivity::class.java)
        ActivityScenario.launch<PhoneShellActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity is PhoneShellActivity)
                assertFalse(texts(activity).any { it == "Permissions" })
            }
        }
    }

    private fun explicitView(uri: String): Intent =
        implicitView(uri).setClass(application, PhoneShellActivity::class.java)

    private fun assertInvalidPasteEntry() {
        // A link delivered to the running shell pauses it, and a paused shell has no compose
        // root for the rule to read, so no root yet means the entry is not showing yet.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag("pairingLinkField")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeRule.onNodeWithTag("pairingLinkField").assertIsDisplayed()
        composeRule.onNodeWithText("enter a valid pairing link from your journal.").assertIsDisplayed()
    }

    @Test
    fun validColdArrivalShowsPrefilledConnectWithoutCamera() {
        val link = "https://go.solstone.app/p#0M0G86WY18004AGA0012P2G008P0M0025PGV5GYMWQV0E60H48SM8NB6EY4DXBDYXZ5FXENY04HMASW9NF6YY"
        ActivityScenario.launch<PhoneShellActivity>(explicitView(link)).use { scenario ->
            composeRule.waitUntil(15_000) {
                composeRule.onAllNodesWithTag("pairingLinkField").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("pairingLinkField").assertTextContains(link)
            composeRule.onNodeWithTag("pairingConnect").assertIsEnabled()
            scenario.onActivity { activity ->
                assertFalse(allViews(activity).any { it.javaClass.simpleName.contains("QrPreviewView") })
            }
        }
    }

    private fun implicitView(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

    private fun observerCreatedCounter(count: AtomicInteger) =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is PhoneShellActivity) count.incrementAndGet()
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    private fun waitForText(scenario: ActivityScenario<PhoneShellActivity>, expected: String) {
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
