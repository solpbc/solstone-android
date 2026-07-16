// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.observer.harness.decimalBytes
import app.solstone.platform.persistence.room.JOURNAL_CACHE_LIMIT_CHOICES_BYTES
import app.solstone.platform.persistence.room.JournalCacheLimitStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlassesJournalCacheScreenRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        GlassesHarnessRuntime.runtime?.closeForTest()
        GlassesHarnessRuntime.runtime = null
        GlassesHarnessRuntime.hooks = null
        context.filesDir.resolve("journal-cache-limit").delete()
    }

    @After
    fun tearDown() {
        GlassesHarnessRuntime.hooks = null
        GlassesHarnessRuntime.runtime?.closeForTest()
        GlassesHarnessRuntime.runtime = null
    }

    @Test
    fun localCacheLoadsOffMainAndPersistsAChoiceAcrossUiRecreation() {
        var loaded = CountDownLatch(1)
        GlassesHarnessRuntime.hooks = GlassesRuntimeHooks().also {
            it.onJournalCacheLoadComplete = { loaded.countDown() }
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val container = waitForContainer()
            assertTrue(waitForRecovery(container))
            waitForInitialPass(container)
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Local cache")
                assertEquals("filesystem/Room load must not finish inline on main", 1L, loaded.count)
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity -> assertCompleteScreen(collectTexts(activity.findViewById(android.R.id.content))) }

            val selected = JOURNAL_CACHE_LIMIT_CHOICES_BYTES.first { it != 4_000_000_000L }
            loaded = CountDownLatch(1)
            GlassesHarnessRuntime.hooks?.onJournalCacheLoadComplete = { loaded.countDown() }
            scenario.onActivity { activity ->
                clickButton(activity.findViewById(android.R.id.content), "Use ${decimalBytes(selected)}")
                assertEquals("durable save must not finish inline on main", 1L, loaded.count)
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            assertEquals(selected, JournalCacheLimitStore(context.filesDir.resolve("journal-cache-limit")).snapshot().configuredLimitBytes)

            scenario.recreate()
            loaded = CountDownLatch(1)
            GlassesHarnessRuntime.hooks?.onJournalCacheLoadComplete = { loaded.countDown() }
            scenario.onActivity { activity -> clickButton(activity.findViewById(android.R.id.content), "Local cache") }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val texts = collectTexts(activity.findViewById(android.R.id.content))
                assertTrue(texts.any { it.contains("Current limit: ${decimalBytes(selected)}") })
            }
        }
    }

    private fun assertCompleteScreen(texts: List<String>) {
        assertTrue(texts.any { it.contains("Local cache") })
        assertTrue(texts.any { it.contains("Cache usage:") })
        assertTrue(texts.any { it.contains("Current limit: 4 GB") })
        JOURNAL_CACHE_LIMIT_CHOICES_BYTES.forEach { choice ->
            assertTrue(texts.any { it.contains("Use ${decimalBytes(choice)}") })
        }
    }

    private fun waitForContainer(): GlassesAppContainer {
        repeat(100) {
            (GlassesHarnessRuntime.container as? GlassesAppContainer)?.let { return it }
            Thread.sleep(100L)
        }
        error("glasses container was not created")
    }

    private fun waitForRecovery(container: GlassesAppContainer): Boolean {
        repeat(100) {
            if (container.recoveryCompleted) return true
            Thread.sleep(100L)
        }
        return container.recoveryCompleted
    }

    private fun waitForInitialPass(container: GlassesAppContainer) {
        repeat(100) {
            if (container.journalCacheState().latestPass != null) return
            Thread.sleep(100L)
        }
        error("initial local cache pass did not complete")
    }

    private fun collectTexts(root: View): List<String> = buildList {
        fun visit(view: View) {
            if (view is TextView) add(view.text.toString())
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }

    private fun clickButton(root: View, label: String) {
        fun visit(view: View): Boolean {
            if (view is Button && view.text.toString() == label) return view.performClick().let { true }
            if (view is ViewGroup) for (i in 0 until view.childCount) if (visit(view.getChildAt(i))) return true
            return false
        }
        check(visit(root)) { "button not found: $label" }
    }
}
